package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.cache.TtsCacheImpl
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * TTS 合成器主类。
 *
 * 负责管理整个文本到语音的转换过程，包括：
 * - 接收外部控制命令 (speak, stop, pause, setSpeed 等)。
 * - 维护播放状态和句子队列。
 * - 根据设定的策略 (TtsStrategy) 调度在线或离线合成任务。
 * - **优化：实现了动态回退与重试机制。当在线合成临时失败时，会自动回退到离线模式，并在一个指数退避的冷却期后，智能地尝试恢复在线合成，以应对网络抖动或服务临时故障。**
 * - 与 AudioPlayer 协作，将合成的音频数据流式传输以供播放。
 * - 通过 TtsCallback 向外部通知状态变化、进度和错误。
 *
 * 该类采用基于 Kotlin 协程的 Actor 模型，通过一个 commandChannel 来处理所有外部请求，确保了线程安全和状态的一致性。
 */
class TtsSynthesizer(
    context: Context,
    private val speaker: Speaker
) {
    private sealed class SynthesisResult {
        object Success : SynthesisResult()
        data class Failure(val reason: String) : SynthesisResult()
    }

    private sealed class Command {
        data class Speak(val text: String) : Command()
        data class SetSpeed(val speed: Float) : Command()
        data class SetVolume(val volume: Float) : Command()
        data class SetVoice(val speaker: Speaker) : Command()
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
    private var currentSpeaker = speaker
    private var currentCallback: TtsCallback? = null
    private val strategyManager: SynthesisStrategyManager
    private val ttsRepository: TtsRepository
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private var synthesisJob: Job? = null
    private val audioPlayer: AudioPlayer = AudioPlayer(initialSampleRate = TtsConstants.DEFAULT_SAMPLE_RATE)
    private val engineMutex = Mutex()
    private var isPausedByError = false
    private var onlineAudioProcessor: AudioSpeedProcessor? = null
    private val processorMutex = Mutex()
    private val splitterStrategy = SentenceSplitterStrategy.NEWLINE

    // --- 新增：在线模式回退与重试机制的状态变量 ---
    private var onlineFailureCount: Int = 0
    private var onlineCooldownUntilTimestamp: Long = 0L

    companion object {
        private const val TAG = "TtsSynthesizer"
        private val instanceCount = AtomicInteger(0)
        @Volatile private var nativeEngine: SynthesizerNative? = null
        @Volatile private var currentVoiceCode: String? = null

        // --- 新增：指数退避算法的常量 ---
        private const val BASE_COOLDOWN_MS = 3000L // 基础冷却时间 3 秒
        private const val MAX_COOLDOWN_MS = 60000L // 最大冷却时间 1 分钟

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
        val onlineApi = WxReaderApi // 已假定 WxReaderApi 被重构为不依赖 context
        val mp3Decoder = MediaCodecMp3Decoder(context.applicationContext)
        val ttsCache = TtsCacheImpl(context.applicationContext)
        ttsRepository = TtsRepository(onlineApi, mp3Decoder, ttsCache)
        scope.launch { commandProcessor() }
        if (instanceCount.incrementAndGet() == 1) {
            nativeEngine = SynthesizerNative()
            nativeEngine?.init(voiceDataPath.toByteArray())
        }
        sendCommand(Command.SetCallback(null))
    }

    // --- 公共 API ---
    fun setCallback(callback: TtsCallback?) = sendCommand(Command.SetCallback(callback))
    fun setSpeed(speed: Float) = sendCommand(Command.SetSpeed(speed))
    fun setVolume(volume: Float) = sendCommand(Command.SetVolume(volume))
    fun setVoice(speaker: Speaker) = sendCommand(Command.SetVoice(speaker))
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

    // --- 命令处理器 ---
    private suspend fun commandProcessor() {
        for (command in commandChannel) {
            when (command) {
                is Command.Speak -> handleSpeak(command.text)
                is Command.SetSpeed -> handleSetSpeed(command.speed)
                is Command.SetVolume -> handleSetVolume(command.volume)
                is Command.SetVoice -> handleSetSpeaker(command.speaker)
                is Command.Pause -> handlePause()
                is Command.Resume -> handleResume()
                is Command.Stop -> handleStop()
                is Command.Release -> { handleRelease(); break }
                is Command.SetStrategy -> strategyManager.setStrategy(command.strategy)
                is Command.SetCallback -> { currentCallback = command.callback; currentCallback?.onInitialized(true) }
                is Command.InternalSentenceStart -> { playingSentenceIndex = command.index; currentCallback?.onSentenceStart(command.index, command.sentence, sentences.size) }
                is Command.InternalSentenceEnd -> {
                    currentCallback?.onSentenceComplete(command.index, command.sentence)
                    if (command.index == sentences.size - 1 && !isPausedByError) {
                        updateState(TtsPlaybackState.IDLE)
                        currentCallback?.onSynthesisComplete()
                    }
                }
                is Command.InternalSynthesisFinished -> { /* 无操作 */ }
                is Command.InternalError -> {
                    Log.e(TAG, "收到内部错误，将执行 handleStop: ${command.message}")
                    handleStop();
                    currentCallback?.onError(command.message)
                }
            }
        }
    }

    // --- 命令处理实现 ---
    private suspend fun handleSpeak(text: String) {
        if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
            Log.d(TAG, "已有语音在播放中，将先停止当前任务再开始新的任务。")
            handleStop()
        }
        isPausedByError = false
        sentences.clear()

        val result = when(splitterStrategy) {
            SentenceSplitterStrategy.NEWLINE -> SentenceSplitter.sentenceSplitListByLine(text)
            SentenceSplitterStrategy.PUNCTUATION -> SentenceSplitter.sentenceSplitList(text)
        }
        sentences.addAll(result)
        if (sentences.isEmpty()) {
            Log.w(TAG, "提供的文本中未找到有效句子。")
            currentCallback?.onError("文本中没有有效的句子")
            return
        }
        playingSentenceIndex = 0
        synthesisSentenceIndex = 0

        processorMutex.withLock {
            onlineAudioProcessor?.release()
            onlineAudioProcessor = null
        }

        audioPlayer.startIfNeeded(volume = currentVolume)
        updateState(TtsPlaybackState.PLAYING)
        currentCallback?.onSynthesisStart()
        startSynthesis()
    }

    private suspend fun handleSetSpeed(speed: Float) {
        val newSpeed = speed.coerceIn(0.5f, 3.0f)
        if (currentSpeed == newSpeed) return
        currentSpeed = newSpeed
        processorMutex.withLock {
            onlineAudioProcessor?.setSpeed(newSpeed)
        }
        if (currentState == TtsPlaybackState.PLAYING) {
            softRestart()
        }
    }
    private suspend fun handleSetSpeaker(speaker: Speaker) { if (speaker == currentSpeaker) return; currentSpeaker = speaker; if (currentState == TtsPlaybackState.PLAYING) { softRestart() } }
    private fun handleSetVolume(volume: Float) { val newVolume = volume.coerceIn(0.0f, 1.0f); if (currentVolume == newVolume) return; currentVolume = newVolume; audioPlayer.setVolume(newVolume) }

    private fun handlePause() {
        if (currentState != TtsPlaybackState.PLAYING && currentState != TtsPlaybackState.PAUSED) {
            Log.w(TAG, "无法暂停，当前状态为 $currentState")
            return
        }
        if (currentState == TtsPlaybackState.PAUSED) {
            Log.d(TAG, "已经是暂停状态，无需再次操作。")
            return
        }
        if (isPausedByError) {
            Log.w(TAG, "因合成失败自动暂停。")
        } else {
            isPausedByError = false
        }
        audioPlayer.pause()
        updateState(TtsPlaybackState.PAUSED)
        currentCallback?.onPaused()
    }

    private suspend fun handleResume() {
        if (currentState != TtsPlaybackState.PAUSED) { Log.w(TAG, "无法恢复，当前状态为 $currentState, 而非 PAUSED。"); return }
        updateState(TtsPlaybackState.PLAYING)
        audioPlayer.resume()
        currentCallback?.onResumed()
        if (isPausedByError) {
            Log.i(TAG, "从错误暂停状态中恢复，正在重试合成...")
            isPausedByError = false
            synthesisJob?.cancelAndJoin()
            startSynthesis()
        }
    }

    private suspend fun handleStop() {
        if (currentState == TtsPlaybackState.IDLE) return
        Log.d(TAG, "开始执行 handleStop...")
        isPausedByError = false
        val jobToJoin = synthesisJob
        jobToJoin?.cancel()
        synthesisJob = null
        Log.d(TAG, "已向合成任务发送取消信号。")
        audioPlayer.resetBlocking()
        Log.d(TAG, "播放器已同步重置，音频应已立即停止。")
        jobToJoin?.join()
        Log.d(TAG, "已确认合成任务完全终止。")
        processorMutex.withLock {
            onlineAudioProcessor?.release()
            onlineAudioProcessor = null
        }
        sentences.clear()
        updateState(TtsPlaybackState.IDLE)
        Log.d(TAG, "handleStop 执行完毕。")
    }

    private suspend fun handleRelease() { handleStop(); commandChannel.close(); strategyManager.release(); if (instanceCount.decrementAndGet() == 0) { nativeEngine?.destroy(); nativeEngine = null; currentVoiceCode = null } }
    private suspend fun softRestart() { Log.d(TAG, "软重启请求，句子索引: $playingSentenceIndex"); val restartIndex = playingSentenceIndex; synthesisJob?.cancelAndJoin(); synthesisJob = null; Log.d(TAG, "旧的合成任务已取消并等待结束。"); audioPlayer.resetBlocking(); Log.d(TAG, "播放器已同步重置。"); synthesisSentenceIndex = restartIndex; startSynthesis(); Log.d(TAG, "新的合成任务已从索引 $restartIndex 处启动。") }

    // --- 合成逻辑 ---
    /**
     * 启动合成任务。
     * 该方法会启动两个并行的子任务：
     * 1. 核心合成循环 (`launchSynthesisLoop`)：负责实际的音频数据生产。
     * 2. 网络状态哨兵 (`modeWatcherJob`)：在 `ONLINE_PREFERRED` 策略下，监控网络状态。
     *    当网络从断开恢复时，它会主动中断当前（可能正在离线合成）的循环，并从当前播放位置
     *    重新开始，以将后续的语音“升级”为高质量的在线版本。
     */
    private fun startSynthesis() {
        // 每次开始新的合成任务时，重置在线失败状态
        resetOnlineCooldown()
        synthesisJob = scope.launch(Dispatchers.Default) {
            var synthesisLoopJob: Job? = null

            // 定义一个可重复使用的启动函数
            fun runSynthesisLoop() {
                synthesisLoopJob?.cancel()
                synthesisLoopJob = launchSynthesisLoop()
            }

            // 哨兵任务：仅在 ONLINE_PREFERRED 策略下运行
            if (strategyManager.currentStrategy == TtsStrategy.ONLINE_PREFERRED) {
                launch {
                    var wasNetworkBad = !strategyManager.isNetworkGood.value
                    strategyManager.isNetworkGood.collect { isNetworkGood ->
                        // 关键触发条件：网络从“坏”变“好”
                        if (wasNetworkBad && isNetworkGood) {
                            Log.i(TAG, "网络已恢复。执行软重启以升级到在线语音...")
                            // 停止当前的合成循环，确保它不再向队列中添加低质量音频
                            synthesisLoopJob?.cancelAndJoin()
                            // 清理播放队列中尚未播放的低质量音频，但保留正在播放的句子
                            audioPlayer.resetQueueOnlyBlocking(preserveSentenceIndex = playingSentenceIndex)
                            // 从下一句开始重新合成
                            synthesisSentenceIndex = playingSentenceIndex + 1
                            if (synthesisSentenceIndex < sentences.size) {
                                Log.i(TAG, "将从句子 $synthesisSentenceIndex 处重新开始在线合成。")
                                runSynthesisLoop()
                            } else {
                                Log.i(TAG, "所有句子均已播放或正在播放，无需重启合成。")
                            }
                        }
                        wasNetworkBad = !isNetworkGood
                    }
                }
            }

            // 首次启动合成循环
            runSynthesisLoop()
        }
    }

    /**
     * 核心合成循环。
     * 该实现包含了最终的“缓存优先”决策逻辑，即使在断网状态下也会尝试使用缓存。
     */
    private fun CoroutineScope.launchSynthesisLoop() = launch {
        var synthesisFailed = false
        try {
            while (coroutineContext.isActive && synthesisSentenceIndex < sentences.size) {
                val index = synthesisSentenceIndex
                val sentence = sentences[index]
                val sessionStrategy = strategyManager.currentStrategy

                var finalResult: SynthesisResult

                // --- 核心逻辑重构：策略驱动，缓存优先 ---
                if (sessionStrategy == TtsStrategy.ONLINE_PREFERRED || sessionStrategy == TtsStrategy.ONLINE_ONLY) {
                    // 1. 对于在线优先或纯在线策略，总是先尝试“在线路径”。
                    //    “在线路径”意味着：优先从缓存获取高质量PCM，缓存未命中再尝试网络。
                    val onlineResult = performOnlineSynthesis(index, sentence)

                    if (onlineResult is SynthesisResult.Success) {
                        // 缓存命中或网络请求成功！
                        finalResult = onlineResult
                        // 成功通过在线路径获取数据，重置冷却计时器
                        resetOnlineCooldown()
                    } else {
                        // 在线路径失败（缓存未命中且网络/API出错）
                        // 激活冷却，避免短时间内频繁尝试失败的网络请求
                        activateOnlineCooldown()

                        if (sessionStrategy == TtsStrategy.ONLINE_PREFERRED) {
                            // 2a. 如果是“在线优先”策略，则回退到离线模式
                            Log.w(TAG, "在线路径失败(缓存未命中或API错误)，回退至[离线模式]。原因: ${(onlineResult as SynthesisResult.Failure).reason}")
                            finalResult = performOfflineSynthesis(index, sentence)
                        } else {
                            // 2b. 如果是“纯在线”策略，则本次合成彻底失败
                            Log.e(TAG, "纯在线模式合成失败，无可用回退。原因: ${(onlineResult as SynthesisResult.Failure).reason}")
                            finalResult = onlineResult
                        }
                    }
                } else {
                    // 3. 对于“纯离线”策略，直接使用离线合成
                    finalResult = performOfflineSynthesis(index, sentence)
                }

                // --- 处理最终结果 ---
                when (finalResult) {
                    is SynthesisResult.Success -> {
                        if (synthesisSentenceIndex == index) {
                            synthesisSentenceIndex++
                        }
                    }
                    is SynthesisResult.Failure -> {
                        // 如果执行到这里，意味着所有尝试（包括可能的回退）都失败了
                        Log.e(TAG, "句子 $index 合成最终失败 (策略: $sessionStrategy): ${finalResult.reason}")
                        synthesisFailed = true
                        break // 终止整个合成任务
                    }
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "合成循环被取消。")
        } finally {
            var finalPcm: ShortArray? = null
            var finalSampleRate: Int? = null
            processorMutex.withLock {
                if (onlineAudioProcessor != null) {
                    finalPcm = onlineAudioProcessor?.flush()
                    finalSampleRate = onlineAudioProcessor?.sampleRate
                    Log.d(TAG, "Flushing audio processor, got ${finalPcm?.size ?: 0} final samples.")
                }
            }

            val pcmToEnqueue = finalPcm
            if (pcmToEnqueue != null && pcmToEnqueue.isNotEmpty() && finalSampleRate != null) {
                val lastIndex = (synthesisSentenceIndex - 1).coerceAtLeast(0)
                audioPlayer.enqueuePcm(
                    pcm = pcmToEnqueue,
                    offset = 0,
                    length = pcmToEnqueue.size,
                    sampleRate = finalSampleRate!!,
                    source = SynthesisMode.ONLINE,
                    sentenceIndex = lastIndex
                )
            }

            if (coroutineContext.isActive) {
                Log.i(TAG, "合成循环结束。向播放器发送流结束(EOS)标记。是否因失败而结束: $synthesisFailed")
                audioPlayer.enqueueEndOfStream {
                    if (synthesisFailed) {
                        isPausedByError = true
                        sendCommand(Command.Pause)
                    } else {
                        Log.i(TAG, "所有句子正常合成完毕，等待播放结束...")
                    }
                }
            }
        }
    }

    private suspend fun performOnlineSynthesis(index: Int, sentence: String): SynthesisResult {
        try {
            val sentence = sentence.trim()
            if (sentence.isEmpty()) {
                Log.w(TAG, "句子 $index 内容为空，跳过在线合成。")
                return SynthesisResult.Success
            }
            Log.d(TAG, "合成[在线]句子 $index: \"$sentence\"")

            // TtsRepository 会处理缓存检查和网络请求的逻辑
            // 即使在冷却期，Repository也会检查缓存，只是不会发起网络请求
            val isCoolingDown = System.currentTimeMillis() < onlineCooldownUntilTimestamp
            val decoded = ttsRepository.getDecodedPcm(sentence, currentSpeaker, allowNetwork = !isCoolingDown)

            val pcmData = decoded.pcmData
            val sampleRate = decoded.sampleRate
            if (!coroutineContext.isActive) return SynthesisResult.Failure("协程被取消")

            val startCb = { sendCommand(Command.InternalSentenceStart(index, sentence)) }
            val endCb = { sendCommand(Command.InternalSentenceEnd(index, sentence)) }

            if (pcmData.isEmpty()) {
                audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.ONLINE, startCb)
                audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.ONLINE, endCb)
                return SynthesisResult.Success
            }

            audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.ONLINE, startCb)

            processorMutex.withLock {
                if (onlineAudioProcessor == null || onlineAudioProcessor?.sampleRate != sampleRate) {
                    onlineAudioProcessor?.release()
                    onlineAudioProcessor = AudioSpeedProcessor(sampleRate)
                    onlineAudioProcessor?.setSpeed(currentSpeed)
                    Log.i(TAG, "Online audio sample rate is $sampleRate, created new AudioSpeedProcessor.")
                }

                val speedAdjustedPcm = onlineAudioProcessor?.process(pcmData) ?: pcmData

                if (speedAdjustedPcm.isNotEmpty()) {
                    audioPlayer.enqueuePcm(pcm = speedAdjustedPcm, sampleRate = sampleRate, source = SynthesisMode.ONLINE, sentenceIndex = index)
                }
            }

            audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.ONLINE, endCb)
            return SynthesisResult.Success
        } catch (e: Exception) {
            // 捕获所有来自 Repository 的异常 (如 IOException, WxApiException, DecodeException)
            val reason = "合成[在线] (句子 $index, ${sentence.trim()})失败: ${e.message}"
            Log.w(TAG, reason/*, if (e is IOException) null else e*/) // 避免为常见网络错误打印完整堆栈
            return SynthesisResult.Failure(reason)
        }
    }

    private suspend fun performOfflineSynthesis(index: Int, sentence: String): SynthesisResult {
        return engineMutex.withLock {
            try {
                val sentence = sentence.trim()
                if (sentence.isEmpty()) {
                    Log.w(TAG, "句子 $index 内容为空，跳过离线合成。")
                    return@withLock SynthesisResult.Success
                }
                Log.d(TAG, "合成[离线]句子 $index: \"$sentence\"")
                val prepare = prepareForSynthesis(sentence, currentSpeed, currentVolume)
                if (prepare != 0) {
                    val reason = "合成[离线]句子准备失败 (code=$prepare) 句子: $sentence"
                    Log.e(TAG, reason)
                    return@withLock SynthesisResult.Success
                }

                val startCb = { sendCommand(Command.InternalSentenceStart(index, sentence)) }
                val endCb = { sendCommand(Command.InternalSentenceEnd(index, sentence)) }

                val synthResult = IntArray(1)
                val pcmArray = pcmBuffer.array()
                var hasStart = false
                while (coroutineContext.isActive) {
                    val status = nativeEngine?.synthesize(pcmArray, TtsConstants.PCM_BUFFER_SIZE, synthResult, 1) ?: -1
                    if (status == -1) {
                        val reason = "合成[离线]句子合成失败，状态码: -1"
                        Log.e(TAG, reason)
                        return@withLock SynthesisResult.Success
                    }
                    val num = synthResult[0]
                    if (num <= 0) break
                    if (!hasStart) {
                        audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.OFFLINE, startCb)
                        hasStart = true
                    }
                    val valid = num.coerceAtMost(pcmArray.size)
                    if (valid > 0) {
                        val chunk = pcmArray.copyOf(valid)
                        audioPlayer.enqueuePcm(pcm = chunk, sampleRate = TtsConstants.DEFAULT_SAMPLE_RATE, source = SynthesisMode.OFFLINE, sentenceIndex = index)
                    }
                    delay(1)
                }
                if (coroutineContext.isActive) {
                    if (!hasStart) {
                        audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.OFFLINE, startCb)
                    }
                    audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.OFFLINE, endCb)
                }
                SynthesisResult.Success
            } catch (e: CancellationException) {
                SynthesisResult.Failure("合成[离线](句子 $index, ${sentence.trim()})协程被取消")
            } catch (e: Exception) {
                val reason = "合成[离线](句子 $index, ${sentence.trim()})异常: ${e.message}"
                Log.e(TAG, reason, e)
                SynthesisResult.Failure(reason)
            } finally {
                nativeEngine?.reset()
            }
        }
    }

    private fun prepareForSynthesis(text: String, speed: Float, volume: Float): Int {
        synchronized(this) {
            if (currentSpeaker.offlineModelName != currentVoiceCode) {
                currentVoiceCode = currentSpeaker.offlineModelName
                nativeEngine?.setVoiceName(currentSpeaker.offlineModelName)
            }
            nativeEngine?.setSpeed(speed)
            nativeEngine?.setVolume(volume)
            var prepareResult = -1
            for (attempt in 0 until TtsConstants.MAX_PREPARE_RETRIES) {
                prepareResult = nativeEngine?.prepareUTF8(text.toByteArray()) ?: -1
                if (prepareResult == 0) break
                nativeEngine?.setVoiceName(currentSpeaker.offlineModelName)
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

    // --- 新增：用于管理在线模式冷却的辅助函数 ---

    /**
     * 当在线合成失败时调用此函数。
     * 它会增加失败计数，并根据指数退避算法计算并设置下一次可以尝试在线模式的时间戳。
     */
    private fun activateOnlineCooldown() {
        onlineFailureCount++
        // 计算退避乘数 (1, 2, 4, 8, 16, 32...)
        val backoffFactor = (1 shl (onlineFailureCount - 1).coerceAtMost(5)).toLong()
        val cooldownDuration = (BASE_COOLDOWN_MS * backoffFactor).coerceAtMost(MAX_COOLDOWN_MS)
        onlineCooldownUntilTimestamp = System.currentTimeMillis() + cooldownDuration
        Log.i(TAG, "在线合成失败次数: $onlineFailureCount。激活冷却期 ${cooldownDuration}ms。")
    }

    /**
     * 当在线合成成功或需要强制重置状态时调用。
     * 它会清除失败计数和冷却时间戳，使系统可以立即恢复使用在线模式。
     */
    private fun resetOnlineCooldown() {
        if (onlineFailureCount > 0 || onlineCooldownUntilTimestamp > 0L) {
            Log.i(TAG, "在线合成恢复正常或被重置。清除失败计数和冷却期。")
        }
        onlineFailureCount = 0
        onlineCooldownUntilTimestamp = 0L
    }
}