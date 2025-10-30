package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.os.Process
import android.util.Log
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
 * - 配置 AudioPlayer 的预缓冲与自动回填策略
 * - 合成协程提升优先级，尽量跟上播放
 * - 播放进度以播放协程为准（通过播放队列 Marker 触发 onSentenceStart/onSentenceComplete）
 */
class TtsSynthesizer(
    context: Context,
    private val voiceName: String
) {

    private val voiceCode: String = voiceName
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)

    @Volatile
    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE

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
        private const val TAG = "TtsSynthesizer"
        private val instanceCount = AtomicInteger(0)
        @Volatile private var nativeEngine: SynthesizerNative? = null
        @Volatile private var currentVoiceCode: String? = null

        init {
            try {
                System.loadLibrary("hwTTS")
                System.loadLibrary("weread-tts")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "加载原生库失败", e)
            }
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
            try {
                if (instanceCount.incrementAndGet() == 1) {
                    nativeEngine = SynthesizerNative()
                    nativeEngine?.init(voiceDataPath.toByteArray())
                    Log.d(TAG, "原生TTS引擎初始化完成，路径: $voiceDataPath")
                }
                currentState = TtsPlaybackState.IDLE
                currentCallback?.onInitialized(true)
            } catch (e: Exception) {
                Log.e(TAG, "TTS引擎初始化失败", e)
                currentCallback?.onInitialized(false)
                currentCallback?.onError("初始化失败: ${e.message}")
            }
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
                    restartCurrentSentence()
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
                    restartCurrentSentence()
                }
            }
        }
    }

    private fun restartCurrentSentence() {
        stateLock.withLock {
            stopSynthesisJobBlocking()
            audioPlayer.stopAndRelease()
            synthesisSentenceIndex = playbackSentenceIndex
            playingSentenceIndex = playbackSentenceIndex

            audioPlayer.configureBuffering(
                prerollMs = 300,
                lowWatermarkMs = 120,
                highWatermarkMs = 350,
                autoRebuffer = true
            )
            audioPlayer.startIfNeeded(volume = currentVolume)
            startSynthesisCoroutine()
        }
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
            // 提升当前工作线程优先级（尽力而为，协程可能迁移线程）
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }

            try {
                while (isActive && synthesisSentenceIndex < sentences.size) {
                    val index = synthesisSentenceIndex
                    val sentence = sentences[index]

                    // 合成并入队：在第一个 PCM 块之前先入队“句首 Marker”，以播放协程为准触发开始回调
                    val ok = synthesizeSentenceAndEnqueue(index, sentence)
                    if (!ok || !isActive) break

                    // 句末标记：真正播放完毕后推进索引与完成回调
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
            } catch (ce: CancellationException) {
                // 正常取消
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
     * 注意：第一个 PCM 块入队前会先入队“句首 Marker”，确保 onSentenceStart 在播放协程触发。
     */
    private suspend fun synthesizeSentenceAndEnqueue(index: Int, sentence: String): Boolean {
        return engineMutex.withLock {
            try {
                val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
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

                    // 让出调度，避免长循环饿死其他协程
                    //if (!isActive) break
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
            } catch (ce: CancellationException) {
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