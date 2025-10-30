package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.os.Process
import android.util.Log
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 文本转语音合成器（播放线程 + 队列）
 * - 新增：配置 AudioPlayer 的预缓冲与自动回填策略
 * - 合成线程提升优先级，尽量跟上播放
 * - 修复：播放进度以播放线程为准（通过播放队列 Marker 触发 onSentenceStart/onSentenceComplete）
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
    // 已播放完成到第几个句子（在“句末 Marker”里推进）
    @Volatile private var playbackSentenceIndex: Int = 0
    // 正在播放的句子索引（在“句首 Marker”里设置）
    @Volatile private var playingSentenceIndex: Int = 0
    // 合成推进到第几个句子
    @Volatile private var synthesisSentenceIndex: Int = 0

    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentVoice: String = voiceName
    private var currentCallback: TtsCallback? = null

    private var synthesisThread: Thread? = null
    private val stateLock = ReentrantLock()
    private val synthesisLock = ReentrantLock()

    @Volatile
    private var shouldStop = false

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
                Log.d(TAG, "TTS引擎初始化成功")
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
                Log.d(TAG, "语速设置为: ${newSpeed}x")
                if (currentState == TtsPlaybackState.PLAYING) {
                    Log.d(TAG, "播放中修改语速，将从当前句重新开始")
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
                Log.d(TAG, "音量设置为: $newVolume")
                audioPlayer.setVolume(newVolume)
            }
        }
    }

    fun setVoice(voiceName: String) {
        stateLock.withLock {
            if (currentVoice != voiceName) {
                currentVoice = voiceName
                Log.d(TAG, "发音人设置为: $voiceName")
                if (currentState == TtsPlaybackState.PLAYING) {
                    Log.d(TAG, "播放中修改发音人，将从当前句重新开始")
                    restartCurrentSentence()
                }
            }
        }
    }

    private fun restartCurrentSentence() {
        stateLock.withLock {
            stopSynthesisThread()
            audioPlayer.stopAndRelease()
            // 继续从已播放完成的位置（而非正在合成的位置）
            synthesisSentenceIndex = playbackSentenceIndex
            playingSentenceIndex = playbackSentenceIndex
            shouldStop = false
            // 重新配置缓冲策略（可按需要调整阈值）
            audioPlayer.configureBuffering(
                prerollMs = 300,
                lowWatermarkMs = 120,
                highWatermarkMs = 350,
                autoRebuffer = true
            )
            audioPlayer.startIfNeeded(volume = currentVolume)
            startSynthesisThread()
        }
    }

    fun speak(text: String) {
        stateLock.withLock {
            if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
                Log.d(TAG, "停止当前播放，准备播放新内容")
                stopInternal()
            }

            sentences.clear()
            sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
            if (sentences.isEmpty()) {
                Log.w(TAG, "没有可播放的句子")
                currentCallback?.onError("文本中没有有效的句子")
                return
            }

            Log.d(TAG, "文本分为 ${sentences.size} 句")
            playbackSentenceIndex = 0
            playingSentenceIndex = 0
            synthesisSentenceIndex = 0
            shouldStop = false

            // 针对低端机：更激进的预缓冲与自动回填
            audioPlayer.configureBuffering(
                prerollMs = 300,       // 启动前至少缓冲 300ms
                lowWatermarkMs = 120,  // 低水位 120ms 进入回填
                highWatermarkMs = 350, // 回填到 350ms 再继续
                autoRebuffer = true
            )
            audioPlayer.startIfNeeded(volume = currentVolume)
            startSynthesisThread()
        }
    }

    fun pause() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PLAYING) {
                Log.w(TAG, "无法暂停: 当前未在播放")
                return
            }
            audioPlayer.pause()
            updateState(TtsPlaybackState.PAUSED)
            currentCallback?.onPaused()
            Log.d(TAG, "播放已暂停，已播放完成句子索引: $playbackSentenceIndex")
        }
    }

    fun resume() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PAUSED) {
                Log.w(TAG, "无法恢复: 当前未暂停")
                return
            }
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onResumed()
            Log.d(TAG, "播放已恢复，从句子索引 $playbackSentenceIndex 继续")
            audioPlayer.resume()
        }
    }

    fun stop() {
        stateLock.withLock { stopInternal() }
    }

    private fun stopInternal() {
        if (currentState == TtsPlaybackState.IDLE) return
        Log.d(TAG, "停止播放")
        shouldStop = true
        stopSynthesisThread()

        if (voiceCode == currentVoiceCode) {
            nativeEngine?.reset()
        }

        audioPlayer.stopAndRelease()

        sentences.clear()
        playbackSentenceIndex = 0
        playingSentenceIndex = 0
        synthesisSentenceIndex = 0
        updateState(TtsPlaybackState.IDLE)
    }

    private fun stopSynthesisThread() {
        synthesisThread?.let { t ->
            if (t.isAlive && Thread.currentThread() != t) {
                try {
                    t.interrupt()
                    t.join(2000)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "等待合成线程时被中断")
                }
            }
        }
        synthesisThread = null
    }

    fun getStatus(): TtsStatus {
        stateLock.withLock {
            val i = playingSentenceIndex.coerceAtMost(sentences.size - 1).coerceAtLeast(0)
            val currentSentence = if (sentences.isNotEmpty() && i in sentences.indices) sentences[i] else ""
            return TtsStatus(
                state = currentState,
                totalSentences = sentences.size,
                // 返回“正在播放”的句子索引，而非“已完成”的索引
                currentSentenceIndex = playingSentenceIndex,
                currentSentence = currentSentence
            )
        }
    }

    fun isSpeaking(): Boolean = currentState == TtsPlaybackState.PLAYING

    private fun startSynthesisThread() {
        updateState(TtsPlaybackState.PLAYING)
        currentCallback?.onSynthesisStart()

        synthesisThread = Thread({
            // 提升合成线程优先级，尽量跟上播放
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            try {
                while (!shouldStop && synthesisSentenceIndex < sentences.size) {
                    val index = synthesisSentenceIndex
                    val sentence = sentences[index]

                    Log.d(TAG, "开始合成句子 $index: $sentence")

                    // 合成并入队：在第一个 PCM 块之前先入队“句首 Marker”，以播放线程为准触发开始回调
                    val ok = synthesizeSentenceAndEnqueue(index, sentence)
                    if (!ok || shouldStop) break

                    // 句末标记：真正播放完毕后推进索引与完成回调
                    audioPlayer.enqueueMarker {
                        stateLock.withLock {
                            playbackSentenceIndex = index + 1
                            currentCallback?.onSentenceComplete(index, sentence)
                            if (playbackSentenceIndex >= sentences.size && !shouldStop) {
                                Log.d(TAG, "所有句子播放完成")
                                updateState(TtsPlaybackState.IDLE)
                                currentCallback?.onSynthesisComplete()
                            }
                        }
                    }

                    synthesisSentenceIndex++
                }
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                Log.e(TAG, "Error during synthesis", t)
                currentCallback?.onError("Speech execution error: ${t.message}")
                stateLock.withLock { updateState(TtsPlaybackState.IDLE) }
            }
        }, "TtsSynthesisQueueThread")

        synthesisThread?.start()
    }

    /**
     * 合成一个句子并将 PCM 按块入队。
     * 注意：第一个 PCM 块入队前会先入队“句首 Marker”，确保 onSentenceStart 在播放线程触发。
     */
    private fun synthesizeSentenceAndEnqueue(index: Int, sentence: String): Boolean {
        return synthesisLock.withLock {
            try {
                val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
                if (prepareResult != 0) {
                    Log.e(TAG, "准备合成失败，错误码: $prepareResult")
                    currentCallback?.onError("准备句子失败: $sentence")
                    return false
                }

                val synthResult = IntArray(1)
                val pcmArray = pcmBuffer.array()

                var started = false

                while (!shouldStop) {
                    val synthesisStatus = nativeEngine?.synthesize(
                        pcmArray,
                        TtsConstants.PCM_BUFFER_SIZE,
                        synthResult,
                        1
                    ) ?: -1

                    if (synthesisStatus == -1) {
                        Log.e(TAG, "合成失败")
                        nativeEngine?.reset()
                        return false
                    }

                    val numSamples = synthResult[0]
                    if (numSamples <= 0) break

                    val validSamples = minOf(pcmArray.size, numSamples)
                    if (validSamples <= 0) {
                        Log.w(TAG, "无效的PCM样本数: $validSamples")
                        break
                    }

                    // 在第一个块真正入队前，先插入“句首 Marker”，确保进度以播放为准
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
                }

                // 特殊情形：若该句没有产出任何 PCM（极端边界），仍需触发“句首 Marker”
                if (!started) {
                    audioPlayer.enqueueMarker {
                        stateLock.withLock {
                            playingSentenceIndex = index
                            currentCallback?.onSentenceStart(index, sentence, sentences.size)
                        }
                    }
                }

                if (shouldStop) return false
                Log.v(TAG, "句子合成完成并已入队（index=$index）")
                return true
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } catch (e: Exception) {
                Log.e(TAG, "合成句子时出错", e)
                currentCallback?.onError("合成错误: ${e.message}")
                return false
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
            Log.d(TAG, "状态变更为: $newState")
        }
    }

    fun release() {
        stateLock.withLock {
            Log.d(TAG, "释放TTS引擎")
            stopInternal()

            if (instanceCount.decrementAndGet() == 0) {
                nativeEngine?.destroy()
                nativeEngine = null
                currentVoiceCode = null
            }

            audioPlayer.stopAndRelease()
            currentState = TtsPlaybackState.IDLE
        }
    }
}