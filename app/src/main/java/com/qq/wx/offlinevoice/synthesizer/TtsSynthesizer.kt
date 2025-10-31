package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.os.Process
import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.online.MediaCodecMp3Decoder
import com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder
import com.qq.wx.offlinevoice.synthesizer.online.OnlineTtsApi
import com.qq.wx.offlinevoice.synthesizer.online.WxReaderApi
import java.io.IOException
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
import kotlin.coroutines.coroutineContext

class TtsSynthesizer(
    context: Context,
    private val voiceName: String
) {
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
        data class SetStrategy(val strategy: TtsStrategy) : Command()
        data class InternalSentenceStart(val index: Int, val sentence: String) : Command()
        data class InternalSentenceEnd(val index: Int, val sentence: String) : Command()
        object InternalSynthesisFinished : Command()
        data class InternalError(val message: String) : Command()
    }

    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE
    private val sentences = mutableListOf<String>()
    private var playingSentenceIndex: Int = 0
    private var synthesisSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentVoice: String = voiceName
    private var currentCallback: TtsCallback? = null
    private val strategyManager: SynthesisStrategyManager
    private val onlineApi: OnlineTtsApi
    private val mp3Decoder: Mp3Decoder
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val voiceCode: String = voiceName
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private var synthesisJob: Job? = null
    private val audioPlayer: AudioPlayer = AudioPlayer(initialSampleRate = TtsConstants.DEFAULT_SAMPLE_RATE)
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
            context, pathBuilder
        )
        voiceDataPath = PathUtils.appendDecodedString(
            byteArrayOf(-105, 16, 22, -80, -70, 86, 114),
            byteArrayOf(-72, 103, 115, -62, -33, 55, 22, -27),
            pathBuilder
        )
        strategyManager = SynthesisStrategyManager(context.applicationContext)
        onlineApi = WxReaderApi
        mp3Decoder = MediaCodecMp3Decoder(context.applicationContext)
        scope.launch { commandProcessor() }
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
    fun speak(text: String) = sendCommand(Command.Speak(text))
    fun pause() = sendCommand(Command.Pause)
    fun resume() = sendCommand(Command.Resume)
    fun stop() = sendCommand(Command.Stop)
    fun release() = sendCommand(Command.Release)
    fun setStrategy(strategy: TtsStrategy) = sendCommand(Command.SetStrategy(strategy))
    fun isSpeaking(): Boolean = isPlaying.value
    fun getStatus(): TtsStatus {
        val i = playingSentenceIndex.coerceAtMost(sentences.size - 1).coerceAtLeast(0)
        val currentSentence = if (sentences.isNotEmpty() && i in sentences.indices) sentences[i] else ""
        return TtsStatus(currentState, sentences.size, playingSentenceIndex, currentSentence)
    }
    private fun sendCommand(command: Command) { commandChannel.trySend(command) }

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
                is Command.Release -> { handleRelease(); break }
                is Command.SetStrategy -> strategyManager.setStrategy(command.strategy)
                is Command.SetCallback -> { currentCallback = command.callback; currentCallback?.onInitialized(true) }
                is Command.InternalSentenceStart -> { playingSentenceIndex = command.index; currentCallback?.onSentenceStart(command.index, command.sentence, sentences.size) }
                is Command.InternalSentenceEnd -> {
                    currentCallback?.onSentenceComplete(command.index, command.sentence)
                    if (command.index == sentences.size - 1) { updateState(TtsPlaybackState.IDLE); currentCallback?.onSynthesisComplete() }
                }
                is Command.InternalSynthesisFinished -> { /* No-op */ }
                is Command.InternalError -> { handleStop(); currentCallback?.onError(command.message) }
            }
        }
    }

    private suspend fun handleSpeak(text: String) {
        if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) { handleStop() }
        sentences.clear()
        sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
        if (sentences.isEmpty()) { currentCallback?.onError("文本中没有有效的句子"); return }
        playingSentenceIndex = 0
        synthesisSentenceIndex = 0
        audioPlayer.startIfNeeded(volume = currentVolume)
        updateState(TtsPlaybackState.PLAYING)
        currentCallback?.onSynthesisStart()
        startSynthesis()
    }

    private suspend fun handleSetSpeed(speed: Float) {
        val newSpeed = speed.coerceIn(0.5f, 3.0f)
        if (currentSpeed == newSpeed) return
        currentSpeed = newSpeed
        if (currentState == TtsPlaybackState.PLAYING) { softRestart() }
    }

    private suspend fun handleSetVoice(voiceName: String) {
        if (currentVoice == voiceName) return
        currentVoice = voiceName
        if (currentState == TtsPlaybackState.PLAYING) { softRestart() }
    }

    private fun handleSetVolume(volume: Float) {
        val newVolume = volume.coerceIn(0.0f, 1.0f)
        if (currentVolume == newVolume) return
        currentVolume = newVolume
        audioPlayer.setVolume(newVolume)
    }

    private fun handlePause() {
        if (currentState != TtsPlaybackState.PLAYING) { Log.w(TAG, "无法暂停，当前状态为 $currentState, 而非 PLAYING。"); return }
        audioPlayer.pause()
        updateState(TtsPlaybackState.PAUSED)
        currentCallback?.onPaused()
    }

    private fun handleResume() {
        if (currentState != TtsPlaybackState.PAUSED) { Log.w(TAG, "无法恢复，当前状态为 $currentState, 而非 PAUSED。"); return }
        audioPlayer.resume()
        updateState(TtsPlaybackState.PLAYING)
        currentCallback?.onResumed()
    }

    private suspend fun handleStop() {
        if (currentState == TtsPlaybackState.IDLE) return
        synthesisJob?.cancelAndJoin()
        synthesisJob = null
        audioPlayer.stopAndReleaseBlocking()
        sentences.clear()
        updateState(TtsPlaybackState.IDLE)
    }

    private suspend fun handleRelease() {
        handleStop()
        commandChannel.close()
        strategyManager.release()
        if (instanceCount.decrementAndGet() == 0) {
            nativeEngine?.destroy()
            nativeEngine = null
            currentVoiceCode = null
        }
    }

    private suspend fun softRestart() {
        Log.d(TAG, "软重启请求，句子索引: $playingSentenceIndex")
        val restartIndex = playingSentenceIndex
        synthesisJob?.cancelAndJoin()
        synthesisJob = null
        Log.d(TAG, "旧的合成任务已取消并等待结束。")
        audioPlayer.resetBlocking()
        Log.d(TAG, "播放器已同步重置。")
        synthesisSentenceIndex = restartIndex
        startSynthesis()
        Log.d(TAG, "新的合成任务已从索引 $restartIndex 处启动。")
    }

    private fun startSynthesis() {
        synthesisJob = scope.launch(Dispatchers.Default) {
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
                var activeMode = strategyManager.getDesiredMode()
                Log.i(TAG, "合成循环启动。初始合成模式: $activeMode")
                val modeWatcherJob = launch {
                    strategyManager.isNetworkGood.collect { _ ->
                        val desiredMode = strategyManager.getDesiredMode()
                        if (activeMode != desiredMode && isActive) {
                            if (activeMode == SynthesisMode.OFFLINE && desiredMode == SynthesisMode.ONLINE) {
                                Log.i(TAG, "网络状况改善。主动升级至[在线模式]。正在重置播放队列...")
                                audioPlayer.resetBlocking()
                                activeMode = SynthesisMode.ONLINE
                            } else if (activeMode == SynthesisMode.ONLINE && desiredMode == SynthesisMode.OFFLINE) {
                                Log.w(TAG, "网络状况变差。主动降级至[离线模式]以合成后续句子。")
                                activeMode = SynthesisMode.OFFLINE
                            }
                        }
                    }
                }
                while (isActive && synthesisSentenceIndex < sentences.size) {
                    val index = synthesisSentenceIndex
                    val sentence = sentences[index]
                    val success = if (activeMode == SynthesisMode.ONLINE) {
                        val onlineSuccess = performOnlineSynthesis(index, sentence)
                        if (!onlineSuccess) {
                            Log.w(TAG, "在线合成句子 $index 失败。被动回退至[离线模式]。")
                            activeMode = SynthesisMode.OFFLINE
                            performOfflineSynthesis(index, sentence)
                        } else {
                            true
                        }
                    } else {
                        performOfflineSynthesis(index, sentence)
                    }
                    if (success) {
                        synthesisSentenceIndex++
                    } else {
                        if (isActive) { sendCommand(Command.InternalError("句子合成失败: '$sentence' (模式: $activeMode)")) }
                        break
                    }
                }
                modeWatcherJob.cancel()
                if (isActive) { sendCommand(Command.InternalSynthesisFinished) }
            } catch (e: CancellationException) {
            } catch (t: Throwable) {
                Log.e(TAG, "合成协程发生严重错误", t)
                if (isActive) { sendCommand(Command.InternalError("合成协程发生未知错误: ${t.message}")) }
            }
        }
    }

    private suspend fun performOnlineSynthesis(index: Int, sentence: String): Boolean {
        try {
            Log.d(TAG, "正在合成[在线]句子 $index: \"$sentence\"")
            val mp3Data = onlineApi.fetchTtsAudio(sentence, currentVoice)
            if (!coroutineContext.isActive) return false
            if (mp3Data.isEmpty()) { Log.e(TAG, "在线API返回了空的音频数据: \"$sentence\""); return false }

            val decodedResult = mp3Decoder.decode(mp3Data)
            val pcmData = decodedResult.pcmData
            val sampleRate = decodedResult.sampleRate
            if (!coroutineContext.isActive) return false

            if (pcmData.isEmpty()) {
                Log.w(TAG, "MP3解码后得到空的PCM数据: \"$sentence\"")
                audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) }
                audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceEnd(index, sentence)) }
                return true
            }

            audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) }
            audioPlayer.enqueuePcm(pcm = pcmData, sampleRate = sampleRate, source = SynthesisMode.ONLINE)
            audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceEnd(index, sentence)) }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "在线合成网络或IO错误 (句子 $index): ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "在线合成或解码时发生意外错误 (句子 $index)", e)
            if (coroutineContext.isActive) sendCommand(Command.InternalError("在线合成或解码失败: ${e.message}"))
            return false
        }
    }

    private suspend fun performOfflineSynthesis(index: Int, sentence: String): Boolean {
        return engineMutex.withLock {
            try {
                Log.d(TAG, "正在合成[离线]句子 $index: \"$sentence\"")
                val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
                if (prepareResult != 0) {
                    Log.e(TAG, "离线引擎准备失败 (代码: $prepareResult)，句子: $sentence")
                    if (coroutineContext.isActive) sendCommand(Command.InternalError("准备句子失败$prepareResult: $sentence"))
                    return@withLock false
                }
                val synthResult = IntArray(1)
                val pcmArray = pcmBuffer.array()
                var hasEnqueuedStartMarker = false
                while (coroutineContext.isActive) {
                    val synthesisStatus = nativeEngine?.synthesize(pcmArray, TtsConstants.PCM_BUFFER_SIZE, synthResult, 1) ?: -1
                    if (synthesisStatus == -1) {
                        Log.e(TAG, "本地合成失败，状态码: -1"); nativeEngine?.reset(); return@withLock false
                    }
                    val numSamples = synthResult[0]
                    if (numSamples <= 0) break
                    if (!hasEnqueuedStartMarker) {
                        audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) }
                        hasEnqueuedStartMarker = true
                    }
                    val validSamples = numSamples.coerceAtMost(pcmArray.size)
                    if (validSamples > 0) {
                        val validPcm = pcmArray.copyOf(validSamples)
                        audioPlayer.enqueuePcm(pcm = validPcm, sampleRate = TtsConstants.DEFAULT_SAMPLE_RATE, source = SynthesisMode.OFFLINE)
                    }
                    delay(1)
                }
                if (coroutineContext.isActive) {
                    if (!hasEnqueuedStartMarker) { audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceStart(index, sentence)) } }
                    audioPlayer.enqueueMarker { sendCommand(Command.InternalSentenceEnd(index, sentence)) }
                }
                true
            } catch (e: CancellationException) {
                false
            } catch (e: Exception) {
                Log.e(TAG, "离线合成时发生异常", e)
                if (coroutineContext.isActive) sendCommand(Command.InternalError("离线合成错误: ${e.message}"))
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
            _isPlaying.value = newState == TtsPlaybackState.PLAYING
        }
    }
}