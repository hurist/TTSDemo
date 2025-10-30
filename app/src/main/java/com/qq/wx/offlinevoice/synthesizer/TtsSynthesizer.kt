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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 文本转语音合成器（命令处理器架构）
 *
 * [最终修复] 修复了 onSynthesisComplete 后调用 speak 无声的问题。
 * 关键在于 handleStop 和 handleSpeak 的逻辑调整，正确复用处于 IDLE 状态的 AudioPlayer。
 */
class TtsSynthesizer(
    context: Context,
    private val voiceName: String
) {
    // --- 内部命令定义 ---
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

        data class InternalSentenceStart(val index: Int, val sentence: String) : Command()
        data class InternalSentenceEnd(val index: Int, val sentence: String) : Command()
        object InternalSynthesisFinished : Command()
        data class InternalError(val message: String) : Command()
    }

    // --- 状态变量 (仅由命令处理器协程访问) ---
    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE
    private val sentences = mutableListOf<String>()
    private var playingSentenceIndex: Int = 0
    private var synthesisSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentVoice: String = voiceName
    private var currentCallback: TtsCallback? = null

    private val voiceCode: String = voiceName
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private var synthesisJob: Job? = null

    private val audioPlayer: AudioPlayer = AudioPlayer(TtsConstants.DEFAULT_SAMPLE_RATE)
    private val engineMutex = Mutex()

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

        scope.launch {
            commandProcessor()
        }

        if (instanceCount.incrementAndGet() == 1) {
            nativeEngine = SynthesizerNative()
            nativeEngine?.init(voiceDataPath.toByteArray())
        }

        sendCommand(Command.SetCallback(null))
    }

    fun setCallback(callback: TtsCallback?) = sendCommand(Command.SetCallback(callback))
    fun setSpeed(speed: Float) = sendCommand(Command.SetSpeed(speed))
    fun setVolume(volume: Float) = sendCommand(Command.SetVolume(volume))
    fun setVoice(voiceName: String) = sendCommand(Command.SetVoice(voiceName))

    // [修改点] speak 方法不再需要发送 Stop 命令，handleSpeak 会处理好状态转换
    fun speak(text: String) {
        sendCommand(Command.Speak(text))
    }

    fun pause() = sendCommand(Command.Pause)
    fun resume() = sendCommand(Command.Resume)
    fun stop() = sendCommand(Command.Stop)
    fun release() = sendCommand(Command.Release)
    fun isSpeaking(): Boolean = currentState == TtsPlaybackState.PLAYING
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
                    break
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
                    if (command.index == sentences.size - 1) {
                        updateState(TtsPlaybackState.IDLE)
                        currentCallback?.onSynthesisComplete()
                    }
                }
                is Command.InternalSynthesisFinished -> { }
                is Command.InternalError -> {
                    handleStop()
                    currentCallback?.onError(command.message)
                }
            }
        }
    }

    // [修改点] handleSpeak 现在负责处理从任何状态开始播放的逻辑
    private suspend fun handleSpeak(text: String) {
        // 如果当前正在播放或暂停，先完整地停止
        if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
            synthesisJob?.cancelAndJoin()
            synthesisJob = null
            audioPlayer.stopAndRelease()
        }
        // 如果是从 IDLE 状态过来，AudioPlayer 是好的，可以复用，不需要 stopAndRelease

        sentences.clear()
        sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
        if (sentences.isEmpty()) {
            currentCallback?.onError("文本中没有有效的句子")
            return
        }
        playingSentenceIndex = 0
        synthesisSentenceIndex = 0
        audioPlayer.configureBuffering(prerollMs = 300, lowWatermarkMs = 120, highWatermarkMs = 350, autoRebuffer = true)

        // startIfNeeded 会正确处理：如果 player 已存在且 active (IDLE 状态)，它什么都不做；
        // 如果 player 被 stopAndRelease 了，它会创建一个新的。
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
        if (currentState != TtsPlaybackState.PLAYING) return
        audioPlayer.pause()
        updateState(TtsPlaybackState.PAUSED)
        currentCallback?.onPaused()
    }

    private fun handleResume() {
        if (currentState != TtsPlaybackState.PAUSED) return
        audioPlayer.resume()
        updateState(TtsPlaybackState.PLAYING)
        currentCallback?.onResumed()
    }

    // [修改点] handleStop 现在是纯粹的中断操作
    private fun handleStop() {
        if (currentState == TtsPlaybackState.IDLE) return // 如果已经是 IDLE，什么都不做

        synthesisJob?.cancel()
        synthesisJob = null
        audioPlayer.stopAndRelease()
        sentences.clear()
        updateState(TtsPlaybackState.IDLE)
    }

    private fun handleRelease() {
        handleStop()
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
        synthesisJob?.cancelAndJoin()
        synthesisJob = null
        Log.d(TAG, "Synthesis job cancelled and joined.")
        audioPlayer.resetBlocking(prerollMs = 220)
        Log.d(TAG, "AudioPlayer has been reset synchronously.")
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
                    if (numSamples <= 0) break
                    if (!hasEnqueuedStartMarker) {
                        audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) }
                        hasEnqueuedStartMarker = true
                    }
                    val validSamples = minOf(pcmArray.size, numSamples)
                    if (validSamples > 0) {
                        val validPcm = pcmArray.copyOf(validSamples)
                        audioPlayer.enqueuePcm(validPcm)
                    }
                    delay(1)
                }
                if (!hasEnqueuedStartMarker) {
                    audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) }
                }
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

    private fun updateState(newState: TtsPlaybackState) {
        if (currentState != newState) {
            currentState = newState
            currentCallback?.onStateChanged(newState)
        }
    }
}