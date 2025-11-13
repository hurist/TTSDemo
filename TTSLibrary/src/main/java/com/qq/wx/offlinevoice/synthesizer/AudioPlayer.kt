package com.qq.wx.offlinevoice.synthesizer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * 流式 TTS 播放器，支持软/硬重置与在线/离线混播。
 *
 * 稳定性与顺序修复：
 * - 保护期：软重置(保留当前句)后，仅允许该句的离线数据进入并播放；其他离线数据直接丢弃；
 *   在线数据按“句子分桶 + 有音频优先”的策略暂存，保护句播放彻底结束后按句序吐回：
 *   仅当句子至少包含一个 PCM 时才触发 START/PCM/END，避免“只有 Marker 导致的索引空推进”与乱序。
 * - 暂停/恢复可靠性：暂停状态下“不消费 PCM 队列”，仅消费控制命令，确保 reset/软重置的 ack 不会卡死；
 *   写 PCM 的循环内也支持控制命令抢占与暂停等待。
 */
class AudioPlayer(
    private val initialSampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE,
    private val queueCapacity: Int = 256
) {
    enum class MarkerType { SENTENCE_START, SENTENCE_END }

    private sealed class QueueItem(open val gen: Long) {
        data class Pcm(
            override val gen: Long,
            val data: ShortArray,
            val offset: Int = 0,
            val length: Int = data.size,
            val sampleRate: Int,
            val source: SynthesisMode,
            val sentenceIndex: Int
        ) : QueueItem(gen) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Pcm

                if (gen != other.gen) return false
                if (offset != other.offset) return false
                if (length != other.length) return false
                if (sampleRate != other.sampleRate) return false
                if (sentenceIndex != other.sentenceIndex) return false
                if (!data.contentEquals(other.data)) return false
                if (source != other.source) return false

                return true
            }

            override fun hashCode(): Int {
                var result = gen.hashCode()
                result = 31 * result + offset
                result = 31 * result + length
                result = 31 * result + sampleRate
                result = 31 * result + sentenceIndex
                result = 31 * result + data.contentHashCode()
                result = 31 * result + source.hashCode()
                return result
            }
        }

        data class Marker(
            override val gen: Long,
            val sentenceIndex: Int,
            val type: MarkerType,
            val source: SynthesisMode,
            val onReached: (() -> Unit)? = null
        ) : QueueItem(gen)

        data class EndOfStream(override val gen: Long, val onDrained: () -> Unit) : QueueItem(gen)
    }

    private enum class ResetType { HARD, SOFT_QUEUE_ONLY }
    private data class Control(
        val type: ResetType,
        val ack: CompletableDeferred<Unit>? = null,
        val preserveSentenceIndex: Int = -1
    )

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var pcmChannel: Channel<QueueItem> = Channel(queueCapacity)
    private var controlChannel: Channel<Control> = Channel(Channel.CONFLATED)

    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    @Volatile private var isPaused = false
    @Volatile private var isStopped = true
    @Volatile private var currentVolume: Float = 1.0f
    @Volatile private var currentSampleRate: Int = initialSampleRate
    @Volatile private var currentPlaybackSource: SynthesisMode? = null
    @Volatile private var generation: Long = 0L

    // 保护期状态：软重置(保留句)后开启；只允许该句的离线数据播放
    @Volatile private var protectionActive: Boolean = false
    @Volatile private var protectedSentenceIndex: Int = -1

    /**
     * 保护期内到达的“在线数据”句子分桶缓冲：
     * - hasPcm: 是否至少包含一段 PCM（决定是否触发句子回调/推进）
     * - hasStart/hasEnd: 是否收到过对应 Marker（用于回吐顺序组织）
     * - items: 原始到达顺序（句内）保留；回吐时按 START -> 所有 PCM(到达顺序) -> END 的顺序
     */
    private data class DeferredBucket(
        val sentenceIndex: Int,
        val items: MutableList<QueueItem> = mutableListOf(),
        var hasPcm: Boolean = false,
        var hasStart: Boolean = false,
        var hasEnd: Boolean = false
    )

    // 使用 Map 按句子聚合；回吐时按句序处理，避免乱序推进
    private val deferredOnline = linkedMapOf<Int, DeferredBucket>()

    companion object {
        private const val TAG = "AudioPlayer"
        private const val WRITE_CHUNK_SIZE = 2048
        private const val FREEZE_MS = 150L
        private const val FREEZE_CAP = 0.03f
        private const val DENOM_FLOOR_MS = 100L
        private const val END_LAG_OK_MS = 40L

        // 分母动态平滑（上抬/回落）的步长与门限
        private const val UP_GROWTH_MS = 120L      // 分母上抬的每次最大增长等效毫秒
        private const val DOWN_SHRINK_MS = 120L    // 分母回落的每次最大减少等效毫秒
        private const val SHRINK_TRIGGER = 0.90f   // accepted / dynamicPred < 该阈值，认为预测偏大
        private const val SHRINK_START_FRAC = 0.55f// 超过该播放比例后才允许回落，避免前半段被放慢
    }

    // ---------- 新增：句内进度（samples 级）统计与查询 ----------
    data class SentencePlaybackProgress(
        val sentenceIndex: Int,
        val playedSamples: Long,
        val totalSamples: Long,
        val fraction: Float // 0..1（若 total=0 则为 0）
    )

    @Volatile private var currentSentenceForProgress: Int = -1

    // 自 AudioTrack 创建以来，累计“成功写入”的样本数（short 数），单声道即帧数
    @Volatile private var globalWrittenSamples: Long = 0L

    // 当前句“开始”时的全局已写入样本锚点（用于消除上一句缓冲尾巴的影响）
    @Volatile private var sentenceStartWrittenSamples: Long = 0L

    // 记录“已接纳用于播放”的 PCM 样本总数（按句聚合，单位：samples/shorts）
    private val sentenceTotalSamples = mutableMapOf<Int, Long>()

    // 预测分母（按句）
    private val predictedTotalPerSentence = mutableMapOf<Int, Long>()

    // 单句单调包络与句末放行
    @Volatile private var lastSentenceForProgress: Int = -1
    @Volatile private var lastFractionForSentence: Float = 0f
    @Volatile private var endMarkerReachedForSentence: Boolean = false

    // 新增：当前句“动态预测分母”（在 getCurrentSentenceProgress 内做平滑上抬/回落）
    @Volatile private var dynamicPredictedForSentence: Long = 0L

    /**
     * 查询当前句的播放进度（samples 级）。
     * - playedSamples：playbackHeadPosition - sentenceStartWrittenSamples（同一 AudioTrack 坐标系）
     * - totalSamples：该句“已接纳用于播放”的样本总数（在线整句迅速收敛，离线逐步收敛）
     * - fraction = played / max(total, 1)
     */
    fun getCurrentSentenceProgress(): SentencePlaybackProgress? {
        val at = audioTrack ?: return null
        val idx = currentSentenceForProgress
        if (idx < 0) return null
        val head = try { at.playbackHeadPosition.toLong() } catch (_: Exception) { return null }
        val played = (head - sentenceStartWrittenSamples).coerceAtLeast(0)

        val accepted = synchronized(sentenceTotalSamples) { sentenceTotalSamples[idx] ?: 0L }
        val predictedRaw = synchronized(predictedTotalPerSentence) { predictedTotalPerSentence[idx] ?: 0L }

        val sr = currentSampleRate.coerceAtLeast(1)
        val denomFloor = (sr * DENOM_FLOOR_MS / 1000).toLong().coerceAtLeast(1L)
        val upStep = (sr * UP_GROWTH_MS / 1000).toLong().coerceAtLeast(1L)
        val downStep = (sr * DOWN_SHRINK_MS / 1000).toLong().coerceAtLeast(1L)

        // 初始化动态预测分母
        if (dynamicPredictedForSentence <= 0L) {
            dynamicPredictedForSentence = predictedRaw
        }

        // 以当前动态分母估算一个临时分母与比例（用于判定是否允许回落）
        val denomForFrac = max(max(dynamicPredictedForSentence, accepted), 1L)
        val fracEst = (if (denomForFrac > 0) played.toFloat() / denomForFrac.toFloat() else 0f).coerceIn(0f, 1f)

        // 平滑上抬：目标上界 = max(预测, 已接纳)
        val targetUp = max(predictedRaw, accepted)
        var dyn = dynamicPredictedForSentence
        if (targetUp > dyn) {
            dyn = min(targetUp, dyn + upStep)
        } else {
            // 允许缓慢回落：后半段且 accepted/dyn 明显偏小（预测偏大）
            val ratio = if (dyn > 0L) accepted.toFloat() / dyn.toFloat() else 1f
            if (!endMarkerReachedForSentence && fracEst >= SHRINK_START_FRAC && ratio < SHRINK_TRIGGER) {
                dyn = max(accepted, dyn - downStep)
            }
        }
        dynamicPredictedForSentence = dyn

        // 最终原始分母（未加下限）：取“动态预测分母”和“已接纳”的较大值
        var denom = max(dyn, accepted).coerceAtLeast(1L)

        // 未到句末时给分母加下限，避免早到 1；到句末允许到 1
        if (!endMarkerReachedForSentence) {
            denom = max(denom, played + denomFloor)
        } else {
            denom = max(denom, played)
        }

        var frac = (played.toFloat() / denom.toFloat()).coerceIn(0f, 1f)

        // 句首预热冻结
        val playedMs = played.toFloat() * 1000f / sr.toFloat()
        if (!endMarkerReachedForSentence && playedMs < FREEZE_MS) {
            frac = min(frac, FREEZE_CAP)
        }

        // 单句单调包络
        if (idx != lastSentenceForProgress) {
            lastSentenceForProgress = idx
            lastFractionForSentence = 0f
        }
        if (frac < lastFractionForSentence) {
            frac = lastFractionForSentence
        } else {
            lastFractionForSentence = frac
        }

        return SentencePlaybackProgress(idx, played, denom, frac)
    }

    fun startIfNeeded(volume: Float = 1.0f) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (playbackJob?.isActive == true) return
        isStopped = false
        isPaused = false
        generation = 0L
        currentSampleRate = initialSampleRate
        currentPlaybackSource = null
        protectionActive = false
        protectedSentenceIndex = -1
        deferredOnline.clear()
        pcmChannel.close(); controlChannel.close()
        pcmChannel = Channel(queueCapacity)
        controlChannel = Channel(Channel.CONFLATED)
        // 重置进度统计
        synchronized(sentenceTotalSamples) { sentenceTotalSamples.clear() }
        synchronized(predictedTotalPerSentence) { predictedTotalPerSentence.clear() }
        currentSentenceForProgress = -1
        globalWrittenSamples = 0L
        sentenceStartWrittenSamples = 0L
        lastSentenceForProgress = -1
        lastFractionForSentence = 0f
        endMarkerReachedForSentence = false
        dynamicPredictedForSentence = 0L

        playbackJob = playbackCoroutine()
    }

    private fun playbackCoroutine() = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        audioTrack = null
        try {
            while (isActive && !isStopped) {

                // 1) 优先非阻塞消费控制命令
                controlChannel.tryReceive().getOrNull()?.let { control ->
                    processControlCommand(control)
                    continue
                }

                // 2) 暂停态：不消费 PCM，仅轮询控制命令，防止 reset/软重置 ack 卡住
                if (isPaused) {
                    val ctrl = controlChannel.tryReceive().getOrNull()
                    if (ctrl != null) {
                        processControlCommand(ctrl)
                    } else {
                        AppLogger.v(TAG, "暂停中：仅处理控制命令，PCM 暂不消费。")
                        delay(10)
                    }
                    continue
                }

                // 3) 正常消费：select 监听控制与 PCM/Marker
                select<Unit> {
                    controlChannel.onReceiveCatching { result ->
                        result.getOrNull()?.let { control ->  processControlCommand(control) }
                    }
                    pcmChannel.onReceiveCatching { result ->
                        val item = result.getOrNull() ?: return@onReceiveCatching
                        if (item.gen != generation) {
                            AppLogger.v(TAG, "丢弃旧代次(${item.gen})的数据，当前代次为 $generation")
                            return@onReceiveCatching
                        }
                        if (!isActive || isStopped || item.gen != generation) return@onReceiveCatching

                        when (item) {
                            is QueueItem.Pcm -> {
                                // 保护期：仅允许受保护句的离线 PCM；在线 PCM 句内暂存（句序回吐）
                                if (protectionActive && item.sentenceIndex != protectedSentenceIndex) {
                                    if (item.source == SynthesisMode.OFFLINE) {
                                        AppLogger.i(TAG, "保护期丢弃离线PCM：句子#${item.sentenceIndex} (受保护句为#${protectedSentenceIndex})")
                                        return@onReceiveCatching
                                    } else {
                                        val bucket = deferredOnline.getOrPut(item.sentenceIndex) { DeferredBucket(item.sentenceIndex) }
                                        bucket.items.add(item)
                                        bucket.hasPcm = true
                                        AppLogger.i(TAG, "保护期暂存在线PCM：句子#${item.sentenceIndex}，bucket(hasPcm=${bucket.hasPcm}) size=${bucket.items.size}")
                                        return@onReceiveCatching
                                    }
                                }

                                // 播放允许的数据
                                if (audioTrack == null || item.sampleRate != currentSampleRate) {
                                    switchSampleRate(item.sampleRate)
                                }
                                if (currentPlaybackSource != item.source || audioTrack?.sampleRate != item.sampleRate) {
                                    currentPlaybackSource = item.source
                                    AppLogger.i(TAG, ">>> 开始播放 [${item.source}] (采样率: ${item.sampleRate} Hz, 句子: ${item.sentenceIndex}) <<<")
                                }

                                // 累计该句的“已接纳样本数”（作为 totalSamples）
                                synchronized(sentenceTotalSamples) {
                                    val prev = sentenceTotalSamples[item.sentenceIndex] ?: 0L
                                    sentenceTotalSamples[item.sentenceIndex] = prev + item.length.toLong()
                                }

                                audioTrack?.play()
                                writePcmInChunks(item)
                            }
                            is QueueItem.Marker -> {
                                // 保护期：仅触发受保护句的离线 Marker；在线 Marker 句内暂存
                                if (protectionActive && item.sentenceIndex != protectedSentenceIndex) {
                                    if (item.source == SynthesisMode.OFFLINE) {
                                        AppLogger.i(TAG, "保护期丢弃离线Marker：句子#${item.sentenceIndex} type=${item.type}")
                                        return@onReceiveCatching
                                    } else {
                                        val bucket = deferredOnline.getOrPut(item.sentenceIndex) { DeferredBucket(item.sentenceIndex) }
                                        bucket.items.add(item)
                                        if (item.type == MarkerType.SENTENCE_START) bucket.hasStart = true
                                        if (item.type == MarkerType.SENTENCE_END) bucket.hasEnd = true
                                        AppLogger.i(TAG, "保护期暂存在线Marker：句子#${item.sentenceIndex} type=${item.type}，bucket(hasPcm=${bucket.hasPcm}, start=${bucket.hasStart}, end=${bucket.hasEnd})")
                                        return@onReceiveCatching
                                    }
                                }

                                if (item.gen == generation) {
                                    try { withContext(Dispatchers.Default) { item.onReached?.invoke() } }
                                    catch (t: Throwable) { AppLogger.w(TAG, "执行 Marker 回调时出错", t) }
                                }

                                // 句首 Marker：仅保证该句 totalSamples 初始化；进度锚点改为“第一块 PCM 写入”时设置
                                if (item.type == MarkerType.SENTENCE_START) {
                                    synchronized(sentenceTotalSamples) {
                                        if (sentenceTotalSamples[item.sentenceIndex] == null) {
                                            sentenceTotalSamples[item.sentenceIndex] = 0L
                                        }
                                    }
                                }

                                // 受保护句 END：等待缓冲排空 -> 结束保护期 -> 按句序吐回仅含 PCM 的句子
                                if (protectionActive && item.sentenceIndex == protectedSentenceIndex && item.type == MarkerType.SENTENCE_END) {
                                    AppLogger.i(TAG, "受保护句 #${item.sentenceIndex} 的 END 已到达，等待缓冲排空后结束保护期并按句序吐回在线暂存。")
                                    launch {
                                        waitForPlaybackToFinish()
                                        protectionActive = false
                                        protectedSentenceIndex = -1
                                        val toFlushBuckets = deferredOnline.size
                                        if (toFlushBuckets > 0) {
                                            AppLogger.i(TAG, "开始吐回 ${toFlushBuckets} 个句子的在线暂存（仅含PCM的句子回吐，按句序）")
                                            val indices = deferredOnline.keys.sorted()
                                            for (si in indices) {
                                                val b = deferredOnline[si] ?: continue
                                                if (!b.hasPcm) {
                                                    AppLogger.w(TAG, "句子#$si 在线暂存仅包含 Marker，无 PCM，丢弃以避免空推进。")
                                                    continue
                                                }
                                                val startMarker = b.items.firstOrNull { it is QueueItem.Marker && it.type == MarkerType.SENTENCE_START } as? QueueItem.Marker
                                                val endMarker = b.items.lastOrNull { it is QueueItem.Marker && it.type == MarkerType.SENTENCE_END } as? QueueItem.Marker
                                                val pcms = b.items.filterIsInstance<QueueItem.Pcm>()

                                                startMarker?.let {
                                                    AppLogger.i(TAG, "回吐句子#$si：发送 START")
                                                    pcmChannel.send(it.copy(gen = generation))
                                                } ?: AppLogger.w(TAG, "回吐句子#$si：缺少 START Marker。")

                                                for (p in pcms) {
                                                    AppLogger.i(TAG, "回吐句子#$si：发送 PCM 长度=${p.length}")
                                                    pcmChannel.send(p.copy(gen = generation))
                                                }

                                                endMarker?.let {
                                                    AppLogger.i(TAG, "回吐句子#$si：发送 END")
                                                    pcmChannel.send(it.copy(gen = generation))
                                                } ?: AppLogger.w(TAG, "回吐句子#$si：缺少 END Marker。")
                                            }
                                        }
                                        deferredOnline.clear()
                                        AppLogger.i(TAG, "保护期关闭。")
                                    }
                                }

                                // 记录当前句 END 到达（用于允许 fraction 最终达到 1）
                                if (item.type == MarkerType.SENTENCE_END && item.sentenceIndex == currentSentenceForProgress) {
                                    endMarkerReachedForSentence = true
                                }
                            }
                            is QueueItem.EndOfStream -> {
                                AppLogger.i(TAG, "收到流结束(EOS)，等待缓冲播放完毕后回调...")
                                launch {
                                    waitForPlaybackToFinish()
                                    if (item.gen == generation && !isStopped) {
                                        AppLogger.i(TAG, "缓冲已播放完毕。执行EOS回调。")
                                        item.onDrained()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            releaseAudioTrack()
            isStopped = true
            protectionActive = false
            protectedSentenceIndex = -1
            deferredOnline.clear()
            // 清理进度统计
            synchronized(sentenceTotalSamples) { sentenceTotalSamples.clear() }
            synchronized(predictedTotalPerSentence) { predictedTotalPerSentence.clear() }
            currentSentenceForProgress = -1
            globalWrittenSamples = 0L
            sentenceStartWrittenSamples = 0L
            lastSentenceForProgress = -1
            lastFractionForSentence = 0f
            endMarkerReachedForSentence = false
            dynamicPredictedForSentence = 0L
        }
    }

    /**
     * 控制命令处理：
     * - SOFT_QUEUE_ONLY：仅保留指定句子的队列项；开启保护期；清空在线暂存缓冲。
     * - HARD：释放 AudioTrack 并结束保护期。
     */
    private suspend fun processControlCommand(control: Control) {
        AppLogger.i(TAG, "正在处理控制命令: ${control.type}, 保留索引: ${control.preserveSentenceIndex}")
        val newGeneration = generation + 1
        generation = newGeneration
        AppLogger.d(TAG, "代次已更新至: $newGeneration")

        if (control.preserveSentenceIndex != -1) {
            val newPcmChannel = Channel<QueueItem>(queueCapacity)
            var preserved = 0
            while (true) {
                val item = pcmChannel.tryReceive().getOrNull() ?: break
                val keep = when (item) {
                    is QueueItem.Pcm -> item.sentenceIndex == control.preserveSentenceIndex
                    is QueueItem.Marker -> item.sentenceIndex == control.preserveSentenceIndex
                    else -> false
                }
                if (keep) {
                    val updated = when (item) {
                        is QueueItem.Pcm -> item.copy(gen = newGeneration)
                        is QueueItem.Marker -> item.copy(gen = newGeneration)
                        else -> item
                    }
                    newPcmChannel.trySend(updated)
                    preserved++
                }
            }
            pcmChannel.close()
            pcmChannel = newPcmChannel

            // 开启保护期
            protectionActive = true
            protectedSentenceIndex = control.preserveSentenceIndex
            deferredOnline.clear()
            AppLogger.d(TAG, "进入保护期：仅允许句子 #${protectedSentenceIndex} 的离线数据。保留条目数=$preserved，已清空在线暂存桶。")
        } else {
            while (pcmChannel.tryReceive().isSuccess) { /* drain */ }
            AppLogger.d(TAG, "PCM 队列已完全清空。")
        }

        when (control.type) {
            ResetType.HARD -> {
                AppLogger.d(TAG, "执行硬重置：释放 AudioTrack，退出保护期并清空在线暂存。")
                releaseAudioTrack()
                currentPlaybackSource = null
                protectionActive = false
                protectedSentenceIndex = -1
                deferredOnline.clear()
                // 清理进度统计
                synchronized(sentenceTotalSamples) { sentenceTotalSamples.clear() }
                synchronized(predictedTotalPerSentence) { predictedTotalPerSentence.clear() }
                currentSentenceForProgress = -1
                globalWrittenSamples = 0L
                sentenceStartWrittenSamples = 0L
                lastSentenceForProgress = -1
                lastFractionForSentence = 0f
                endMarkerReachedForSentence = false
                dynamicPredictedForSentence = 0L
            }
            ResetType.SOFT_QUEUE_ONLY -> {
                AppLogger.d(TAG, "执行软重置：仅清队列(保留指定句)，已进入保护期。")
            }
        }
        control.ack?.complete(Unit)
    }

    /**
     * 分块写入 PCM，处理控制命令：
     * - HARD：立即中断。
     * - SOFT_QUEUE_ONLY：若保留的是当前句，允许跨代次写完本块，避免断句；否则中断本块以尽快切换。
     * - 支持暂停：暂停期间不继续写入，但允许控制命令抢占，避免暂停期间 reset/软重置被卡住。
     *
     * 改进：句内进度的“起点锚”在该句第一块 PCM 开始写入时设置为当前 globalWrittenSamples，
     * 避免上一句在缓冲区的残留播放被计入本句，解决离线模式进度偏差。
     */
    private suspend fun writePcmInChunks(item: QueueItem.Pcm) {
        val at = audioTrack ?: return
        var written = 0
        var allowContinueAcrossGenBump = false

        // 句首：首次写入该句前，设置进度锚点，并重置状态
        if (currentSentenceForProgress != item.sentenceIndex) {
            currentSentenceForProgress = item.sentenceIndex
            sentenceStartWrittenSamples = globalWrittenSamples
            lastSentenceForProgress = item.sentenceIndex
            lastFractionForSentence = 0f
            endMarkerReachedForSentence = false
            // 初始化动态分母
            val predictedRaw = synchronized(predictedTotalPerSentence) { predictedTotalPerSentence[item.sentenceIndex] ?: 0L }
            dynamicPredictedForSentence = predictedRaw
        }

        while (written < item.length) {
            // 控制命令抢占
            controlChannel.tryReceive().getOrNull()?.let { control ->
                val isHard = control.type == ResetType.HARD
                val isSoft = control.type == ResetType.SOFT_QUEUE_ONLY
                val preservesCurrent = control.preserveSentenceIndex == item.sentenceIndex

                AppLogger.i(
                    TAG,
                    "写入中捕获控制命令: type=${control.type}, preserve=${control.preserveSentenceIndex}, " +
                            "currentSentence=${item.sentenceIndex}, written=$written/${item.length}"
                )

                processControlCommand(control)

                if (isHard) {
                    AppLogger.w(TAG, "检测到硬重置(HARD)，立即中断当前 PCM 写入。")
                    return
                }
                if (isSoft && preservesCurrent) {
                    allowContinueAcrossGenBump = true
                    AppLogger.d(TAG, "软重置(保当前句)，允许跨代次写完本块避免截断。")
                } else if (isSoft) {
                    AppLogger.w(TAG, "软重置(非当前句)，中断本 PCM 块以尽快切换。")
                    return
                }
            }

            if (!coroutineContext.isActive || isStopped || (item.gen != generation && !allowContinueAcrossGenBump)) {
                AppLogger.d(TAG, "写入PCM时状态/代次变化(allowAcrossGen=$allowContinueAcrossGenBump)，中断写入。")
                return
            }

            // 暂停处理：暂停期间不继续写入，但仍允许上面的控制命令抢占
            while (isPaused && coroutineContext.isActive && !isStopped) {
                delay(10)
                controlChannel.tryReceive().getOrNull()?.let { processControlCommand(it) }
                if (item.gen != generation && !allowContinueAcrossGenBump) {
                    AppLogger.d(TAG, "暂停等待中检测到代次变化，结束写入。")
                    return
                }
            }

            val toWrite = (item.length - written).coerceAtMost(WRITE_CHUNK_SIZE)
            val result = at.write(item.data, item.offset + written, toWrite)
            if (result > 0) {
                written += result
                // 累计全局“已写入样本数”（short 个数）
                globalWrittenSamples += result.toLong()
            } else {
                AppLogger.e(TAG, "AudioTrack 写入错误，代码: $result。中止当前音频块的写入。")
                break
            }
        }
    }

    private suspend fun waitForPlaybackToFinish() {
        val track = audioTrack ?: return
        AppLogger.d(TAG, "开始监视播放缓冲区排空...")
        try {
            var last = -1; var stall = 0; val MAX_STALLS = 50
            while (coroutineContext.isActive && !isStopped) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                val pos = track.playbackHeadPosition
                if (last == -1 || pos > last) { stall = 0; last = pos } else { stall++ }
                if (stall > MAX_STALLS) break
                delay(20)
            }
        } catch (e: Exception) { AppLogger.e(TAG, "等待播放完成时出错", e) }
    }

    private suspend fun switchSampleRate(newSampleRate: Int) {
        AppLogger.i(TAG, "切换采样率: 从 $currentSampleRate Hz -> $newSampleRate Hz")
        releaseAudioTrack()
        audioTrack = createAudioTrack(newSampleRate)
        if (audioTrack == null) {
            AppLogger.e(TAG, "创建新的 AudioTrack ($newSampleRate Hz) 失败，停止播放。")
            isStopped = true
        } else {
            currentSampleRate = newSampleRate
            // 新的 AudioTrack 建立后，重置“写入样本数”坐标系
            globalWrittenSamples = 0L
            sentenceStartWrittenSamples = 0L
            lastSentenceForProgress = -1
            lastFractionForSentence = 0f
            endMarkerReachedForSentence = false
            dynamicPredictedForSentence = 0L
            AppLogger.i(TAG, "新的 AudioTrack 已成功创建。")
        }
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack? {
        return try {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                AppLogger.e(TAG, "无效的最小缓冲区大小: $minBufferSize @ $sampleRate Hz")
                return null
            }
            val bufferSizeBytes = (minBufferSize * 2).coerceAtLeast(minBufferSize)
            AppLogger.d(TAG, "创建 AudioTrack: 采样率=$sampleRate Hz, 缓冲区大小=$bufferSizeBytes 字节")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSizeBytes,
                AudioTrack.MODE_STREAM
            ).also { at ->
                if (at.state != AudioTrack.STATE_INITIALIZED) {
                    AppLogger.e(TAG, "AudioTrack 初始化失败，状态: ${at.state}")
                    at.release()
                    return null
                }
                at.setStereoVolume(currentVolume, currentVolume)
            }
        } catch (e: Exception) { AppLogger.e(TAG, "创建 AudioTrack 时出错", e); null }
    }

    private suspend fun releaseAudioTrack() {
        withContext(Dispatchers.IO) {
            audioTrack?.let { track ->
                AppLogger.d(TAG, "正在释放 AudioTrack (采样率: ${track.sampleRate} Hz)...")
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause(); track.flush()
                    }
                    track.stop(); track.release()
                } catch (e: Exception) { AppLogger.e(TAG, "释放 AudioTrack 时出现异常", e) }
            }
            audioTrack = null
        }
    }

    suspend fun resetBlocking() {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ResetType.HARD, ack, preserveSentenceIndex = -1))
        ack.await()
        AppLogger.d(TAG, "播放器已确认[硬重置]完成。")
    }

    /**
     * 软重置：仅清队列，保留指定句，并进入保护期。
     */
    suspend fun resetQueueOnlyBlocking(preserveSentenceIndex: Int) {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ResetType.SOFT_QUEUE_ONLY, ack, preserveSentenceIndex))
        ack.await()
        AppLogger.d(TAG, "播放器已确认[软重置]完成，进入保护期：仅允许句子 #$preserveSentenceIndex 的离线数据。")
    }

    /**
     * 入队 PCM：
     * - 保护期内直接丢弃“非受保护句”的离线 PCM，杜绝升级窗口污染；
     * - 在线 PCM 始终接收（但在消费端按句子暂存，保护期后按句序回吐）。
     */
    suspend fun enqueuePcm(
        pcm: ShortArray,
        offset: Int = 0,
        length: Int = pcm.size,
        sampleRate: Int,
        source: SynthesisMode,
        sentenceIndex: Int
    ) {
        if (isStopped || length <= 0) return
        if (protectionActive && source == SynthesisMode.OFFLINE && sentenceIndex != protectedSentenceIndex) {
            AppLogger.i(TAG, "保护期丢弃入队的离线PCM：句子#$sentenceIndex (受保护句为#$protectedSentenceIndex)")
            return
        }
        pcmChannel.send(QueueItem.Pcm(generation, pcm, offset, length, sampleRate, source, sentenceIndex))
    }

    /**
     * 入队 Marker（带来源）：
     * - 保护期内丢弃“非受保护句”的离线 Marker，防止错误回调；
     * - 在线 Marker 始终接收（在消费端按句子暂存，保护期后按句序回吐）。
     */
    suspend fun enqueueMarker(
        sentenceIndex: Int,
        type: MarkerType,
        source: SynthesisMode,
        onReached: (() -> Unit)? = null
    ) {
        if (isStopped) return
        if (protectionActive && source == SynthesisMode.OFFLINE && sentenceIndex != protectedSentenceIndex) {
            AppLogger.i(TAG, "保护期丢弃入队的离线Marker：句子#$sentenceIndex type=$type")
            return
        }
        AppLogger.i(TAG, "入队Marker：句子#$sentenceIndex type=$type from $source")
        pcmChannel.send(QueueItem.Marker(generation, sentenceIndex, type, source, onReached))
    }

    /**
     * 兼容旧签名（无来源）。为避免误判，默认按 ONLINE 处理（即不会被丢弃，只会在保护期内暂存）。
     * 推荐尽快切到带 source 的重载，以便在保护期内正确丢弃“非受保护”的离线 Marker。
     */
    @Deprecated("Use enqueueMarker(sentenceIndex, type, source, onReached)")
    suspend fun enqueueMarker(sentenceIndex: Int, type: MarkerType, onReached: (() -> Unit)? = null) {
        enqueueMarker(sentenceIndex, type, SynthesisMode.ONLINE, onReached)
    }

    suspend fun enqueueEndOfStream(onDrained: () -> Unit) {
        if (isStopped) return
        pcmChannel.send(QueueItem.EndOfStream(generation, onDrained))
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1.0f); currentVolume = v
        audioTrack?.setStereoVolume(v, v)
    }

    fun pause() {
        if (!isStopped && !isPaused) {
            isPaused = true
            runCatching { audioTrack?.pause() }
            AppLogger.d(TAG, "音频已暂停 (用户操作)。")
        }
    }

    fun resume() {
        if (!isStopped && isPaused) {
            isPaused = false
            runCatching { audioTrack?.play() }
            AppLogger.d(TAG, "音频已恢复 (用户操作)。")
        }
    }

    suspend fun stopAndReleaseBlocking() {
        if (isStopped) return
        val jobToJoin = playbackJob
        isStopped = true; isPaused = false
        protectionActive = false
        protectedSentenceIndex = -1
        deferredOnline.clear()
        controlChannel.close(); pcmChannel.close()
        jobToJoin?.cancelAndJoin()
        playbackJob = null
        // 清理进度统计
        synchronized(sentenceTotalSamples) { sentenceTotalSamples.clear() }
        synchronized(predictedTotalPerSentence) { predictedTotalPerSentence.clear() }
        currentSentenceForProgress = -1
        globalWrittenSamples = 0L
        sentenceStartWrittenSamples = 0L
        lastSentenceForProgress = -1
        lastFractionForSentence = 0f
        endMarkerReachedForSentence = false
        dynamicPredictedForSentence = 0L

        AppLogger.d(TAG, "AudioPlayer 已同步停止并释放资源。")
    }

    // ---------- 上层查询/判定辅助 ----------

    /**
     * 指定来源/句子在当前时刻是否会被接收并进入播放（或被暂存）。
     * - ONLINE：在保护期内会被接收并暂存（最终按句序回吐）
     * - OFFLINE：仅受保护句会被接收；非受保护句在保护期内会被直接丢弃
     */
    fun canAccept(source: SynthesisMode, sentenceIndex: Int): Boolean {
        return if (!protectionActive) true
        else {
            when (source) {
                SynthesisMode.ONLINE -> true
                SynthesisMode.OFFLINE -> sentenceIndex == protectedSentenceIndex
            }
        }
    }

    fun isInProtection(): Boolean = protectionActive
    fun getProtectedSentenceIndex(): Int = protectedSentenceIndex

    // 供上层设置预测总样本
    fun setPredictedTotalSamples(sentenceIndex: Int, predictedSamples: Long) {
        if (predictedSamples <= 0) return
        synchronized(predictedTotalPerSentence) {
            predictedTotalPerSentence[sentenceIndex] = predictedSamples
        }
    }
}