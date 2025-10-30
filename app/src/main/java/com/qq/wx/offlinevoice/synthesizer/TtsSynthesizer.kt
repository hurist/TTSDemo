package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.os.Process
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 文本转语音合成器（播放协程 + 队列）
 * - 播放中修改 speed/voice：软重启，立刻丢弃旧音频并从当前句句首用新配置继续
 * - 非播放（IDLE/PAUSED）仅更新配置
 * - 进度以播放协程为准（句首/句末 Marker）
 */
class TtsSynthesizer(
    context: Context,
    private val voiceName: String
) {

    private val voiceCode: String = voiceName
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)

    @Volatile private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE

    private val sentences = mutableListOf<String>()
    @Volatile private var playbackSentenceIndex: Int = 0
    @Volatile private var playingSentenceIndex: Int = 0
    @Volatile private var synthesisSentenceIndex: Int = 0

    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentVoice: String = voiceName
    private var currentCallback: TtsCallback? = null

    private val stateLock = ReentrantLock()
    private val engineMutex = Mutex()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var synthesisJob: Job? = null

    private val audioPlayer: AudioPlayer = AudioPlayer(TtsConstants.DEFAULT_SAMPLE_RATE)

    companion object {
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
    }

    fun initialize() {
        stateLock.withLock {
            if (instanceCount.incrementAndGet() == 1) {
                nativeEngine = SynthesizerNative()
                nativeEngine?.init(voiceDataPath.toByteArray())
            }
            currentState = TtsPlaybackState.IDLE
            currentCallback?.onInitialized(true)
        }
    }

    fun setCallback(callback: TtsCallback?) {
        currentCallback = callback
    }

    fun setSpeed(speed: Float) {
        val newSpeed = speed.coerceIn(0.5f, 3.0f)
        stateLock.withLock {
            if (currentSpeed != newSpeed) {
                currentSpeed = newSpeed
                if (currentState == TtsPlaybackState.PLAYING) {
                    softRestartFromCurrentSentence()
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        val newVolume = volume.coerceIn(0.0f, 1.0f)
        stateLock.withLock {
            if (currentVolume != newVolume) {
                currentVolume = newVolume
                audioPlayer.setVolume(newVolume)
            }
        }
    }

    fun setVoice(voiceName: String) {
        stateLock.withLock {
            if (currentVoice != voiceName) {
                currentVoice = voiceName
                if (currentState == TtsPlaybackState.PLAYING) {
                    softRestartFromCurrentSentence()
                }
            }
        }
    }

    /**
     * 软重启：立刻丢弃旧音频并从“正在播放的句子”的句首用新配置继续
     */
    private fun softRestartFromCurrentSentence() {
        val fromIndex = stateLock.withLock { playingSentenceIndex.coerceIn(0, sentences.size) }

        // 1) 取消当前合成协程
        stopSynthesisJobBlocking()

        // 2) 软清空播放器（优先处理、立刻 flush 内部缓冲）
        scope.launch(Dispatchers.Default) {
            audioPlayer.softReset(prerollMs = 220)
        }

        // 3) 重置合成游标
        stateLock.withLock {
            synthesisSentenceIndex = fromIndex
            // playingSentenceIndex 会在新句子“句首 Marker”里重新设置为 fromIndex
        }

        // 4) 确保播放器活跃
        audioPlayer.startIfNeeded(volume = stateLock.withLock { currentVolume })

        // 5) 继续合成并入队（不改变外部状态，不重复 onSynthesisStart）
        startSynthesisCoroutine()
    }

    fun speak(text: String) {
        stateLock.withLock {
            if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
                stopInternal()
            }

            sentences.clear()
            sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
            if (sentences.isEmpty()) {
                currentCallback?.onError("文本中没有有效的句子")
                return
            }

            playbackSentenceIndex = 0
            playingSentenceIndex = 0
            synthesisSentenceIndex = 0

            audioPlayer.configureBuffering(
                prerollMs = 300,
                lowWatermarkMs = 120,
                highWatermarkMs = 350,
                autoRebuffer = true
            )
            audioPlayer.startIfNeeded(volume = currentVolume)

            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onSynthesisStart()

            startSynthesisCoroutine()
        }
    }

    fun pause() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PLAYING) return
            audioPlayer.pause()
            updateState(TtsPlaybackState.PAUSED)
            currentCallback?.onPaused()
        }
    }

    fun resume() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PAUSED) return
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onResumed()
            audioPlayer.resume()
        }
    }

    fun stop() {
        stateLock.withLock { stopInternal() }
    }

    private fun stopInternal() {
        if (currentState == TtsPlaybackState.IDLE) return
        stopSynthesisJobBlocking()
        audioPlayer.stopAndRelease()
        sentences.clear()
        playbackSentenceIndex = 0
        playingSentenceIndex = 0
        synthesisSentenceIndex = 0
        updateState(TtsPlaybackState.IDLE)
    }

    private fun startSynthesisCoroutine() {
        synthesisJob = scope.launch(Dispatchers.Default) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
            try {
                while (isActive && synthesisSentenceIndex < sentences.size) {
                    val index = synthesisSentenceIndex
                    val sentence = sentences[index]

                    val ok = synthesizeSentenceAndEnqueue(index, sentence)
                    if (!ok || !isActive) break

                    audioPlayer.enqueueMarker {
                        stateLock.withLock {
                            playbackSentenceIndex = index + 1
                            currentCallback?.onSentenceComplete(index, sentence)
                            if (playbackSentenceIndex >= sentences.size) {
                                updateState(TtsPlaybackState.IDLE)
                                currentCallback?.onSynthesisComplete()
                            }
                        }
                    }

                    synthesisSentenceIndex++
                }
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                currentCallback?.onError("Speech execution error: ${t.message}")
                stateLock.withLock { updateState(TtsPlaybackState.IDLE) }
            }
        }
    }

    private fun stopSynthesisJobBlocking() {
        synthesisJob?.cancel()
        synthesisJob = null
    }

    /**
     * 合成一个句子并将 PCM 按块入队。
     * 第一个块之前入队“句首 Marker”，确保 onSentenceStart 在播放协程触发。
     */
    private suspend fun synthesizeSentenceAndEnqueue(index: Int, sentence: String): Boolean {
        return engineMutex.withLock {
            try {
                val speed = stateLock.withLock { currentSpeed }
                val volume = stateLock.withLock { currentVolume }

                val prepareResult = prepareForSynthesis(sentence, speed, volume)
                if (prepareResult != 0) {
                    currentCallback?.onError("准备句子失败: $sentence")
                    return@withLock false
                }

                val synthResult = IntArray(1)
                val pcmArray = pcmBuffer.array()
                var started = false

                while (synthesisJob?.isActive == true) {
                    val synthesisStatus = nativeEngine?.synthesize(
                        pcmArray,
                        TtsConstants.PCM_BUFFER_SIZE,
                        synthResult,
                        1
                    ) ?: -1

                    if (synthesisStatus == -1) {
                        nativeEngine?.reset()
                        return@withLock false
                    }

                    val numSamples = synthResult[0]
                    if (numSamples <= 0) break

                    val validSamples = minOf(pcmArray.size, numSamples)
                    if (validSamples <= 0) break

                    if (!started) {
                        audioPlayer.enqueueMarker {
                            stateLock.withLock {
                                playingSentenceIndex = index
                                currentCallback?.onSentenceStart(index, sentence, sentences.size)
                            }
                        }
                        started = true
                    }

                    val validPcm = pcmArray.copyOf(validSamples)
                    if (validPcm.isNotEmpty()) {
                        audioPlayer.enqueuePcm(validPcm)
                    }

                    // 让出调度
                    delay(0)
                }

                if (!started) {
                    audioPlayer.enqueueMarker {
                        stateLock.withLock {
                            playingSentenceIndex = index
                            currentCallback?.onSentenceStart(index, sentence, sentences.size)
                        }
                    }
                }

                true
            } catch (_: CancellationException) {
                false
            } catch (e: Exception) {
                currentCallback?.onError("合成错误: ${e.message}")
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

    fun getStatus(): TtsStatus {
        stateLock.withLock {
            val i = playingSentenceIndex.coerceAtMost(sentences.size - 1).coerceAtLeast(0)
            val currentSentence = if (sentences.isNotEmpty() && i in sentences.indices) sentences[i] else ""
            return TtsStatus(
                state = currentState,
                totalSentences = sentences.size,
                currentSentenceIndex = playingSentenceIndex,
                currentSentence = currentSentence
            )
        }
    }

    fun isSpeaking(): Boolean = currentState == TtsPlaybackState.PLAYING

    fun release() {
        stateLock.withLock {
            stopInternal()
            if (instanceCount.decrementAndGet() == 0) {
                nativeEngine?.destroy()
                nativeEngine = null
                currentVoiceCode = null
            }
        }
    }
}