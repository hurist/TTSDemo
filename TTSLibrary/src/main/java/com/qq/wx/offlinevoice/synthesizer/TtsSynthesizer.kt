package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import com.qq.wx.offlinevoice.synthesizer.cache.TtsCacheImpl
import com.qq.wx.offlinevoice.synthesizer.online.MediaCodecMp3Decoder
import com.qq.wx.offlinevoice.synthesizer.online.WxApiException
import com.qq.wx.offlinevoice.synthesizer.online.WxReaderApi
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlin.math.min
import kotlin.math.max
import kotlin.math.pow
import androidx.core.content.edit
import kotlinx.coroutines.withTimeout

/**
 * TTS 合成器主类（Actor 模型）。
 *
 * 职责：
 * - 接收外部控制命令 (speak, stop, pause, setSpeed, setVoice, setVolume, seekToSentence 等)；
 * - 维护播放状态、句子队列与当前索引；
 * - 策略驱动（ONLINE_ONLY / ONLINE_PREFERRED / OFFLINE）与在线失败退避；
 * - 网络恢复哨兵（稳定窗口去抖，ONLINE_PREFERRED 下对未播部分升级在线）；
 * - 与 AudioPlayer 协作进行在线/离线流式合成与播放；
 * - 回调外部状态、进度与错误。
 *
 * 方案 A+B（集中守卫 + SessionScope）要点：
 * - 会话作用域（sessionJob + SessionScope）：每次启动/软重启/停止都会创建新的 SupervisorJob，旧会话作用域被取消，
 *   循环与子任务依赖协程取消语义（isActive）自然退出。
 * - 守卫集中化：仅在“副作用边界”统一校验（入队 PCM/Marker/EOS、回调触发、finally flush/EOS），
 *   删除大量散落的 mySession==synthesisSessionId 判断。
 * - 暂停期参数变更统一管理：pendingChanges 集合（SPEAKER/SPEED）；resume 时一次性“参数感知软重启”，
 *   从“当前句开头”用新参数重合成，避免旧参数音频泄漏。
 *
 * 新增功能（本次）：
 * - seekToSentence：支持按句拖动进度。行为：
 *   - PLAYING：立即切到目标句播放（清空队列、取消旧循环、从目标句重启）。
 *   - PAUSED：立即更新进度索引（playingSentenceIndex），真正播放在恢复时生效（仅首次 seek 清队，防抖）。
 *   - 目标为空句时：若不是最后一句则向后找第一个非空句，否则向前找第一个非空句；全空则保持不动。
 */
class TtsSynthesizer(
    private val context: Context,
    speaker: Speaker,
    private var currentCallback: TtsCallback? = null
) {

    private sealed class SynthesisResult {
        object Success : SynthesisResult()
        data class Failure(val reason: String) : SynthesisResult()
        object Deferred : SynthesisResult() // 暂不产出（如保护期/会话切换）
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
        data class InternalSentenceStart(val index: Int, val sentence: String, val mode: SynthesisMode, val startPos: Int, val endPos: Int) : Command()
        data class InternalSentenceEnd(val index: Int, val sentence: String) : Command()
        object InternalSynthesisFinished : Command()
        data class InternalError(val message: String) : Command()

        // 新增：按句 seek 命令
        data class SeekTo(val index: Int) : Command()
    }

    init {
        AppLogger.initialize(context)
        AppLogger.setCallback(object : AppLogger.Callback {
            override fun onLogWritten(
                level: AppLogger.Level,
                tag: String,
                msg: String
            ) {
                currentCallback?.onLog(level, "[$tag] $msg")
            }
        })
    }

    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE

    // 使用 TtsBag 取代原先的 String 句子单元
    data class TtsBag(
        val text: String,
        val index: Int,
        val utteranceId: String,
        val start: Int,
        val end: Int
    )
    private val sentences = mutableListOf<TtsBag>()

    @Volatile private var playingSentenceIndex: Int = 0
    private var synthesisSentenceIndex by Delegates.observable(0) { _, oldValue, newValue ->
        AppLogger.d(TAG, "修改synthesisSentenceIndex： $oldValue -> $newValue")
    }
    private var currentSpeed: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentSpeaker = speaker

    private val strategyManager: SynthesisStrategyManager
    private val ttsRepository: TtsRepository

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)

    private val appScope = CoroutineScope(Dispatchers.Default + Job()) // 顶层应用作用域（常驻）
    private var sessionJob: Job? = null                                  // 会话作用域的根 Job
    private var synthesisJob: Job? = null                                 // 当前 startSynthesis 顶层任务

    private val audioPlayer: AudioPlayer = AudioPlayer(initialSampleRate = TtsConstants.DEFAULT_SAMPLE_RATE)

    private val engineMutex = Mutex()
    private var isPausedByError = false
    private var onlineAudioProcessor: AudioSpeedProcessor? = null
    private val processorMutex = Mutex()
    private val splitterStrategy = SentenceSplitterStrategy.NEWLINE

    // 在线失败退避
    private var onlineFailureCount: Int = 0
    private var onlineCooldownUntilTimestamp: Long = 0L

    // ONLINE_PREFERRED 升级窗口（配合 AudioPlayer 保护期）
    @Volatile private var upgradeWindowActive: Boolean = false
    @Volatile private var upgradeProtectedIndex: Int = -1

    private val commandChannel = Channel<Command>(Channel.UNLIMITED)

    // 暂停期待应用参数变更
    private enum class PendingChange { SPEAKER, SPEED }
    private val pendingChanges = mutableSetOf<PendingChange>()

    // 新增：暂停期间的 seek 目标与防抖标记
    @Volatile private var pendingSeekIndex: Int? = null
    @Volatile private var pendingSeekScheduled: Boolean = false

    // ========= 新增：字符进度回调循环 =========
    private var characterProgressJob: Job? = null

    // ===== EWMA / 预测与持久化（SharedPreferences） =====
    private val ewmaPrefs: SharedPreferences = context.getSharedPreferences("tts_ewma", Context.MODE_PRIVATE)

    // 运行期缓存（内存命中后避免频繁 IO）
    private val scaleOverallByBucket = mutableMapOf<String, Float>()
    private val countOverallByBucket = mutableMapOf<String, Int>()

    // 句级观测缓存
    private val predictedSamplesPerSentence = mutableMapOf<Int, Long>()
    private val actualSamplesPerSentence = mutableMapOf<Int, Long>()
    private val bucketKeyPerSentence = mutableMapOf<Int, String>()
    private val sourcePerSentence = mutableMapOf<Int, SynthesisMode>()
    private val boostedPredictedSentences = mutableSetOf<Int>()

    // —— 新增：仅用于“字符进度回调映射”的分数平滑状态（不影响播放器与日志） —— //
    @Volatile private var mapSmoothLastSentence: Int = -1
    @Volatile private var mapSmoothFrac: Float = 0f

    // —— 新增：BUFFERING 状态管理 —— //
    private var bufferingJob: Job? = null
    @Volatile private var bufferingSentenceIndex: Int? = null

    companion object {
        private const val TAG = "TtsSynthesizer"
        private val instanceCount = AtomicInteger(0)
        @Volatile private var nativeEngine: SynthesizerNative? = null
        @Volatile private var currentVoiceCode: String? = null

        private const val BASE_COOLDOWN_MS = 3000L
        private const val MAX_COOLDOWN_MS = 60000L
        private const val NETWORK_STABILIZE_MS = 600L
        // 字符进度轮询周期（毫秒）
        private const val CHAR_PROGRESS_INTERVAL_MS = 50L

        // ========= 自适应预测基准常量（速度=1.0 的基线） =========
        private const val BASE_UNIT_MS      = 140f   // 原 PRED_BASE_MS_PER_UNIT 158 略降
        private const val BASE_INTERCEPT_MS = 145f   // 原 150
        private const val BASE_MIN_MS       = 370f   // 原 380
        private const val BASE_COMMA_MS     = 500f   // 介于之前多组值之间
        private const val BASE_PERIOD_MS    = 600f   // 保持偏大句末停顿
        private const val DEFAULT_INIT_SCALE_BASE = 1.32f

        // 压缩幂指数（高速下缩短幅度更大）
        private const val POW_UNIT     = 0.45f  // 原 0.35：单字时长在高 speed 更快压缩
        private const val POW_INTERCEPT= 0.20f  // 保持
        private const val POW_MIN      = 0.40f  // 原 0.30：最小时长也随 speed 更明显缩短
        private const val POW_COMMA    = 0.78f  // 原 0.60：逗号停顿在高 speed 显著缩短
        private const val POW_PERIOD   = 0.85f  // 原 0.65：句末停顿在高 speed 显著缩短
        private const val POW_SCALE    = 0.60f  // 原 0.40：冷启动整体比例在高 speed 更快收小

        // 低速附加 BOOST 最大值
        private const val LOW_BOOST_UNIT_MAX    = 0.25f
        private const val LOW_BOOST_INTERCEPT_MAX=0.15f
        private const val LOW_BOOST_MIN_MAX     = 0.20f
        private const val LOW_BOOST_COMMA_MAX   = 0.65f
        private const val LOW_BOOST_PERIOD_MAX  = 0.80f
        private const val LOW_BOOST_SCALE_MAX   = 0.22f
        private const val LOW_BOOST_POW         = 1.1f

        // 句中动态抬分母基准（再自适应）
        private const val MID_BOOST_RATIO_BASE = 1.05f
        private const val MID_BOOST_MULT_BASE  = 1.17f
        private const val MID_BOOST_STEP_MS_BASE = 250f
        private const val MID_BOOST_MIN_MS_BASE  = 500f

        // 保留旧常量名用于兼容其它代码引用（值取基准）
        private const val DEFAULT_INIT_SCALE    = DEFAULT_INIT_SCALE_BASE

        // EWMA 学习率（保持原逻辑）
        private const val ALPHA_OFFLINE = 0.06f

        // 合法区间与默认
        private const val SCALE_MIN = 0.6f
        private const val SCALE_MAX = 1.8f
        private const val RATIO_MIN = 0.5f
        private const val RATIO_MAX = 2.0f

        // —— 新增：仅用于字符进度映射的分数平滑与标点扩散参数（不影响分母/日志） —— //
        private const val MAP_FRAC_ALPHA = 0.12f
        private val PUNC_KERNEL = floatArrayOf(0.18f, 0.22f, 0.20f, 0.22f, 0.18f)
        private const val PUNC_EXTRA_SMALL = 0.70f
        private const val PUNC_EXTRA_BIG = 1.02f

        // —— 新增：弱网下的“当前句在线超时”与 loading 防抖 —— //
        private const val CURRENT_SENTENCE_ONLINE_TIMEOUT_MS = 10000L
        private const val LOADING_DEBOUNCE_MS = 200L

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
        val onlineApi = WxReaderApi(context)
        val mp3Decoder = MediaCodecMp3Decoder(context.applicationContext)
        val ttsCache = TtsCacheImpl(context.applicationContext)
        ttsRepository = TtsRepository(onlineApi, mp3Decoder, ttsCache)

        appScope.launch { commandProcessor() }
        if (instanceCount.incrementAndGet() == 1) {
            nativeEngine = SynthesizerNative()
            runCatching {
                nativeEngine?.init(voiceDataPath.toByteArray())
            }.onFailure {
                AppLogger.e(TAG, "TtsSynthesizer 初始化本地引擎失败: ${it.message}", it, important = true)
                currentCallback?.onInitialized(false)
            }.onSuccess {
                AppLogger.i(TAG, "TtsSynthesizer 本地引擎初始化成功。", important = true)
                currentCallback?.onInitialized(true)
            }
        }
        //sendCommand(Command.SetCallback(null))
    }

    // ============ 公共 API ============
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

    /**
     * 新增：对外 seek API（按句跳转）
     */
    fun seekToSentence(index: Int) = sendCommand(Command.SeekTo(index))

    fun setToken(token: String, uid: Int) {
        ttsRepository.onlineApi.setToken(token, uid)
    }

    fun isSpeaking(): Boolean = isPlaying.value
    fun getStatus(): TtsStatus {
        val i = playingSentenceIndex.coerceAtMost(sentences.size - 1).coerceAtLeast(0)
        val currentSentence = if (sentences.isNotEmpty() && i in sentences.indices) sentences[i].text else ""
        return TtsStatus(currentState, sentences.size, playingSentenceIndex, currentSentence)
    }
    private fun sendCommand(command: Command) { commandChannel.trySend(command) }

    // ============ Actor 命令处理 ============
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
                is Command.SetCallback -> {
                    currentCallback = command.callback;
                    currentCallback?.onInitialized(true)
                }
                is Command.InternalSentenceStart -> {
                    playingSentenceIndex = command.index
                    currentCallback?.onSentenceStart(
                        sentenceIndex = command.index,
                        sentence = command.sentence,
                        totalSentences = sentences.size,
                        mode = command.mode,
                        startPos = command.startPos,
                        endPos = command.endPos,
                    )
                    AppLogger.d(TAG, "修改句子索引为 ${command.index}: ${command.sentence}")
                    // 句首立即给一次 0 位回调，改善可见联动
                    currentCallback?.onSentenceProgressChanged(
                        sentenceIndex = command.index,
                        sentence = command.sentence,
                        progress = 0,
                        char = "",
                        startPos = command.startPos,
                        endPos = command.endPos,
                    )
                }
                is Command.InternalSentenceEnd -> {
                    onSentenceFinishedForEwma(command.index, command.sentence)
                    currentCallback?.onSentenceComplete(command.index, command.sentence)
                    if (upgradeWindowActive && command.index == upgradeProtectedIndex) {
                        AppLogger.i(TAG, "受保护句 #$upgradeProtectedIndex 已结束，关闭升级窗口。")
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
                    AppLogger.e(TAG, "收到内部错误，将执行 handleStop: ${command.message}")
                    handleStop()
                    currentCallback?.onError(command.message)
                }
                is Command.SeekTo -> handleSeekTo(command.index)
            }
        }
    }

    // ============ 会话作用域管理（B） ============
    /**
     * 创建新的会话作用域（SupervisorJob），并取消旧会话。
     * 所有会话内子任务依赖协程取消语义自动退出，尽量不在业务处散落判断。
     */
    private fun newSessionScope(): CoroutineScope {
        val old = sessionJob
        sessionJob = SupervisorJob()
        old?.cancel()
        cancelBuffering()
        AppLogger.i(TAG, "创建新的 SessionScope，旧会话已取消。")
        return CoroutineScope(Dispatchers.Default + sessionJob!!)
    }

    private fun isSessionActive(): Boolean = sessionJob?.isActive == true

    // ============ 命令实现 ============
    private suspend fun handleSpeak(text: String) {
        if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
            AppLogger.d(TAG, "已有语音在播放中，将先停止当前任务再开始新的任务。")
            handleStop()
        }
        cancelBuffering()
        isPausedByError = false
        pendingChanges.clear()
        pendingSeekIndex = null
        pendingSeekScheduled = false
        sentences.clear()

        val result = when (splitterStrategy) {
            SentenceSplitterStrategy.NEWLINE -> SentenceSplitter.sentenceSplitListByLine(text)
            SentenceSplitterStrategy.PUNCTUATION -> SentenceSplitter.sentenceSplitList(text)
        }
        if (result.isEmpty()) {
            AppLogger.w(TAG, "提供的文本中未找到有效句子。", important = true)
            currentCallback?.onError("文本中没有有效的句子")
            return
        }

        // 将分句结果构造成 TtsBag 队列
        var cursor = 0
        result.forEachIndexed { idx, seg ->
            val start = cursor
            val end = cursor + seg.length
            sentences.add(
                TtsBag(
                    text = seg,
                    index = idx,
                    utteranceId = "utt_$idx",
                    start = start,
                    end = end
                )
            )
            cursor = end
        }

        // 清理句级观测缓存（EWMA 的比例字典保留，用于跨会话自适应）
        predictedSamplesPerSentence.clear()
        actualSamplesPerSentence.clear()
        bucketKeyPerSentence.clear()
        sourcePerSentence.clear()
        boostedPredictedSentences.clear()

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
            AppLogger.d(TAG, "setSpeed: 与当前速度一致($newSpeed)，忽略。")
            return
        }
        currentSpeed = newSpeed
        AppLogger.i(TAG, "setSpeed: 设定新速度=$newSpeed")

        when (currentState) {
            TtsPlaybackState.PLAYING -> {
                processorMutex.withLock { onlineAudioProcessor?.setSpeed(newSpeed) }
                AppLogger.i(TAG, "播放中修改速度，执行软重启以立即生效。")
                softRestart()
            }
            TtsPlaybackState.PAUSED -> {
                val first = pendingChanges.add(PendingChange.SPEED)
                AppLogger.i(TAG, "暂停中修改速度，记录待应用变更（首次=$first），恢复时从当前句开头用新速度播放。")
                if (first && !pendingSeekScheduled) scheduleParamRestartWhilePaused("setSpeed")
            }
            else -> AppLogger.i(TAG, "IDLE 状态修改速度，将在下一次 speak 生效。")
        }
    }

    private suspend fun handleSetSpeaker(speaker: Speaker) {
        if (speaker == currentSpeaker) {
            AppLogger.d(TAG, "setVoice: 与当前 speaker 相同，忽略。")
            return
        }
        currentSpeaker = speaker

        when (currentState) {
            TtsPlaybackState.PLAYING -> {
                AppLogger.i(TAG, "播放中切换 speaker，执行软重启以立即生效。")
                softRestart()
            }
            TtsPlaybackState.PAUSED -> {
                val first = pendingChanges.add(PendingChange.SPEAKER)
                AppLogger.i(TAG, "暂停中切换 speaker，记录待应用变更（首次=$first），恢复时从当前句开头用新 speaker 播放。")
                if (first && !pendingSeekScheduled) scheduleParamRestartWhilePaused("setVoice")
            }
            else -> AppLogger.i(TAG, "当前状态($currentState)切换 speaker，等待下一次 speak 生效。")
        }
    }

    private fun handleSetVolume(volume: Float) {
        val newVolume = volume.coerceIn(0.0f, 1.0f)
        if (currentVolume == newVolume) {
            AppLogger.d(TAG, "setVolume: 与当前音量一致($newVolume)，忽略。")
            return
        }
        currentVolume = newVolume
        audioPlayer.setVolume(newVolume)
        AppLogger.i(TAG, "setVolume: 音量已设定为 $newVolume")
    }

    private fun handlePause() {
        if (currentState != TtsPlaybackState.PLAYING && currentState != TtsPlaybackState.PAUSED && currentState != TtsPlaybackState.BUFFERING) {
            AppLogger.w(TAG, "无法暂停，当前状态为 $currentState")
            return
        }
        if (currentState == TtsPlaybackState.PAUSED) {
            AppLogger.d(TAG, "已经是暂停状态，无需再次操作。")
            return
        }
        cancelBuffering()
        if (isPausedByError) AppLogger.w(TAG, "因合成失败自动暂停。") else isPausedByError = false
        audioPlayer.pause()
        updateState(TtsPlaybackState.PAUSED)
        currentCallback?.onPaused()
    }

    /**
     * Resume：
     * - 若 pendingChanges 非空（暂停期间改了 speaker/speed）或 isPausedByError 为真或存在 pendingSeekIndex，
     *   则执行“参数感知软重启”：
     *   取消旧会话 -> 新建 SessionScope -> 取消旧合成循环 -> 清空播放器队列 ->
     *   将 synthesisSentenceIndex 回拨到 pendingSeekIndex ?: playingSentenceIndex -> startSynthesis() ->
     *   resume()，从“当前句开头”用新参数重新播。
     */
    private suspend fun handleResume() {
        if (currentState != TtsPlaybackState.PAUSED) {
            AppLogger.w(TAG, "无法恢复，当前状态为 $currentState, 而非 PAUSED。")
            return
        }

        val needParamRestart = pendingChanges.isNotEmpty()
        val needErrorRestart = isPausedByError
        val needSeekRestart = pendingSeekIndex != null

        if (needParamRestart || needErrorRestart || needSeekRestart) {
            AppLogger.i(TAG, "恢复前需要重启合成：pendingChanges=$pendingChanges, pendingSeekIndex=$pendingSeekIndex, isPausedByError=$needErrorRestart。")
            val restartIndex = pendingSeekIndex ?: playingSentenceIndex

            // 清标志
            pendingChanges.clear()
            pendingSeekIndex = null
            pendingSeekScheduled = false
            isPausedByError = false

            // 重置会话作用域（取消旧会话）
            newSessionScope()

            // 取消旧合成循环并等待退出
            synthesisJob?.cancelAndJoin()
            synthesisJob = null
            AppLogger.d(TAG, "恢复前：旧的合成任务已取消并结束。")

            // 清空播放队列，回拨到目标句索引
            audioPlayer.resetBlocking()
            AppLogger.d(TAG, "恢复前：播放器已同步重置（清空队列）。")
            synthesisSentenceIndex = restartIndex

            // 清理句级观测缓存，避免不同参数污染
            predictedSamplesPerSentence.clear()
            actualSamplesPerSentence.clear()
            bucketKeyPerSentence.clear()
            sourcePerSentence.clear()
            boostedPredictedSentences.clear()

            // 新会话下启动合成
            startSynthesis()
            AppLogger.d(TAG, "恢复前：新的合成任务已从索引 $synthesisSentenceIndex 处启动（将用新参数/seek 重新合成当前句）。")
        }

        updateState(TtsPlaybackState.PLAYING)
        audioPlayer.resume()
        currentCallback?.onResumed()
    }

    /**
     * Stop：硬关闸
     * - 取消旧会话（使所有会话内任务/回调自然失效）；
     * - 取消旧合成循环；
     * - 播放器 stop 并释放资源；
     * - 立刻置 IDLE 并后台释放处理器。
     */
    private suspend fun handleStop() {
        if (currentState == TtsPlaybackState.IDLE) return
        AppLogger.d(TAG, "开始执行 handleStop...")

        // 取消旧会话作用域（集中会话守卫）
        sessionJob?.cancel()
        cancelBuffering()

        isPausedByError = false
        pendingChanges.clear()
        pendingSeekIndex = null
        pendingSeekScheduled = false

        val jobToCancel = synthesisJob
        synthesisJob = null
        jobToCancel?.cancel()

        audioPlayer.stopAndReleaseBlocking()
        AppLogger.d(TAG, "播放器已完全停止并释放。")

        sentences.clear()
        upgradeWindowActive = false
        upgradeProtectedIndex = -1
        updateState(TtsPlaybackState.IDLE)
        AppLogger.d(TAG, "状态已置为 IDLE。")

        // 清理句级观测缓存（EWMA 比例表保留）
        predictedSamplesPerSentence.clear()
        actualSamplesPerSentence.clear()
        bucketKeyPerSentence.clear()
        sourcePerSentence.clear()
        boostedPredictedSentences.clear()

        appScope.launch {
            try {
                jobToCancel?.join()
                AppLogger.d(TAG, "旧合成任务已退出。")
            } catch (e: Exception) {
                AppLogger.w(TAG, "等待旧任务退出时异常: ${e.message}")
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
        currentCallback = null
    }

    /**
     * 软重启：
     * - 新建会话作用域（取消旧会话）；
     * - 取消并等待旧合成循环结束；
     * - 同步 Reset 播放器；
     * - 从当前句索引重启合成。
     */
    private suspend fun softRestart() {
        AppLogger.d(TAG, "软重启请求，句子索引: $playingSentenceIndex")
        val restartIndex = playingSentenceIndex

        // 重置会话作用域
        newSessionScope()

        synthesisJob?.cancelAndJoin()
        synthesisJob = null
        AppLogger.d(TAG, "旧的合成任务已取消并等待结束。")

        audioPlayer.resetBlocking()
        AppLogger.d(TAG, "播放器已同步重置。")

        // 清理句级观测缓存（EWMA 比例表保留）
        predictedSamplesPerSentence.clear()
        actualSamplesPerSentence.clear()
        bucketKeyPerSentence.clear()
        sourcePerSentence.clear()
        boostedPredictedSentences.clear()

        synthesisSentenceIndex = restartIndex
        startSynthesis()
        AppLogger.d(TAG, "新的合成任务已从索引 $restartIndex 处启动。")
    }

    // 暂停期：仅在“第一次新增变更项/seek”时执行预备重启（防抖）
    private suspend fun scheduleParamRestartWhilePaused(reason: String) {
        // 新会话（取消旧会话，集中守卫）
        newSessionScope()

        // 取消旧合成循环（后台等待退出）
        val job = synthesisJob
        synthesisJob = null
        job?.cancel()
        appScope.launch { try { job?.join() } catch (_: Exception) { } }

        // 清空播放队列
        audioPlayer.resetBlocking()
        AppLogger.d(TAG, "暂停中 $reason：已取消旧循环并清空播放队列，等待恢复时从当前句重启生效。")

        // 清理句级观测缓存
        predictedSamplesPerSentence.clear()
        actualSamplesPerSentence.clear()
        bucketKeyPerSentence.clear()
        sourcePerSentence.clear()
        boostedPredictedSentences.clear()
    }

    /**
     * 处理 seek 命令：
     * - 规范化目标句（跳过空句，方向优先向后，末尾向前）；
     * - PLAYING：立即生效（会话隔离、清队、从目标句重启合成），并立即更新 playingSentenceIndex；
     * - PAUSED：立即更新 playingSentenceIndex，并记录 pendingSeekIndex；
     *         仅第一次 seek 时清队（scheduleParamRestartWhilePaused 防抖），恢复时统一生效。
     */
    private suspend fun handleSeekTo(requestedIndex: Int) {
        if (sentences.isEmpty()) {
            AppLogger.w(TAG, "handleSeekTo: 当前没有可用句子，忽略 seek 请求(index=$requestedIndex)。")
            return
        }
        val target = resolveSeekIndex(requestedIndex)
        if (target == playingSentenceIndex) {
            AppLogger.i(TAG, "handleSeekTo: 目标句与当前句相同(index=$target)，按规则忽略。")
            return
        }

        when (currentState) {
            TtsPlaybackState.PLAYING -> {
                // 立即生效
                AppLogger.i(TAG, "handleSeekTo: 播放中收到 seek 到句子 #$target，立即切换。")
                playingSentenceIndex = target // 进度立即更新
                // 会话隔离与硬清队
                newSessionScope()
                synthesisJob?.cancelAndJoin()
                synthesisJob = null
                audioPlayer.resetBlocking()
                AppLogger.d(TAG, "handleSeekTo: 播放中，已取消旧任务并清空队列，准备从 #$target 重启。")

                // 清缓存
                predictedSamplesPerSentence.clear()
                actualSamplesPerSentence.clear()
                bucketKeyPerSentence.clear()
                sourcePerSentence.clear()
                boostedPredictedSentences.clear()

                synthesisSentenceIndex = target
                startSynthesis()
                AppLogger.d(TAG, "handleSeekTo: 新的合成任务已从索引 $target 启动。")
            }
            TtsPlaybackState.PAUSED -> {
                // 暂停：立即更新索引，延后生效，防抖清队
                AppLogger.i(TAG, "handleSeekTo: 暂停中收到 seek 到句子 #$target，将在恢复时生效，进度立即更新。")
                playingSentenceIndex = target // 进度立即更新
                pendingSeekIndex = target
                if (!pendingSeekScheduled) {
                    scheduleParamRestartWhilePaused("seekTo")
                    pendingSeekScheduled = true
                } else {
                    AppLogger.d(TAG, "handleSeekTo: 暂停期已预备重启过，本次 seek 不再重复清队。")
                }
            }
            else -> {
                AppLogger.w(TAG, "handleSeekTo: 当前状态($currentState)不支持立即 seek。建议在 speak 后或恢复/播放中使用。", important = true)
            }
        }
    }

    /**
     * 解析并归一化 seek 目标：
     * - 越界裁切；
     * - 若目标句为空：
     *   - 若不是最后一句：沿“向后”方向跳过连续空句，找到第一个非空句；
     *   - 否则（位于末尾）：沿“向前”方向跳过连续空句，找到第一个非空句；
     * - 若全为空（极端）：保持不动（返回 playingSentenceIndex），并打印警告。
     */
    private fun resolveSeekIndex(requestedIndex: Int): Int {
        if (sentences.isEmpty()) return 0
        val clamped = requestedIndex.coerceIn(0, sentences.size - 1)

        fun isEmptyAt(i: Int): Boolean {
            if (i !in sentences.indices) return true
            return sentences[i].text.trim().isEmpty()
        }

        if (!isEmptyAt(clamped)) return clamped

        // 优先向后（非最后一句时）
        if (clamped < sentences.lastIndex) {
            var i = clamped + 1
            while (i <= sentences.lastIndex && isEmptyAt(i)) i++
            if (i <= sentences.lastIndex) {
                AppLogger.d(TAG, "resolveSeekIndex: 目标为空句，按规则向后跳至第一个非空句 index=$i", important = true)
                return i
            }
        }

        // 向前寻找
        var j = (clamped - 1).coerceAtLeast(0)
        while (j >= 0 && isEmptyAt(j)) j--
        if (j >= 0) {
            AppLogger.d(TAG, "resolveSeekIndex: 目标为空句且位于末尾或后续也为空，向前跳至第一个非空句 index=$j", important = true)
            return j
        }

        // 全为空：保持现状
        AppLogger.w(TAG, "resolveSeekIndex: 文本全为空或无法找到非空句，保持当前索引不变 index=$playingSentenceIndex")
        return playingSentenceIndex
    }

    // ============ 合成逻辑 ============
    /**
     * 启动合成任务（在新会话作用域下运行）。
     * 并行子任务：
     * 1) 核心合成循环（launchSynthesisLoop）
     * 2) 网络状态哨兵（ONLINE_PREFERRED 下，网络坏->好时触发保护性升级）
     */
    private fun startSynthesis() {
        resetOnlineCooldown()
        val sessionScope = newSessionScope()

        synthesisJob = sessionScope.launch(Dispatchers.Default) {
            var synthesisLoopJob: Job? = null

            fun runSynthesisLoop() {
                synthesisLoopJob?.cancel()
                synthesisLoopJob = launchSynthesisLoop()
            }

            // ONLINE_PREFERRED：网络恢复进行在线升级
            if (strategyManager.currentStrategy == TtsStrategy.ONLINE_PREFERRED) {
                launch {
                    var wasNetworkBad = !strategyManager.isNetworkGood.value
                    strategyManager.isNetworkGood.collect { isNetworkGood ->
                        if (wasNetworkBad && isNetworkGood) {
                            // 稳定窗口去抖
                            delay(NETWORK_STABILIZE_MS)
                            if (!strategyManager.isNetworkGood.value) {
                                AppLogger.i(TAG, "网络恢复检测在稳定窗口后失效，取消本次升级触发。")
                                wasNetworkBad = !strategyManager.isNetworkGood.value
                                return@collect
                            }
                            if (!isSessionActive()) return@collect
                            if (upgradeWindowActive) {
                                AppLogger.i(TAG, "升级窗口仍在进行，忽略重复触发。")
                                wasNetworkBad = !strategyManager.isNetworkGood.value
                                return@collect
                            }
                            resetOnlineCooldown()
                            AppLogger.i(TAG, "网络已恢复且通过稳定窗口。执行升级：软重启合成循环并进入升级窗口（保护期）。")

                            synthesisLoopJob?.cancelAndJoin()

                            val protectedIndex = playingSentenceIndex
                            audioPlayer.resetQueueOnlyBlocking(preserveSentenceIndex = protectedIndex)

                            upgradeWindowActive = true
                            upgradeProtectedIndex = protectedIndex

                            synthesisSentenceIndex = protectedIndex + 1
                            if (synthesisSentenceIndex < sentences.size) {
                                AppLogger.i(TAG, "将从句子 $synthesisSentenceIndex 处重新开始在线合成（升级窗口生效中）。")
                                runSynthesisLoop()
                            } else {
                                AppLogger.i(TAG, "所有句子均已播放或正在播放，无需重启合成。")
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
     * 核心合成循环（依赖协程取消语义；边界统一调用 Guarded 入队与回调）。
     */
    private fun CoroutineScope.launchSynthesisLoop() = launch {
        var synthesisFailed = false
        try {
            while (coroutineContext.isActive && synthesisSentenceIndex < sentences.size && isSessionActive()) {
                val index = synthesisSentenceIndex
                val bag = sentences[index]
                val sessionStrategy = strategyManager.currentStrategy

                val finalResult = when (sessionStrategy) {
                    TtsStrategy.ONLINE_PREFERRED, TtsStrategy.ONLINE_ONLY -> {
                        val onlineResult = performOnlineSynthesis(index, bag)
                        if (onlineResult is SynthesisResult.Success) {
                            resetOnlineCooldown(); onlineResult
                        } else {
                            activateOnlineCooldown()
                            if (sessionStrategy == TtsStrategy.ONLINE_PREFERRED) {
                                AppLogger.w(TAG, "在线路径失败(缓存未命中/无PCM或API错误)，回退至[离线模式]。原因: ${(onlineResult as? SynthesisResult.Failure)?.reason ?: "unknown"}")
                                performOfflineSynthesis(index, bag)
                            } else {
                                AppLogger.e(TAG, "纯在线模式合成失败，无可用回退。原因: ${(onlineResult as? SynthesisResult.Failure)?.reason ?: "unknown"}")
                                onlineResult
                            }
                        }
                    }
                    else -> performOfflineSynthesis(index, bag)
                }

                when (finalResult) {
                    is SynthesisResult.Success -> {
                        AppLogger.d(TAG, "处理合成位置：synthesisSentenceIndex:$synthesisSentenceIndex, index:$index")
                        if (synthesisSentenceIndex == index) synthesisSentenceIndex++
                    }
                    is SynthesisResult.Deferred -> {
                        AppLogger.i(TAG, "句子 $index 合成被延后（通常因保护期/会话切换），将稍后重试。", important = true)
                        delay(200)
                    }
                    is SynthesisResult.Failure -> {
                        AppLogger.e(TAG, "句子 $index 合成最终失败 (策略: $sessionStrategy): ${finalResult.reason}")
                        synthesisFailed = true
                        break
                    }
                }
            }
        } catch (_: CancellationException) {
            AppLogger.d(TAG, "合成循环被取消。")
        } finally {
            val stillActive = coroutineContext.isActive && isSessionActive()
            if (stillActive) {
                var finalPcm: ShortArray? = null
                var finalSampleRate: Int? = null
                processorMutex.withLock {
                    if (onlineAudioProcessor != null) {
                        finalPcm = onlineAudioProcessor?.flush()
                        finalSampleRate = onlineAudioProcessor?.sampleRate
                        AppLogger.d(TAG, "Flushing audio processor, got ${finalPcm?.size ?: 0} final samples.")
                    }
                }

                finalPcm?.takeIf { it.isNotEmpty() && finalSampleRate != null }?.let { pcm ->
                    val lastIndex = (synthesisSentenceIndex - 1).coerceAtLeast(0)
                    // 统计最后一块在线样本（用于 EWMA）
                    actualSamplesPerSentence[lastIndex] = (actualSamplesPerSentence[lastIndex] ?: 0L) + pcm.size
                    enqueuePcmGuarded(
                        pcm = pcm,
                        sampleRate = finalSampleRate!!,
                        source = SynthesisMode.ONLINE,
                        sentenceIndex = lastIndex
                    )
                    endBufferingIfNeeded(lastIndex)
                }

                if (isSessionActive() && coroutineContext.isActive) {
                    AppLogger.i(TAG, "合成循环结束(活动会话)。发送EOS。失败标志: $synthesisFailed")
                    enqueueEndOfStreamGuarded {
                        if (synthesisFailed) {
                            isPausedByError = true
                            sendCommand(Command.Pause)
                        } else {
                            AppLogger.i(TAG, "所有句子正常合成完毕，等待播放结束...")
                        }
                    }
                }
            } else {
                AppLogger.i(TAG, "合成循环结束（会话已取消或协程不活跃），跳过 flush/EOS。")
            }
        }
    }

    /**
     * 在线合成（缓存优先）。
     * - 非空句但无 PCM => Failure（避免仅凭 Marker 推进）。
     * - 空句（trim 为空）仍 START/END => Success。
     * - 边界统一用 Guarded 方法入队与回调闭包，集中会话守卫。
     */
    private suspend fun performOnlineSynthesis(index: Int, bag: TtsBag): SynthesisResult {
        try {
            if (!coroutineContext.isActive || !isSessionActive()) return SynthesisResult.Deferred

            val sentence = bag.text
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) {
                AppLogger.w(TAG, "句子 $index 内容为空，跳过在线合成。", important = true)
                enqueueMarkerGuarded(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.ONLINE) {
                    if (isSessionActive()) sendCommand(Command.InternalSentenceStart(index, sentence, SynthesisMode.ONLINE, bag.start, bag.end))
                }
                enqueueMarkerGuarded(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.ONLINE) {
                    if (isSessionActive()) sendCommand(Command.InternalSentenceEnd(index, sentence))
                }
                return SynthesisResult.Success
            }
            AppLogger.d(TAG, "合成[在线]句子 $index: \"$trimmed\"", important = true)
            val start = System.currentTimeMillis()

            val isCoolingDown = System.currentTimeMillis() < onlineCooldownUntilTimestamp

            val strategy = strategyManager.currentStrategy
            val shouldBuffer = (strategy == TtsStrategy.ONLINE_PREFERRED || strategy == TtsStrategy.ONLINE_ONLY) &&
                    (index == playingSentenceIndex || index == playingSentenceIndex + 1)

            if (shouldBuffer) {
                scheduleBufferingIfNeeded(index)
            }

            val decoded = if (shouldBuffer) {
                withTimeout(CURRENT_SENTENCE_ONLINE_TIMEOUT_MS) {
                    ttsRepository.getDecodedPcm(trimmed, currentSpeaker, allowNetwork = !isCoolingDown)
                }
            } else {
                ttsRepository.getDecodedPcm(trimmed, currentSpeaker, allowNetwork = !isCoolingDown)
            }

            if (!coroutineContext.isActive || !isSessionActive()) return SynthesisResult.Deferred

            val pcmData = decoded.pcmData
            val sampleRate = decoded.sampleRate
            if (pcmData.isEmpty()) {
                val reason = "在线合成未产出PCM（非空句），index=$index"
                AppLogger.w(TAG, reason, important = true)
                return SynthesisResult.Failure(reason)
            }

            val duration = System.currentTimeMillis() - start
            AppLogger.d(TAG, "在线合成句子 $index 成功，PCM 大小=${pcmData.size}, 采样率=$sampleRate, 耗时：$duration ms, 句子：$trimmed", important = true)

            // 在线：不设置预测分母，不加入 predictedSamplesPerSentence（方案A）
            enqueueMarkerGuarded(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.ONLINE) {
                if (isSessionActive()) sendCommand(Command.InternalSentenceStart(index, sentence, SynthesisMode.ONLINE, startPos = bag.start, endPos = bag.end))
            }

            processorMutex.withLock {
                if (onlineAudioProcessor == null || onlineAudioProcessor?.sampleRate != sampleRate) {
                    onlineAudioProcessor?.release()
                    onlineAudioProcessor = AudioSpeedProcessor(sampleRate)
                    onlineAudioProcessor?.setSpeed(currentSpeed)
                    AppLogger.i(TAG, "Online audio sample rate is $sampleRate, created new AudioSpeedProcessor.")
                } else {
                    onlineAudioProcessor?.setSpeed(currentSpeed)
                }

                val speedAdjustedPcm = onlineAudioProcessor?.process(pcmData) ?: pcmData
                if (speedAdjustedPcm.isNotEmpty()) {
                    actualSamplesPerSentence[index] = (actualSamplesPerSentence[index] ?: 0L) + speedAdjustedPcm.size
                    enqueuePcmGuarded(
                        pcm = speedAdjustedPcm,
                        sampleRate = sampleRate,
                        source = SynthesisMode.ONLINE,
                        sentenceIndex = index
                    )
                    endBufferingIfNeeded(index)
                }
            }

            enqueueMarkerGuarded(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.ONLINE) {
                if (isSessionActive()) sendCommand(Command.InternalSentenceEnd(index, sentence))
            }
            return SynthesisResult.Success
        } catch (e: WxApiException) {
            val code = e.errorCode
            val reason = "合成[在线] (句子 $index)失败: ${e.message}, code=$code"
            AppLogger.e(TAG, reason, important = true)
            return SynthesisResult.Failure(reason)
        } catch (e: Exception) {
            val reason = "合成[在线] (句子 $index)失败: ${e.message}"
            AppLogger.e(TAG, reason, important = true)
            return SynthesisResult.Failure(reason)
        }
    }

    /**
     * 离线合成。
     * - 保护期且目标句不是受保护句：返回 Deferred（不上产、不推进）；
     * - 边界统一用 Guarded 方法入队与回调闭包，集中会话守卫。
     *
     * 保持流式分块入队，但在句首先设置“预测总样本数”，稳定分母，避免进度回退。
     */
    private suspend fun performOfflineSynthesis(index: Int, bag: TtsBag): SynthesisResult {
        if (!coroutineContext.isActive || !isSessionActive()) return SynthesisResult.Deferred
        if (audioPlayer.isInProtection() && index != audioPlayer.getProtectedSentenceIndex()) {
            AppLogger.i(TAG, "离线合成请求被延后：当前处于保护期，受保护句=${audioPlayer.getProtectedSentenceIndex()}，请求句=$index")
            return SynthesisResult.Deferred
        }

        val sentence = bag.text
        return engineMutex.withLock {
            try {
                val trimmed = sentence.trim()
                if (trimmed.isEmpty()) {
                    AppLogger.w(TAG, "句子 $index 内容为空，跳过离线合成。", important = true)
                    return@withLock SynthesisResult.Success
                }
                if (!coroutineContext.isActive || !isSessionActive()) {
                    AppLogger.i(TAG, "离线合成开始前会话不活跃/已取消，index=$index -> Deferred")
                    return@withLock SynthesisResult.Deferred
                }

                AppLogger.d(TAG, "合成[离线]句子 $index: \"$trimmed\"", important = true)

                // 自适应预测（离线）
                val predicted = predictTotalSamplesScaled(
                    text = trimmed,
                    sampleRate = TtsConstants.DEFAULT_SAMPLE_RATE,
                    speed = currentSpeed,
                    preferOnline = false
                )
                predictedSamplesPerSentence[index] = predicted
                bucketKeyPerSentence[index] = currentBucketKey()
                sourcePerSentence[index] = SynthesisMode.OFFLINE
                boostedPredictedSentences.remove(index)
                audioPlayer.setPredictedTotalSamples(index, predicted)

                val prepare = prepareForSynthesis(trimmed, currentSpeed, currentVolume)
                if (prepare != 0) {
                    val reason = "合成[离线]句子准备失败 (code=$prepare) 句子: $trimmed"
                    AppLogger.e(TAG, "prepare 失败：$reason（按成功跳过处理，避免打断整体流程）")
                    return@withLock SynthesisResult.Success
                }

                val startCb = { if (isSessionActive()) sendCommand(Command.InternalSentenceStart(index, trimmed, SynthesisMode.OFFLINE, bag.start, bag.end)) }
                val endCb = { if (isSessionActive()) sendCommand(Command.InternalSentenceEnd(index, trimmed)) }

                val synthResult = IntArray(1)
                val pcmArray = pcmBuffer.array()
                var hasStart = false
                while (coroutineContext.isActive && isSessionActive()) {
                    if (audioPlayer.isInProtection() && index != audioPlayer.getProtectedSentenceIndex()) {
                        AppLogger.i(TAG, "离线合成过程中进入/仍在保护期，句子 $index 延后（Deferred）。", important = true)
                        return@withLock SynthesisResult.Deferred
                    }

                    val status = nativeEngine?.synthesize(pcmArray, TtsConstants.PCM_BUFFER_SIZE, synthResult, 1) ?: -1
                    if (status == -1) {
                        val reason = "合成[离线]句子合成失败，状态码: -1"
                        AppLogger.e(TAG, reason, important = true)
                        return@withLock SynthesisResult.Success
                    }
                    val num = synthResult[0]
                    if (num <= 0) break
                    if (!hasStart) {
                        enqueueMarkerGuarded(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.OFFLINE, startCb)
                        hasStart = true
                    }
                    val valid = num.coerceAtMost(pcmArray.size)
                    if (valid > 0) {
                        val chunk = pcmArray.copyOf(valid)
                        // 统计离线入队样本（用于 EWMA）
                        actualSamplesPerSentence[index] = (actualSamplesPerSentence[index] ?: 0L) + chunk.size
                        // 句中动态抬分母（渐进式）
                        maybeBoostPredictedInFlight(index, TtsConstants.DEFAULT_SAMPLE_RATE)
                        enqueuePcmGuarded(
                            pcm = chunk,
                            sampleRate = TtsConstants.DEFAULT_SAMPLE_RATE,
                            source = SynthesisMode.OFFLINE,
                            sentenceIndex = index
                        )
                        endBufferingIfNeeded(index)
                    }
                    delay(1)
                }
                AppLogger.d(TAG, "离线合成句子 $index 完成。 句子：$trimmed", important = true)
                if (coroutineContext.isActive && isSessionActive()) {
                    if (!hasStart) {
                        enqueueMarkerGuarded(index, AudioPlayer.MarkerType.SENTENCE_START, SynthesisMode.OFFLINE, startCb)
                    }
                    enqueueMarkerGuarded(index, AudioPlayer.MarkerType.SENTENCE_END, SynthesisMode.OFFLINE, endCb)
                }
                SynthesisResult.Success
            } catch (e: CancellationException) {
                SynthesisResult.Failure("合成[离线](句子 $index, ${sentence.trim()})协程被取消")
            } catch (e: Exception) {
                val reason = "合成[离线](句子 $index, ${sentence.trim()})异常: ${e.message}"
                AppLogger.e(TAG, reason, e, important = true)
                SynthesisResult.Failure(reason)
            } finally {
                nativeEngine?.reset()
            }
        }
    }

    // ============ “Guarded” 边界封装（方案 A：集中会话守卫） ============
    private suspend fun enqueuePcmGuarded(
        pcm: ShortArray,
        sampleRate: Int,
        source: SynthesisMode,
        sentenceIndex: Int
    ) {
        if (!isSessionActive() || !coroutineContext.isActive) return
        audioPlayer.enqueuePcm(
            pcm = pcm,
            offset = 0,
            length = pcm.size,
            sampleRate = sampleRate,
            source = source,
            sentenceIndex = sentenceIndex
        )
    }

    private suspend fun enqueueMarkerGuarded(
        sentenceIndex: Int,
        type: AudioPlayer.MarkerType,
        source: SynthesisMode,
        onReached: (() -> Unit)? = null
    ) {
        if (!isSessionActive() || !coroutineContext.isActive) return
        audioPlayer.enqueueMarker(sentenceIndex, type, source, onReached)
    }

    private suspend fun enqueueEndOfStreamGuarded(onDrained: () -> Unit) {
        if (!isSessionActive() || !coroutineContext.isActive) return
        audioPlayer.enqueueEndOfStream(onDrained)
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

            // 启停字符进度轮询
            when (newState) {
                TtsPlaybackState.PLAYING -> startCharacterProgressLoop()
                else -> stopCharacterProgressLoop()
            }
        }
    }

    private fun startCharacterProgressLoop() {
        if (characterProgressJob?.isActive == true) return
        characterProgressJob = appScope.launch(Dispatchers.Default) {
            var lastSentence = -1
            var lastCharIdx = -1
            while (isActive && currentState == TtsPlaybackState.PLAYING) {
                val progress = audioPlayer.getCurrentSentenceProgress()
                if (progress != null) {
                    val sIdx = progress.sentenceIndex
                    val bag = sentences.getOrNull(sIdx) ?: continue
                    val text = bag.text
                    val charCount = text.length // 如需去空白可用 text.trim().length
                    if (charCount > 0) {
                        // —— 新增：仅用于“字符进度映射”的分数轻量平滑（不改底层进度与日志） —— //
                        val rawFrac = progress.fraction.coerceIn(0f, 1f)
                        val frac = if (sIdx != mapSmoothLastSentence) {
                            mapSmoothLastSentence = sIdx
                            mapSmoothFrac = rawFrac
                            rawFrac
                        } else {
                            mapSmoothFrac = mapSmoothFrac * (1f - MAP_FRAC_ALPHA) + rawFrac * MAP_FRAC_ALPHA
                            mapSmoothFrac
                        }

                        // 将比例映射为 [0, charCount-1] 的索引（标点邻域扩散，缓解“到标点突然快一截”）
                        val idx = mapFractionToWeightedIndex(text, frac)
                        if (sIdx != lastSentence || idx != lastCharIdx) {
                            currentCallback?.onSentenceProgressChanged(
                                sentenceIndex = sIdx,
                                sentence = text,
                                progress = idx,
                                char = text.getOrNull(idx)?.toString() ?: "",
                                startPos = bag.start,
                                endPos = bag.end
                            )
                            lastSentence = sIdx
                            lastCharIdx = idx
                        }
                    } else {
                        // 空句：固定回调 0
                        if (sIdx != lastSentence || lastCharIdx != 0) {
                            currentCallback?.onSentenceProgressChanged(
                                sentenceIndex = sIdx,
                                sentence = "",
                                progress = 0,
                                char = "",
                                startPos = bag.start,
                                endPos = bag.end
                            )
                            lastSentence = sIdx
                            lastCharIdx = 0
                        }
                    }
                }
                delay(CHAR_PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopCharacterProgressLoop() {
        val job = characterProgressJob
        characterProgressJob = null
        job?.cancel()
    }

    /**
     * 将播放比例映射到字符索引：对标点/停顿加权，缓解“线性比例”的系统性误差（离线更明显）。
     * 可按需要微调各类字符的权重。
     */
    private fun mapFractionToWeightedIndex(text: String, fraction: Float): Int {
        if (text.isEmpty()) return 0

        // 基础权重（保留现有规则）
        val weights = FloatArray(text.length) { i -> charWeight(text[i]) }

        // —— 新增：标点邻域权重扩散（避免到标点处一下子跳太多） —— //
        for (i in text.indices) {
            val c = text[i]
            val extra = when {
                c in charArrayOf('，','、','；','：',',',';') -> PUNC_EXTRA_SMALL
                c in charArrayOf('。','！','？','!','?','…') -> PUNC_EXTRA_BIG
                else -> 0f
            }
            if (extra > 0f) {
                // 将额外权重按核 [-2..+2] 分配到标点及其邻居
                for (k in -2..2) {
                    val j = i + k
                    if (j in text.indices) {
                        weights[j] += extra * PUNC_KERNEL[k + 2]
                    }
                }
            }
        }

        val total = weights.sum().coerceAtLeast(1e-3f)
        val target = total * fraction.coerceIn(0f, 1f)
        var acc = 0f
        for (i in weights.indices) {
            acc += weights[i]
            if (acc >= target) return i
        }
        return text.lastIndex
    }

    private fun charWeight(c: Char): Float {
        return when {
            c == ' ' || c == '\t' || c == '\u3000' -> 0.4f
            c in charArrayOf('，','、','；','：',',',';') -> 1.5f
            c in charArrayOf('。','！','？','!','?') -> 2.0f
            c == '—' || c == '-' -> 1.2f
            c.isDigit() || c.isLetter() -> 1.1f
            else -> 1.0f
        }
    }

    private fun activateOnlineCooldown() {
        onlineFailureCount++
        val backoffFactor = (1 shl (onlineFailureCount - 1).coerceAtMost(5)).toLong()
        val cooldownDuration = (BASE_COOLDOWN_MS * backoffFactor).coerceAtMost(MAX_COOLDOWN_MS)
        onlineCooldownUntilTimestamp = System.currentTimeMillis() + cooldownDuration
        AppLogger.i(TAG, "在线合成失败次数: $onlineFailureCount。激活冷却期 ${cooldownDuration}ms。")
    }

    private fun resetOnlineCooldown() {
        if (onlineFailureCount > 0 || onlineCooldownUntilTimestamp > 0L) {
            AppLogger.i(TAG, "在线合成恢复正常或被重置。清除失败计数和冷却期。")
        }
        onlineFailureCount = 0
        onlineCooldownUntilTimestamp = 0L
    }

    fun getVoiceDataPath(): String = voiceDataPath

    fun clearCache() {
        if (currentState != TtsPlaybackState.IDLE) {
            AppLogger.w(TAG, "clearCache: 当前状态非 IDLE，清理缓存可能影响正在进行的合成任务。停止后再操作")
            Toast.makeText(context, "请在停止合成后再清理缓存", Toast.LENGTH_SHORT).show()
            return
        }
        ttsRepository.clearCache()
    }

    // ================= 文本 → 预测总样本数（含 EWMA 比例） =================

    private data class PredProfile(
        val unitMs: Float,
        val interceptMs: Float,
        val minMs: Float,
        val commaMs: Float,
        val periodMs: Float,
        val scaleFactor: Float,
        val midBoostRatio: Float,
        val midBoostMult: Float,
        val midBoostStepMs: Float,
        val midBoostMinMs: Float
    )

    private fun speedCompress(value: Float, speed: Float, pow: Float): Float {
        val s = speed.coerceIn(0.5f, 3.0f)
        return value / s.pow(pow)
    }

    private fun lowBoost(s: Float, max: Float): Float {
        if (s >= 1f) return 0f
        val t = (1f - s).pow(LOW_BOOST_POW)
        return max * t
    }

    private fun buildPredProfile(speed: Float): PredProfile {
        val s = speed.coerceIn(0.5f, 3.0f)
        var unit    = speedCompress(BASE_UNIT_MS,      s, POW_UNIT)
        var intercept = speedCompress(BASE_INTERCEPT_MS, s, POW_INTERCEPT)
        var minMs   = speedCompress(BASE_MIN_MS,      s, POW_MIN)
        var comma   = speedCompress(BASE_COMMA_MS,    s, POW_COMMA)
        var period  = speedCompress(BASE_PERIOD_MS,   s, POW_PERIOD)

        var rawScale = speedCompress(DEFAULT_INIT_SCALE_BASE, s, POW_SCALE)

        // 低速附加放大（仅 s<1.0 生效）
        unit     *= (1f + lowBoost(s, LOW_BOOST_UNIT_MAX))
        intercept*= (1f + lowBoost(s, LOW_BOOST_INTERCEPT_MAX))
        minMs    *= (1f + lowBoost(s, LOW_BOOST_MIN_MAX))
        comma    *= (1f + lowBoost(s, LOW_BOOST_COMMA_MAX))
        period   *= (1f + lowBoost(s, LOW_BOOST_PERIOD_MAX))
        rawScale *= (1f + lowBoost(s, LOW_BOOST_SCALE_MAX))

        // 高速时降低整体比例的下限，避免被冷启动比例拖慢
        val minScale = when {
            s >= 1.8f -> 1.05f
            s >= 1.5f -> 1.08f
            s >= 1.3f -> 1.10f
            else      -> 1.15f
        }
        val scaleClamped = rawScale.coerceIn(minScale, 1.40f)

        val midRatio = (MID_BOOST_RATIO_BASE + (s - 1f) * 0.01f).coerceIn(1.03f, 1.08f)
        val midMult  = (MID_BOOST_MULT_BASE - (s - 1f) * 0.02f).coerceIn(1.12f, MID_BOOST_MULT_BASE)
        val midStep  = (MID_BOOST_STEP_MS_BASE - (s - 1f) * 30f).coerceIn(180f, MID_BOOST_STEP_MS_BASE)
        val midMin   = (MID_BOOST_MIN_MS_BASE - (s - 1f) * 40f).coerceIn(380f, MID_BOOST_MIN_MS_BASE)

        return PredProfile(unit, intercept, minMs, comma, period, scaleClamped, midRatio, midMult, midStep, midMin)
    }

    private fun predictTotalSamplesScaled(text: String, sampleRate: Int, speed: Float, preferOnline: Boolean): Long {
        val raw = predictTotalSamplesRaw(text, sampleRate, speed)
        val profile = buildPredProfile(speed)
        val scaled = (raw.toDouble() * profile.scaleFactor.toDouble()).toLong()
        return scaled.coerceAtLeast(1L)
    }

    private fun predictTotalSamplesRaw(text: String, sampleRate: Int, speed: Float): Long {
        val trimmed = text.trim()
        val profile = buildPredProfile(speed)
        if (trimmed.isEmpty()) {
            return (sampleRate * profile.minMs / 1000f).toLong().coerceAtLeast(1L)
        }
        var sumW = 0f
        var commaCnt = 0
        var periodCnt = 0
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            when {
                c == ' ' || c == '\t' || c == '\u3000' -> sumW += 0.2f
                c in charArrayOf('，','、','；','：',',',';') -> { commaCnt++; sumW += 0.4f }
                // 将“……”或“...”合并为一次句末停顿
                c == '。' || c == '！' || c == '？' || c == '!' || c == '?' -> { periodCnt++; sumW += 0.6f }
                c == '…' -> {
                    var j = i + 1
                    while (j < trimmed.length && trimmed[j] == '…') j++
                    periodCnt += 1
                    sumW += 0.8f
                    i = j - 1
                }
                c == '.' -> {
                    var j = i + 1; var dots = 1
                    while (j < trimmed.length && trimmed[j] == '.') { dots++; j++ }
                    if (dots >= 3) { periodCnt += 1; sumW += 0.8f; i = j - 1 }
                    else sumW += 0.5f
                }
                c.isDigit() -> sumW += 0.8f
                c.isLetter() -> sumW += 0.6f
                else -> sumW += 1.0f // 汉字/CJK
            }
            i++
        }
        var estMs = profile.interceptMs +
                profile.unitMs * sumW +
                commaCnt * profile.commaMs +
                periodCnt * profile.periodMs
        if (estMs < profile.minMs) estMs = profile.minMs
        return (sampleRate * estMs / 1000f).toLong().coerceAtLeast(1L)
    }

    private fun currentBucketKey(): String {
        val spk = currentSpeaker.offlineModelName
        val bucket = String.format("%.1f", currentSpeed.coerceIn(0.5f, 3.0f))
        return "$spk|$bucket"
    }

    private fun getCurrentBucketScale(preferOnline: Boolean): Float {
        val key = currentBucketKey()
        val overall = loadScaleOverall(key) ?: DEFAULT_INIT_SCALE
        return overall.coerceIn(SCALE_MIN, SCALE_MAX)
    }

    private fun loadScaleOverall(bucket: String): Float? {
        scaleOverallByBucket[bucket]?.let { return it }
        val v = ewmaPrefs.getFloat("scale_overall_$bucket", Float.NaN)
        return if (v.isNaN()) null else {
            val c = v.coerceIn(SCALE_MIN, SCALE_MAX)
            scaleOverallByBucket[bucket] = c
            c
        }
    }
    private fun saveScaleOverall(bucket: String, value: Float) {
        scaleOverallByBucket[bucket] = value
        ewmaPrefs.edit { putFloat("scale_overall_$bucket", value)}
    }

    private fun loadCountOverall(bucket: String): Int? {
        val v = ewmaPrefs.getInt("scale_overall_cnt_$bucket", -1)
        return if (v < 0) null else v
    }
    private fun saveCountOverall(bucket: String, value: Int) {
        countOverallByBucket[bucket] = value
        ewmaPrefs.edit { putInt("scale_overall_cnt_$bucket", value) }
    }

    private fun maybeBoostPredictedInFlight(index: Int, sampleRate: Int) {
        val actual = actualSamplesPerSentence[index] ?: return
        val predicted = predictedSamplesPerSentence[index] ?: return
        val profile = buildPredProfile(currentSpeed)
        val minSamples = (sampleRate * (profile.midBoostMinMs / 1000f)).toLong()
        if (actual < minSamples) return
        if (actual > (predicted * profile.midBoostRatio)) {
            val target = (actual * profile.midBoostMult).toLong()
            val step = (sampleRate * (profile.midBoostStepMs / 1000f)).toLong().coerceAtLeast(1L)
            val next = min(target, predicted + step)
            if (next > predicted) {
                predictedSamplesPerSentence[index] = next
                audioPlayer.setPredictedTotalSamples(index, next)
            }
        }
    }

    private fun onSentenceFinishedForEwma(index: Int, sentence: String) {
        val predicted = predictedSamplesPerSentence.remove(index)
        val actual = actualSamplesPerSentence.remove(index)
        val bucket = bucketKeyPerSentence.remove(index)
        val source = sourcePerSentence.remove(index)
        boostedPredictedSentences.remove(index)
        if (predicted == null || actual == null || bucket == null || source == null) return
        if (predicted <= 0L || actual <= 0L) return

        // 在线句直接跳过（在线不参与比例更新）
        if (source == SynthesisMode.ONLINE) return

        val ratioRaw = (actual.toDouble() / predicted.toDouble()).toFloat()
        val ratio = ratioRaw.coerceIn(RATIO_MIN, RATIO_MAX)
        val overallBase = loadScaleOverall(bucket) ?: DEFAULT_INIT_SCALE
        val overallCnt = (countOverallByBucket[bucket] ?: loadCountOverall(bucket) ?: 0)

        val alphaOverall = ALPHA_OFFLINE
        val newOverall = ((1f - alphaOverall) * overallBase + alphaOverall * ratio).coerceIn(SCALE_MIN, SCALE_MAX)
        scaleOverallByBucket[bucket] = newOverall
        saveScaleOverall(bucket, newOverall)
        val newOverallCnt = overallCnt + 1
        countOverallByBucket[bucket] = newOverallCnt
        saveCountOverall(bucket, newOverallCnt)
    }

    // —— 新增：BUFFERING 辅助 —— //
    private fun scheduleBufferingIfNeeded(index: Int) {
        bufferingJob?.cancel()
        bufferingSentenceIndex = index
        bufferingJob = appScope.launch(Dispatchers.Default) {
            delay(LOADING_DEBOUNCE_MS)
            if (!isSessionActive()) return@launch
            if (currentState == TtsPlaybackState.PLAYING && bufferingSentenceIndex == index) {
                updateState(TtsPlaybackState.BUFFERING)
            }
        }
    }

    private fun endBufferingIfNeeded(index: Int) {
        val target = bufferingSentenceIndex
        if (target != null && target == index) {
            bufferingJob?.cancel()
            bufferingJob = null
            bufferingSentenceIndex = null
            if (currentState == TtsPlaybackState.BUFFERING) {
                updateState(TtsPlaybackState.PLAYING)
            }
        }
    }

    private fun cancelBuffering() {
        bufferingJob?.cancel()
        bufferingJob = null
        bufferingSentenceIndex = null
        if (currentState == TtsPlaybackState.BUFFERING) {
            updateState(TtsPlaybackState.PLAYING)
        }
    }
}