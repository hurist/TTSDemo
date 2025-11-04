package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.cache.TtsCacheImpl
import com.qq.wx.offlinevoice.synthesizer.online.MediaCodecMp3Decoder
import com.qq.wx.offlinevoice.synthesizer.online.WxReaderApi
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
import kotlin.properties.Delegates

/**
 * TTS 合成器主类。
 *
 * 职责：
 * - 接收外部控制命令 (speak, stop, pause, setSpeed, setVoice, setVolume 等)；
 * - 维护播放状态、句子队列与当前索引；
 * - 策略驱动（ONLINE_ONLY / ONLINE_PREFERRED / OFFLINE）与在线失败退避；
 * - 网络恢复哨兵（稳定窗口去抖）；
 * - 与 AudioPlayer 协作进行在线/离线流式合成与播放；
 * - 回调外部状态、进度与错误。
 *
 * 稳定性修复（与 AudioPlayer 的“保护期 + 分桶吐回”配合）：
 * - 会话 ID（sessionId）守卫：每次启动/软重启/停止都会 bump，旧任务的任何“晚到 PCM/Marker/EOS”都被丢弃；
 * - Stop 硬关闸：先 bump 会话，再取消任务并 stopAndRelease 播放器，立即置为 IDLE；
 * - finally 仅在“仍是活动会话且协程活跃”时 flush/EOS；取消后绝不向新会话写尾巴；
 * - 在线路径对“非空句但无 PCM”判为 Failure；真正空句（trim 为空）仅发 Marker 仍视作 Success；
 *
 * 新增（本次修复的重点）：
 * - 统一“暂停期参数变更”机制：使用 pendingChanges 集合记录暂停时的参数变更项（SPEAKER、SPEED）。
 *   在 PAUSED 状态下 setVoice/setSpeed 时，不立刻重启；仅在第一次记录变更时执行“预备清空”：
 *   bump 会话 -> 取消旧合成循环 -> 清空播放队列。用户 resume 时统一执行一次“参数感知软重启”，
 *   将 synthesisSentenceIndex 回拨到 playingSentenceIndex，用新参数从“当前这句话开头”重新合成与播放。
 */
class TtsSynthesizer(
    context: Context,
    private val speaker: Speaker
) {
    // --- 内部结果枚举 ---
    private sealed class SynthesisResult {
        object Success : SynthesisResult()
        data class Failure(val reason: String) : SynthesisResult()
        object Deferred : SynthesisResult() // 暂不产出（如保护期/会话切换）
    }

    // --- 命令模型（Actor 模式） ---
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

    // --- 播放状态与参数 ---
    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE
    private val sentences = mutableListOf<String>()
    @Volatile private var playingSentenceIndex: Int = 0
    private var synthesisSentenceIndex by Delegates.observable(0) { _, oldValue, newValue ->
        Log.d(TAG, "修改synthesisSentenceIndex： $oldValue -> $newValue")
    }
    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentSpeaker = speaker
    private var currentCallback: TtsCallback? = null

    // --- 策略与数据源 ---
    private val strategyManager: SynthesisStrategyManager
    private val ttsRepository: TtsRepository

    // --- 可观测播放状态 ---
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // --- 本地离线引擎相关 ---
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)

    // --- 并发与任务 ---
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private var synthesisJob: Job? = null
    private val audioPlayer: AudioPlayer = AudioPlayer(initialSampleRate = TtsConstants.DEFAULT_SAMPLE_RATE)
    private val engineMutex = Mutex()
    private var isPausedByError = false
    private var onlineAudioProcessor: AudioSpeedProcessor? = null
    private val processorMutex = Mutex()
    private val splitterStrategy = SentenceSplitterStrategy.NEWLINE

    // --- 在线模式冷却状态 ---
    private var onlineFailureCount: Int = 0
    private var onlineCooldownUntilTimestamp: Long = 0L

    // --- 升级窗口（保护期）感知（配合 AudioPlayer 的保护期） ---
    @Volatile private var upgradeWindowActive: Boolean = false
    @Volatile private var upgradeProtectedIndex: Int = -1

    // --- 会话 ID 守卫（阻断旧任务的“晚到写入/回调”） ---
    @Volatile private var synthesisSessionId: Long = 0L
    private fun bumpSessionId(): Long {
        val newId = (synthesisSessionId + 1)
        synthesisSessionId = newId
        Log.i(TAG, "Bump synthesis session id -> $newId")
        return newId
    }

    // --- 暂停期“待应用参数变更”的统一集合 ---
    private enum class PendingChange { SPEAKER, SPEED }
    private val pendingChanges = mutableSetOf<PendingChange>()

    // --- 常量与静态初始化 ---
    companion object {
        private const val TAG = "TtsSynthesizer"
        private val instanceCount = AtomicInteger(0)
        @Volatile private var nativeEngine: SynthesizerNative? = null
        @Volatile private var currentVoiceCode: String? = null

        // 指数退避
        private const val BASE_COOLDOWN_MS = 3000L
        private const val MAX_COOLDOWN_MS = 60000L

        // 网络恢复稳定期（去抖）
        private const val NETWORK_STABILIZE_MS = 600L

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
        val onlineApi = WxReaderApi
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

    // --- 命令处理器（Actor） ---
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
                is Command.InternalSentenceStart -> {
                    playingSentenceIndex = command.index
                    currentCallback?.onSentenceStart(command.index, command.sentence, sentences.size)
                    Log.d(TAG, "修改句子索引为 ${command.index}: ${command.sentence}")
                }
                is Command.InternalSentenceEnd -> {
                    currentCallback?.onSentenceComplete(command.index, command.sentence)
                    if (upgradeWindowActive && command.index == upgradeProtectedIndex) {
                        Log.i(TAG, "受保护句 #$upgradeProtectedIndex 已结束，关闭升级窗口。")
                        upgradeWindowActive = false
                        upgradeProtectedIndex = -1
                    }
                    if (command.index == sentences.size - 1 && !isPausedByError) {
                        updateState(TtsPlaybackState.IDLE)
                        currentCallback?.onSynthesisComplete()
                    }
                }
                is Command.InternalSynthesisFinished -> { /* no-op */ }
                is Command.InternalError -> {
                    Log.e(TAG, "收到内部错误，将执行 handleStop: ${command.message}")
                    handleStop()
                    currentCallback?.onError(command.message)
                }
            }
        }
    }

    // --- 命令实现 ---

    private suspend fun handleSpeak(text: String) {
        if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
            Log.d(TAG, "已有语音在播放中，将先停止当前任务再开始新的任务。")
            handleStop()
        }
        isPausedByError = false
        pendingChanges.clear()
        sentences.clear()

        val result = when (splitterStrategy) {
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
        if (currentSpeed == newSpeed) {
            Log.d(TAG, "setSpeed: 与当前速度一致($newSpeed)，忽略。")
            return
        }
        currentSpeed = newSpeed
        Log.i(TAG, "setSpeed: 设定新速度=$newSpeed")

        when (currentState) {
            TtsPlaybackState.PLAYING -> {
                // 播放中：即时生效 -> 更新处理器并软重启
                processorMutex.withLock { onlineAudioProcessor?.setSpeed(newSpeed) }
                Log.i(TAG, "播放中修改速度，执行软重启以立即生效。")
                softRestart()
            }
            TtsPlaybackState.PAUSED -> {
                val first = pendingChanges.add(PendingChange.SPEED)
                Log.i(TAG, "暂停中修改速度，记录待应用变更（首次=$first），恢复时从当前句开头用新速度播放。")
                if (first) {
                    scheduleParamRestartWhilePaused("setSpeed")
                } else {
                    Log.d(TAG, "已存在待应用参数变更，跳过重复预备重启。")
                }
            }
            else -> {
                Log.i(TAG, "IDLE 状态修改速度，将在下一次 speak 生效。")
            }
        }
    }

    private suspend fun handleSetSpeaker(speaker: Speaker) {
        if (speaker == currentSpeaker) {
            Log.d(TAG, "setVoice: 与当前 speaker 相同，忽略。")
            return
        }
        currentSpeaker = speaker

        when (currentState) {
            TtsPlaybackState.PLAYING -> {
                Log.i(TAG, "播放中切换 speaker，执行软重启以立即生效。")
                softRestart()
            }
            TtsPlaybackState.PAUSED -> {
                val first = pendingChanges.add(PendingChange.SPEAKER)
                Log.i(TAG, "暂停中切换 speaker，记录待应用变更（首次=$first），恢复时从当前句开头用新 speaker 播放。")
                if (first) {
                    scheduleParamRestartWhilePaused("setVoice")
                } else {
                    Log.d(TAG, "已存在待应用参数变更，跳过重复预备重启。")
                }
            }
            else -> {
                Log.i(TAG, "当前状态($currentState)切换 speaker，等待下一次 speak 生效。")
            }
        }
    }

    private fun handleSetVolume(volume: Float) {
        val newVolume = volume.coerceIn(0.0f, 1.0f)
        if (currentVolume == newVolume) {
            Log.d(TAG, "setVolume: 与当前音量一致($newVolume)，忽略。")
            return
        }
        currentVolume = newVolume
        audioPlayer.setVolume(newVolume)
        Log.i(TAG, "setVolume: 音量已设定为 $newVolume")
    }

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

    /**
     * Resume 修复点：
     * - 若 pendingChanges 非空（暂停期间改了 speaker/speed）或 isPausedByError 为真，则进行“参数感知软重启”：
     *   bump 会话 ID -> 取消旧循环 -> 清空队列 -> 将 synthesisSentenceIndex 回到 playingSentenceIndex ->
     *   startSynthesis() -> 再 resume()，从“当前句开头”用新参数重新播放。
     */
    private suspend fun handleResume() {
        if (currentState != TtsPlaybackState.PAUSED) {
            Log.w(TAG, "无法恢复，当前状态为 $currentState, 而非 PAUSED。")
            return
        }

        val needParamRestart = pendingChanges.isNotEmpty()
        val needErrorRestart = isPausedByError

        if (needParamRestart || needErrorRestart) {
            Log.i(TAG, "恢复前需要重启合成：pendingChanges=$pendingChanges, isPausedByError=$needErrorRestart。")
            val restartIndex = playingSentenceIndex

            // 清标志
            pendingChanges.clear()
            isPausedByError = false

            // 会话守卫：先切断旧循环的任何可能晚到写入
            bumpSessionId()

            // 取消旧合成循环并等待退出
            synthesisJob?.cancelAndJoin()
            synthesisJob = null
            Log.d(TAG, "恢复前：旧的合成任务已取消并结束。")

            // 清空播放队列，回拨到当前句索引，准备从“当前句开头”重新合成
            audioPlayer.resetBlocking()
            Log.d(TAG, "恢复前：播放器已同步重置（清空队列）。")
            synthesisSentenceIndex = restartIndex

            // 用新参数从当前句重新开始
            startSynthesis()
            Log.d(TAG, "恢复前：新的合成任务已从索引 $synthesisSentenceIndex 处启动（将用新参数重新合成当前句）。")
        }

        updateState(TtsPlaybackState.PLAYING)
        audioPlayer.resume()
        currentCallback?.onResumed()
    }

    /**
     * Stop：硬关闸
     * - 先 bump 会话 ID，立即让旧任务“失效”，避免任何晚到入队；
     * - 取消旧任务，音频播放器同步 stop 并释放资源（后续 enqueue 直接 return）；
     * - 立刻置状态为 IDLE；
     * - 在线处理器释放放入后台协程，避免阻塞命令处理器。
     */
    private suspend fun handleStop() {
        if (currentState == TtsPlaybackState.IDLE) return
        Log.d(TAG, "开始执行 handleStop...")

        bumpSessionId()

        isPausedByError = false
        pendingChanges.clear()

        val jobToCancel = synthesisJob
        synthesisJob = null
        jobToCancel?.cancel()

        audioPlayer.stopAndReleaseBlocking()
        Log.d(TAG, "播放器已完全停止并释放。")

        sentences.clear()
        upgradeWindowActive = false
        upgradeProtectedIndex = -1
        updateState(TtsPlaybackState.IDLE)
        Log.d(TAG, "状态已置为 IDLE。")

        scope.launch {
            try {
                jobToCancel?.join()
                Log.d(TAG, "旧合成任务已退出。")
            } catch (e: Exception) {
                Log.w(TAG, "等待旧任务退出时异常: ${e.message}")
            }
            processorMutex.withLock {
                onlineAudioProcessor?.release()
                onlineAudioProcessor = null
            }
        }
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

    /**
     * 软重启：
     * - bump 会话 ID，取消并等待旧循环结束；
     * - 同步 Reset 播放器；
     * - 从当前句索引重启合成。
     */
    private suspend fun softRestart() {
        Log.d(TAG, "软重启请求，句子索引: $playingSentenceIndex")
        val restartIndex = playingSentenceIndex

        bumpSessionId()

        synthesisJob?.cancelAndJoin()
        synthesisJob = null
        Log.d(TAG, "旧的合成任务已取消并等待结束。")

        audioPlayer.resetBlocking()
        Log.d(TAG, "播放器已同步重置。")

        synthesisSentenceIndex = restartIndex
        startSynthesis()
        Log.d(TAG, "新的合成任务已从索引 $restartIndex 处启动。")
    }

    // --- 暂停期：预备重启（仅首次变更时执行，防抖） ---
    private suspend fun scheduleParamRestartWhilePaused(reason: String) {
        // 立即让旧循环与后续入队失效
        bumpSessionId()

        // 取消旧合成循环（后台等待退出）
        val job = synthesisJob
        synthesisJob = null
        job?.cancel()
        scope.launch {
            try { job?.join() } catch (_: Exception) { }
        }

        // 清空播放队列
        audioPlayer.resetBlocking()
        Log.d(TAG, "暂停中 $reason：已取消旧循环并清空播放队列，等待恢复时从当前句重启生效。")
    }

    // --- 合成逻辑入口 ---
    /**
     * 启动合成任务。
     * 并行任务：
     * 1) 核心合成循环（launchSynthesisLoop）
     * 2) 网络状态哨兵（ONLINE_PREFERRED 下，网络坏->好时触发升级窗口与重新尝试在线）
     */
    private fun startSynthesis() {
        // 每次开始新的合成任务时，重置在线失败状态，并 bump 会话 ID
        resetOnlineCooldown()
        val mySession = bumpSessionId()

        synthesisJob = scope.launch(Dispatchers.Default) {
            var synthesisLoopJob: Job? = null

            fun runSynthesisLoop() {
                synthesisLoopJob?.cancel()
                synthesisLoopJob = launchSynthesisLoop(mySession)
            }

            if (strategyManager.currentStrategy == TtsStrategy.ONLINE_PREFERRED) {
                launch {
                    var wasNetworkBad = !strategyManager.isNetworkGood.value
                    strategyManager.isNetworkGood.collect { isNetworkGood ->
                        if (wasNetworkBad && isNetworkGood) {
                            // 稳定窗口去抖
                            delay(NETWORK_STABILIZE_MS)
                            if (!strategyManager.isNetworkGood.value) {
                                Log.i(TAG, "网络恢复检测在稳定窗口后失效，取消本次升级触发。")
                                wasNetworkBad = !strategyManager.isNetworkGood.value
                                return@collect
                            }
                            // 避免重复触发升级窗口
                            if (upgradeWindowActive) {
                                Log.i(TAG, "升级窗口仍在进行，忽略重复的网络恢复触发。")
                                wasNetworkBad = !strategyManager.isNetworkGood.value
                                return@collect
                            }
                            resetOnlineCooldown()
                            Log.i(TAG, "网络已恢复且通过稳定窗口。执行升级：软重启合成循环并进入升级窗口（保护期）。")

                            // 停止当前的合成循环，确保不再向队列中添加低质量音频
                            synthesisLoopJob?.cancelAndJoin()

                            // 清理播放队列中尚未播放的低质量音频，但保留正在播放的句子
                            val protectedIndex = playingSentenceIndex
                            audioPlayer.resetQueueOnlyBlocking(preserveSentenceIndex = protectedIndex)

                            // 标记升级窗口，在受保护句 END 到达后关闭
                            upgradeWindowActive = true
                            upgradeProtectedIndex = protectedIndex

                            // 从下一句开始重新尝试在线合成（保持相同会话 mySession）
                            synthesisSentenceIndex = protectedIndex + 1
                            if (synthesisSentenceIndex < sentences.size) {
                                Log.i(TAG, "将从句子 $synthesisSentenceIndex 处重新开始在线合成（升级窗口生效中，会话=$mySession）。")
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
     * 核心合成循环（携带 mySession，会话守卫）。
     */
    private fun CoroutineScope.launchSynthesisLoop(mySession: Long) = launch {
        var synthesisFailed = false
        try {
            while (coroutineContext.isActive && synthesisSentenceIndex < sentences.size && mySession == synthesisSessionId) {
                val index = synthesisSentenceIndex
                val sentence = sentences[index]
                val sessionStrategy = strategyManager.currentStrategy

                val finalResult = when (sessionStrategy) {
                    TtsStrategy.ONLINE_PREFERRED, TtsStrategy.ONLINE_ONLY -> {
                        val onlineResult = performOnlineSynthesis(mySession, index, sentence)
                        if (onlineResult is SynthesisResult.Success) {
                            resetOnlineCooldown()
                            onlineResult
                        } else {
                            activateOnlineCooldown()
                            if (sessionStrategy == TtsStrategy.ONLINE_PREFERRED) {
                                Log.w(TAG, "在线路径失败(缓存未命中/无PCM或API错误)，回退至[离线模式]。原因: ${(onlineResult as? SynthesisResult.Failure)?.reason ?: "unknown"}")
                                performOfflineSynthesis(mySession, index, sentence)
                            } else {
                                Log.e(TAG, "纯在线模式合成失败，无可用回退。原因: ${(onlineResult as? SynthesisResult.Failure)?.reason ?: "unknown"}")
                                onlineResult
                            }
                        }
                    }
                    else -> performOfflineSynthesis(mySession, index, sentence)
                }

                when (finalResult) {
                    is SynthesisResult.Success -> {
                        Log.d(TAG, "处理合成位置：synthesisSentenceIndex:$synthesisSentenceIndex, index:$index")
                        if (synthesisSentenceIndex == index) synthesisSentenceIndex++
                    }
                    is SynthesisResult.Deferred -> {
                        Log.i(TAG, "句子 $index 合成被延后（通常因保护期/会话切换），将稍后重试。")
                        delay(200)
                    }
                    is SynthesisResult.Failure -> {
                        Log.e(TAG, "句子 $index 合成最终失败 (策略: $sessionStrategy): ${finalResult.reason}")
                        synthesisFailed = true
                        break
                    }
                }
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "合成循环被取消。")
        } finally {
            val stillActive = coroutineContext.isActive && mySession == synthesisSessionId
            if (stillActive) {
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
                    if (mySession == synthesisSessionId && coroutineContext.isActive) {
                        audioPlayer.enqueuePcm(
                            pcm = pcmToEnqueue,
                            offset = 0,
                            length = pcmToEnqueue.size,
                            sampleRate = finalSampleRate!!,
                            source = SynthesisMode.ONLINE,
                            sentenceIndex = lastIndex
                        )
                    }
                }

                if (mySession == synthesisSessionId && coroutineContext.isActive) {
                    Log.i(TAG, "合成循环结束(活动会话)。发送EOS。失败标志: $synthesisFailed")
                    audioPlayer.enqueueEndOfStream {
                        if (synthesisFailed) {
                            isPausedByError = true
                            sendCommand(Command.Pause)
                        } else {
                            Log.i(TAG, "所有句子正常合成完毕，等待播放结束...")
                        }
                    }
                }
            } else {
                Log.i(TAG, "合成循环结束（非活动会话或已取消），跳过 flush/EOS。")
            }
        }
    }

    /**
     * 在线合成（缓存优先）。
     * - 对“非空句但无PCM”的结果判为 Failure，避免仅凭 Marker 推进。
     * - 真正“空句”（trim 为空）仍触发 START/END，保证回调一致性。
     * - 全路径会话守卫：任何入队/回调前后都检查 mySession 是否仍为活动会话。
     */
    private suspend fun performOnlineSynthesis(mySession: Long, index: Int, sentence: String): SynthesisResult {
        try {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) {
                if (!coroutineContext.isActive || mySession != synthesisSessionId) return SynthesisResult.Deferred
                val startCb = { if (mySession == synthesisSessionId) sendCommand(Command.InternalSentenceStart(index, sentence)) }
                val endCb = { if (mySession == synthesisSessionId) sendCommand(Command.InternalSentenceEnd(index, sentence)) }
                audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.ONLINE, startCb)
                audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.ONLINE, endCb)
                return SynthesisResult.Success
            }
            Log.d(TAG, "合成[在线]句子 $index: \"$trimmed\"")

            val isCoolingDown = System.currentTimeMillis() < onlineCooldownUntilTimestamp
            val decoded = ttsRepository.getDecodedPcm(trimmed, currentSpeaker, allowNetwork = !isCoolingDown)

            if (!coroutineContext.isActive || mySession != synthesisSessionId) return SynthesisResult.Deferred

            val pcmData = decoded.pcmData
            val sampleRate = decoded.sampleRate
            if (pcmData.isEmpty()) {
                val reason = "在线合成未产出PCM（非空句），index=$index"
                Log.w(TAG, reason)
                return SynthesisResult.Failure(reason)
            }

            val startCb = { if (mySession == synthesisSessionId) sendCommand(Command.InternalSentenceStart(index, sentence)) }
            val endCb = { if (mySession == synthesisSessionId) sendCommand(Command.InternalSentenceEnd(index, sentence)) }

            if (!coroutineContext.isActive || mySession != synthesisSessionId) return SynthesisResult.Deferred
            audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.ONLINE, startCb)

            processorMutex.withLock {
                if (onlineAudioProcessor == null || onlineAudioProcessor?.sampleRate != sampleRate) {
                    onlineAudioProcessor?.release()
                    onlineAudioProcessor = AudioSpeedProcessor(sampleRate)
                    onlineAudioProcessor?.setSpeed(currentSpeed)
                    Log.i(TAG, "Online audio sample rate is $sampleRate, created new AudioSpeedProcessor.")
                } else {
                    // 确保以当前速度处理
                    onlineAudioProcessor?.setSpeed(currentSpeed)
                }

                val speedAdjustedPcm = onlineAudioProcessor?.process(pcmData) ?: pcmData
                if (!coroutineContext.isActive || mySession != synthesisSessionId) return@withLock
                if (speedAdjustedPcm.isNotEmpty()) {
                    audioPlayer.enqueuePcm(
                        pcm = speedAdjustedPcm,
                        offset = 0,
                        length = speedAdjustedPcm.size,
                        sampleRate = sampleRate,
                        source = SynthesisMode.ONLINE,
                        sentenceIndex = index
                    )
                }
            }

            if (!coroutineContext.isActive || mySession != synthesisSessionId) return SynthesisResult.Deferred
            audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.ONLINE, endCb)
            return SynthesisResult.Success
        } catch (e: Exception) {
            val reason = "合成[在线] (句子 $index, ${sentence.trim()})失败: ${e.message}"
            Log.w(TAG, reason)
            return SynthesisResult.Failure(reason)
        }
    }

    /**
     * 离线合成。
     * - 保护期内且目标句不是受保护句：返回 Deferred；
     * - 会话守卫：入队/回调前后都检查 mySession 是否仍为活动会话。
     */
    private suspend fun performOfflineSynthesis(mySession: Long, index: Int, sentence: String): SynthesisResult {
        if (audioPlayer.isInProtection() && index != audioPlayer.getProtectedSentenceIndex()) {
            Log.i(TAG, "离线合成请求被延后：当前处于保护期，受保护句=${audioPlayer.getProtectedSentenceIndex()}，请求句=$index")
            return SynthesisResult.Deferred
        }

        return engineMutex.withLock {
            try {
                val trimmed = sentence.trim()
                if (trimmed.isEmpty()) {
                    Log.w(TAG, "句子 $index 内容为空，跳过离线合成。")
                    return@withLock SynthesisResult.Success
                }
                if (!coroutineContext.isActive || mySession != synthesisSessionId) {
                    Log.i(TAG, "离线合成开始前即会话失效/被取消，index=$index -> Deferred")
                    return@withLock SynthesisResult.Deferred
                }

                Log.d(TAG, "合成[离线]句子 $index: \"$trimmed\"")
                val prepare = prepareForSynthesis(trimmed, currentSpeed, currentVolume)
                if (prepare != 0) {
                    val reason = "合成[离线]句子准备失败 (code=$prepare) 句子: $trimmed"
                    Log.e(TAG, "prepare 失败：$reason（按成功跳过处理，避免打断整体流程）")
                    return@withLock SynthesisResult.Success
                }

                val startCb = { if (mySession == synthesisSessionId) sendCommand(Command.InternalSentenceStart(index, trimmed)) }
                val endCb = { if (mySession == synthesisSessionId) sendCommand(Command.InternalSentenceEnd(index, trimmed)) }

                val synthResult = IntArray(1)
                val pcmArray = pcmBuffer.array()
                var hasStart = false
                while (coroutineContext.isActive && mySession == synthesisSessionId) {
                    if (audioPlayer.isInProtection() && index != audioPlayer.getProtectedSentenceIndex()) {
                        Log.i(TAG, "离线合成过程中进入/仍在保护期，句子 $index 延后（Deferred）。")
                        return@withLock SynthesisResult.Deferred
                    }

                    val status = nativeEngine?.synthesize(pcmArray, TtsConstants.PCM_BUFFER_SIZE, synthResult, 1) ?: -1
                    if (status == -1) {
                        val reason = "合成[离线]句子合成失败，状态码: -1"
                        Log.e(TAG, reason)
                        return@withLock SynthesisResult.Success
                    }
                    val num = synthResult[0]
                    if (num <= 0) break
                    if (!hasStart) {
                        if (!coroutineContext.isActive || mySession != synthesisSessionId) return@withLock SynthesisResult.Deferred
                        audioPlayer.enqueueMarker(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.OFFLINE, startCb)
                        hasStart = true
                    }
                    val valid = num.coerceAtMost(pcmArray.size)
                    if (valid > 0) {
                        if (!coroutineContext.isActive || mySession != synthesisSessionId) return@withLock SynthesisResult.Deferred
                        val chunk = pcmArray.copyOf(valid)
                        audioPlayer.enqueuePcm(
                            pcm = chunk,
                            offset = 0,
                            length = chunk.size,
                            sampleRate = TtsConstants.DEFAULT_SAMPLE_RATE,
                            source = SynthesisMode.OFFLINE,
                            sentenceIndex = index
                        )
                    }
                    delay(1)
                }
                if (coroutineContext.isActive && mySession == synthesisSessionId) {
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

    // --- 离线引擎准备 ---
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

    // --- 状态变更 ---
    private fun updateState(newState: TtsPlaybackState) {
        if (currentState != newState) {
            currentState = newState
            currentCallback?.onStateChanged(newState)
            _isPlaying.value = newState == TtsPlaybackState.PLAYING
        }
    }

    // --- 在线模式冷却管理 ---
    private fun activateOnlineCooldown() {
        onlineFailureCount++
        val backoffFactor = (1 shl (onlineFailureCount - 1).coerceAtMost(5)).toLong() // 1,2,4,8,16,32
        val cooldownDuration = (BASE_COOLDOWN_MS * backoffFactor).coerceAtMost(MAX_COOLDOWN_MS)
        onlineCooldownUntilTimestamp = System.currentTimeMillis() + cooldownDuration
        Log.i(TAG, "在线合成失败次数: $onlineFailureCount。激活冷却期 ${cooldownDuration}ms。")
    }

    private fun resetOnlineCooldown() {
        if (onlineFailureCount > 0 || onlineCooldownUntilTimestamp > 0L) {
            Log.i(TAG, "在线合成恢复正常或被重置。清除失败计数和冷却期。")
        }
        onlineFailureCount = 0
        onlineCooldownUntilTimestamp = 0L
    }

    fun getVoiceDataPath(): String = voiceDataPath
}