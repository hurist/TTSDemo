package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.os.Process
import android.util.Log
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 文本转语音合成器，采用**命令处理器（Command Processor）架构**。
 *
 * 核心设计:
 * 1.  **串行化处理**: 所有的公开方法（如 `speak`, `pause`, `setSpeed`）都不会直接修改状态，而是将操作封装成一个 `Command` 对象，
 *     发送到一个 `commandChannel` 队列中。
 * 2.  **单一工作协程**: 一个名为 `commandProcessor` 的协程是唯一一个消费 `commandChannel` 的消费者。它按顺序取出并处理命令，
 *     这意味着所有状态的变更都是串行发生的，从根本上杜绝了竞态条件。
 * 3.  **响应式UI状态**: 通过 `isPlaying` 这个 `StateFlow` 向UI层暴露一个简单、可靠的播放状态，便于构建响应式UI。
 * 4.  **同步清理**: 在处理中断性操作（如 `stop` 或在播放中调用 `speak`）时，会通过 `cancelAndJoin` 和 `stopAndReleaseBlocking`
 *     同步等待所有后台任务（合成、播放）完全结束后，再进行下一步操作，确保了状态转换的原子性和可靠性。
 */
class TtsSynthesizer(
    context: Context,
    private val voiceName: String
) {
    /**
     * 内部命令的密封类定义。
     * 包含了所有可能对合成器状态进行修改的操作。
     */
    private sealed class Command {
        data class Speak(val text: String) : Command()
        data class SetSpeed(val speed: Float) : Command()
        data class SetVolume(val volume: Float) : Command()
        data class SetVoice(val voiceName: String) : Command()
        data class SetCallback(val callback: TtsCallback?) : Command()
        object Pause : Command()
        object Resume : Command()
        object Stop : Command()
        object Release : Command()

        // 由后台任务（合成协程、播放器回调）产生的内部事件，同样作为命令入队处理，以保证顺序
        data class InternalSentenceStart(val index: Int, val sentence: String) : Command()
        data class InternalSentenceEnd(val index: Int, val sentence: String) : Command()
        object InternalSynthesisFinished : Command()
        data class InternalError(val message: String) : Command()
    }

    // --- 状态变量 (这些变量只能被 commandProcessor 协程访问和修改) ---
    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE
    private val sentences = mutableListOf<String>()
    private var playingSentenceIndex: Int = 0
    private var synthesisSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentVoice: String = voiceName
    private var currentCallback: TtsCallback? = null

    /**
     * [UI状态] 向UI层暴露的播放状态。
     * `true` 表示正在播放或暂停中。
     * `false` 表示空闲、停止或已释放。
     */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val voiceCode: String = voiceName
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private var synthesisJob: Job? = null

    private val audioPlayer: AudioPlayer = AudioPlayer(TtsConstants.DEFAULT_SAMPLE_RATE)
    private val engineMutex = Mutex() // 用于保护对 Native TTS 引擎的同步调用

    companion object {
        private const val TAG = "TtsSynthesizer"
        private val instanceCount = AtomicInteger(0)
        @Volatile private var nativeEngine: SynthesizerNative? = null
        @Volatile private var currentVoiceCode: String? = null

        init {
            try {
                System.loadLibrary("hwTTS")
                System.loadLibrary("weread-tts")
            } catch (_: UnsatisfiedLinkError) { }
        }
    }

    init {
        val pathBuilder = StringBuilder()
        PathUtils.appendExternalVoicePath(
            byteArrayOf(68, 111, 42, 100, -19),
            byteArrayOf(50, 0, 67, 7, -120, 65, 34, 26),
            context,
            pathBuilder
        )
        voiceDataPath = PathUtils.appendDecodedString(
            byteArrayOf(-105, 16, 22, -80, -70, 86, 114),
            byteArrayOf(-72, 103, 115, -62, -33, 55, 22, -27),
            pathBuilder
        )

        // 启动命令处理器协程
        scope.launch {
            commandProcessor()
        }

        // 初始化 Native 引擎
        if (instanceCount.incrementAndGet() == 1) {
            nativeEngine = SynthesizerNative()
            nativeEngine?.init(voiceDataPath.toByteArray())
        }

        sendCommand(Command.SetCallback(null))
    }

    // --- 公开 API: 仅将操作封装成命令发送，立即返回，不阻塞调用方线程 ---
    fun setCallback(callback: TtsCallback?) = sendCommand(Command.SetCallback(callback))
    fun setSpeed(speed: Float) = sendCommand(Command.SetSpeed(speed))
    fun setVolume(volume: Float) = sendCommand(Command.SetVolume(volume))
    fun setVoice(voiceName: String) = sendCommand(Command.SetVoice(voiceName))
    fun speak(text: String) = sendCommand(Command.Speak(text))
    fun pause() = sendCommand(Command.Pause)
    fun resume() = sendCommand(Command.Resume)
    fun stop() = sendCommand(Command.Stop)
    fun release() = sendCommand(Command.Release)

    fun isSpeaking(): Boolean = isPlaying.value

    fun getStatus(): TtsStatus {
        val i = playingSentenceIndex.coerceAtMost(sentences.size - 1).coerceAtLeast(0)
        val currentSentence = if (sentences.isNotEmpty() && i in sentences.indices) sentences[i] else ""
        return TtsStatus(
            state = currentState,
            totalSentences = sentences.size,
            currentSentenceIndex = playingSentenceIndex,
            currentSentence = currentSentence
        )
    }

    private fun sendCommand(command: Command) {
        commandChannel.trySend(command)
    }

    /**
     * 核心的命令处理器。这是一个无限循环，按顺序处理所有到来的命令。
     * 它是本类中唯一可以修改状态的地方。
     */
    private suspend fun commandProcessor() {
        for (command in commandChannel) {
            when (command) {
                is Command.Speak -> handleSpeak(command.text)
                is Command.SetSpeed -> handleSetSpeed(command.speed)
                is Command.SetVolume -> handleSetVolume(command.volume)
                is Command.SetVoice -> handleSetVoice(command.voiceName)
                is Command.Pause -> handlePause()
                is Command.Resume -> handleResume()
                is Command.Stop -> handleStop()
                is Command.Release -> {
                    handleRelease()
                    break // 收到 Release 命令后，退出循环，协程结束
                }
                is Command.SetCallback -> {
                    currentCallback = command.callback
                    currentCallback?.onInitialized(true)
                }
                is Command.InternalSentenceStart -> {
                    playingSentenceIndex = command.index
                    currentCallback?.onSentenceStart(command.index, command.sentence, sentences.size)
                }
                is Command.InternalSentenceEnd -> {
                    currentCallback?.onSentenceComplete(command.index, command.sentence)
                    // 当最后一个句子播放完毕时，整个任务完成
                    if (command.index == sentences.size - 1) {
                        updateState(TtsPlaybackState.IDLE)
                        currentCallback?.onSynthesisComplete()
                    }
                }
                is Command.InternalSynthesisFinished -> { /* 合成完成是一个内部状态，UI层面不关心，最终完成由播放最后一个句子决定 */ }
                is Command.InternalError -> {
                    handleStop() // 发生任何错误，都执行完整的停止流程
                    currentCallback?.onError(command.message)
                }
            }
        }
    }

    /**
     * 处理 `speak` 请求。
     * 通过先调用同步的 `handleStop` 来确保在播放新内容前，系统处于绝对干净的状态。
     */
    private suspend fun handleSpeak(text: String) {
        // 如果当前正在播放或暂停，先执行一次完整的、同步的停止操作。
        if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
            handleStop()
        }
        // 当 handleStop 返回时，我们能确保系统已处于一个干净的 IDLE 状态。

        sentences.clear()
        sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
        if (sentences.isEmpty()) {
            currentCallback?.onError("文本中没有有效的句子")
            return
        }
        playingSentenceIndex = 0
        synthesisSentenceIndex = 0
        audioPlayer.configureBuffering(prerollMs = 300, lowWatermarkMs = 120, highWatermarkMs = 350, autoRebuffer = true)

        // startIfNeeded 现在可以安全地创建一个新的播放器会话，因为旧的已被彻底清理。
        audioPlayer.startIfNeeded(volume = currentVolume)

        updateState(TtsPlaybackState.PLAYING)
        currentCallback?.onSynthesisStart()
        startSynthesis()
    }

    private suspend fun handleSetSpeed(speed: Float) {
        val newSpeed = speed.coerceIn(0.5f, 3.0f)
        if (currentSpeed == newSpeed) return
        currentSpeed = newSpeed
        if (currentState == TtsPlaybackState.PLAYING) {
            softRestart()
        }
    }

    private suspend fun handleSetVoice(voiceName: String) {
        if (currentVoice == voiceName) return
        currentVoice = voiceName
        if (currentState == TtsPlaybackState.PLAYING) {
            softRestart()
        }
    }

    private fun handleSetVolume(volume: Float) {
        val newVolume = volume.coerceIn(0.0f, 1.0f)
        if (currentVolume == newVolume) return
        currentVolume = newVolume
        audioPlayer.setVolume(newVolume)
    }

    private fun handlePause() {
        // 严格的状态检查，只有在播放中才能暂停
        if (currentState != TtsPlaybackState.PLAYING) {
            Log.w(TAG, "Cannot pause, current state is $currentState, not PLAYING.")
            return
        }
        audioPlayer.pause()
        updateState(TtsPlaybackState.PAUSED)
        currentCallback?.onPaused()
    }

    private fun handleResume() {
        // 严格的状态检查，只有在暂停中才能恢复
        if (currentState != TtsPlaybackState.PAUSED) {
            Log.w(TAG, "Cannot resume, current state is $currentState, not PAUSED.")
            return
        }
        audioPlayer.resume()
        updateState(TtsPlaybackState.PLAYING)
        currentCallback?.onResumed()
    }

    /**
     * 处理 `stop` 请求。这是一个挂起函数，它会同步地停止并等待所有相关任务结束。
     */
    private suspend fun handleStop() {
        if (currentState == TtsPlaybackState.IDLE) return // 如果已是空闲，无需操作

        // 1. 等待合成任务完全死亡
        synthesisJob?.cancelAndJoin()
        synthesisJob = null

        // 2. 等待播放器任务完全死亡并释放所有资源
        audioPlayer.stopAndReleaseBlocking()

        // 3. 清理状态
        sentences.clear()
        updateState(TtsPlaybackState.IDLE)
    }

    private suspend fun handleRelease() {
        handleStop() // handleStop 现在是同步的，所以这里是安全的
        commandChannel.close()
        if (instanceCount.decrementAndGet() == 0) {
            nativeEngine?.destroy()
            nativeEngine = null
            currentVoiceCode = null
        }
    }

    private suspend fun softRestart() {
        Log.d(TAG, "Soft restart requested for sentence index: $playingSentenceIndex")
        val restartIndex = playingSentenceIndex

        // 1. 等待旧的合成任务完全停止
        synthesisJob?.cancelAndJoin()
        synthesisJob = null
        Log.d(TAG, "Synthesis job cancelled and joined.")

        // 2. 同步重置播放器，等待其确认完成
        audioPlayer.resetBlocking(prerollMs = 220)
        Log.d(TAG, "AudioPlayer has been reset synchronously.")

        // 3. 在完全干净的状态下，启动新的合成任务
        synthesisSentenceIndex = restartIndex
        startSynthesis()
        Log.d(TAG, "New synthesis started from index: $restartIndex")
    }

    private fun startSynthesis() {
        synthesisJob = scope.launch(Dispatchers.Default) {
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
                while (isActive && synthesisSentenceIndex < sentences.size) {
                    val index = synthesisSentenceIndex
                    val sentence = sentences[index]
                    val ok = synthesizeAndEnqueue(index, sentence)
                    if (!ok || !isActive) break
                    synthesisSentenceIndex++
                }
                sendCommand(Command.InternalSynthesisFinished)
            } catch (e: CancellationException) {
                // Job被取消是正常操作，无需上报错误
            } catch (t: Throwable) {
                sendCommand(Command.InternalError("合成失败: ${t.message}"))
            }
        }
    }

    private suspend fun synthesizeAndEnqueue(index: Int, sentence: String): Boolean {
        return engineMutex.withLock {
            try {
                val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
                if (prepareResult != 0) {
                    sendCommand(Command.InternalError("准备句子失败: $sentence"))
                    return@withLock false
                }
                val synthResult = IntArray(1)
                val pcmArray = pcmBuffer.array()
                var hasEnqueuedStartMarker = false
                while (synthesisJob?.isActive == true) {
                    val synthesisStatus = nativeEngine?.synthesize(
                        pcmArray, TtsConstants.PCM_BUFFER_SIZE, synthResult, 1
                    ) ?: -1
                    if (synthesisStatus == -1) {
                        nativeEngine?.reset()
                        return@withLock false
                    }
                    val numSamples = synthResult[0]
                    if (numSamples <= 0) break // 当前句子合成完成
                    if (!hasEnqueuedStartMarker) {
                        audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) }
                        hasEnqueuedStartMarker = true
                    }
                    val validSamples = minOf(pcmArray.size, numSamples)
                    if (validSamples > 0) {
                        val validPcm = pcmArray.copyOf(validSamples)
                        audioPlayer.enqueuePcm(validPcm)
                    }
                    delay(1) // 礼貌性地让出CPU，防止合成任务100%占用
                }
                if (!hasEnqueuedStartMarker) {
                    // 对于空句子，也要确保触发 onSentenceStart
                    audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) }
                }
                // 在每个句子合成完成后，放入句末标记
                audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceEnd(index, sentence)) }
                true
            } catch (e: CancellationException) {
                false
            } catch (e: Exception) {
                sendCommand(Command.InternalError("合成错误: ${e.message}"))
                false
            } finally {
                nativeEngine?.reset()
            }
        }
    }

    private fun prepareForSynthesis(text: String, speed: Float, volume: Float): Int {
        synchronized(this) {
            if (currentVoice != currentVoiceCode) {
                currentVoiceCode = currentVoice
                nativeEngine?.setVoiceName(currentVoice)
            }
            nativeEngine?.setSpeed(speed)
            nativeEngine?.setVolume(volume)
            var prepareResult = -1
            for (attempt in 0 until TtsConstants.MAX_PREPARE_RETRIES) {
                prepareResult = nativeEngine?.prepareUTF8(text.toByteArray()) ?: -1
                if (prepareResult == 0) break
                nativeEngine?.setVoiceName(currentVoice)
            }
            return prepareResult
        }
    }

    /**
     * 状态更新的唯一入口。
     * 在这里同时更新内部状态和暴露给UI的 StateFlow。
     */
    private fun updateState(newState: TtsPlaybackState) {
        if (currentState != newState) {
            currentState = newState
            currentCallback?.onStateChanged(newState)

            // 更新暴露给UI的 isPlaying 状态
            // 只要会话是活动的（播放中或暂停中），UI上就认为是在“播放”状态
            _isPlaying.value = newState == TtsPlaybackState.PLAYING
        }
    }
}