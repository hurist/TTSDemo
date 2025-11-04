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
        ) : QueueItem(gen)

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
                        Log.v(TAG, "暂停中：仅处理控制命令，PCM 暂不消费。")
                        delay(10)
                    }
                    continue
                }

                // 3) 正常消费：select 监听控制与 PCM/Marker
                select<Unit> {
                    controlChannel.onReceive { control ->
                        processControlCommand(control)
                    }
                    pcmChannel.onReceive { item ->
                        if (item.gen != generation) {
                            Log.v(TAG, "丢弃旧代次(${item.gen})的数据，当前代次为 $generation")
                            return@onReceive
                        }
                        if (!isActive || isStopped || item.gen != generation) return@onReceive

                        when (item) {
                            is QueueItem.Pcm -> {
                                // 保护期：仅允许受保护句的离线 PCM；在线 PCM 句内暂存（句序回吐）
                                if (protectionActive && item.sentenceIndex != protectedSentenceIndex) {
                                    if (item.source == SynthesisMode.OFFLINE) {
                                        Log.i(TAG, "保护期丢弃离线PCM：句子#${item.sentenceIndex} (受保护句为#${protectedSentenceIndex})")
                                        return@onReceive
                                    } else {
                                        val bucket = deferredOnline.getOrPut(item.sentenceIndex) { DeferredBucket(item.sentenceIndex) }
                                        bucket.items.add(item)
                                        bucket.hasPcm = true
                                        Log.i(TAG, "保护期暂存在线PCM：句子#${item.sentenceIndex}，bucket(hasPcm=${bucket.hasPcm}) size=${bucket.items.size}")
                                        return@onReceive
                                    }
                                }

                                // 播放允许的数据
                                if (audioTrack == null || item.sampleRate != currentSampleRate) {
                                    switchSampleRate(item.sampleRate)
                                }
                                if (currentPlaybackSource != item.source || audioTrack?.sampleRate != item.sampleRate) {
                                    currentPlaybackSource = item.source
                                    Log.i(TAG, ">>> 开始播放 [${item.source}] (采样率: ${item.sampleRate} Hz, 句子: ${item.sentenceIndex}) <<<")
                                }
                                audioTrack?.play()
                                writePcmInChunks(item)
                            }
                            is QueueItem.Marker -> {
                                // 保护期：仅触发受保护句的离线 Marker；在线 Marker 句内暂存
                                if (protectionActive && item.sentenceIndex != protectedSentenceIndex) {
                                    if (item.source == SynthesisMode.OFFLINE) {
                                        Log.i(TAG, "保护期丢弃离线Marker：句子#${item.sentenceIndex} type=${item.type}")
                                        return@onReceive
                                    } else {
                                        val bucket = deferredOnline.getOrPut(item.sentenceIndex) { DeferredBucket(item.sentenceIndex) }
                                        bucket.items.add(item)
                                        if (item.type == MarkerType.SENTENCE_START) bucket.hasStart = true
                                        if (item.type == MarkerType.SENTENCE_END) bucket.hasEnd = true
                                        Log.i(TAG, "保护期暂存在线Marker：句子#${item.sentenceIndex} type=${item.type}，bucket(hasPcm=${bucket.hasPcm}, start=${bucket.hasStart}, end=${bucket.hasEnd})")
                                        return@onReceive
                                    }
                                }

                                if (item.gen == generation) {
                                    try { withContext(Dispatchers.Default) { item.onReached?.invoke() } }
                                    catch (t: Throwable) { Log.w(TAG, "执行 Marker 回调时出错", t) }
                                }

                                // 受保护句 END：等待缓冲排空 -> 结束保护期 -> 按句序吐回仅含 PCM 的句子
                                if (protectionActive && item.sentenceIndex == protectedSentenceIndex && item.type == MarkerType.SENTENCE_END) {
                                    Log.i(TAG, "受保护句 #${item.sentenceIndex} 的 END 已到达，等待缓冲排空后结束保护期并按句序吐回在线暂存。")
                                    launch {
                                        waitForPlaybackToFinish()
                                        protectionActive = false
                                        protectedSentenceIndex = -1
                                        val toFlushBuckets = deferredOnline.size
                                        if (toFlushBuckets > 0) {
                                            Log.i(TAG, "开始吐回 ${toFlushBuckets} 个句子的在线暂存（仅含PCM的句子回吐，按句序）")
                                            val indices = deferredOnline.keys.sorted()
                                            for (si in indices) {
                                                val b = deferredOnline[si] ?: continue
                                                if (!b.hasPcm) {
                                                    Log.w(TAG, "句子#$si 在线暂存仅包含 Marker，无 PCM，丢弃以避免空推进。")
                                                    continue
                                                }
                                                val startMarker = b.items.firstOrNull { it is QueueItem.Marker && it.type == MarkerType.SENTENCE_START } as? QueueItem.Marker
                                                val endMarker = b.items.lastOrNull { it is QueueItem.Marker && it.type == MarkerType.SENTENCE_END } as? QueueItem.Marker
                                                val pcms = b.items.filterIsInstance<QueueItem.Pcm>()

                                                startMarker?.let {
                                                    Log.i(TAG, "回吐句子#$si：发送 START")
                                                    pcmChannel.send(it.copy(gen = generation))
                                                } ?: Log.w(TAG, "回吐句子#$si：缺少 START Marker。")

                                                for (p in pcms) {
                                                    Log.i(TAG, "回吐句子#$si：发送 PCM 长度=${p.length}")
                                                    pcmChannel.send(p.copy(gen = generation))
                                                }

                                                endMarker?.let {
                                                    Log.i(TAG, "回吐句子#$si：发送 END")
                                                    pcmChannel.send(it.copy(gen = generation))
                                                } ?: Log.w(TAG, "回吐句子#$si：缺少 END Marker。")
                                            }
                                        }
                                        deferredOnline.clear()
                                        Log.i(TAG, "保护期关闭。")
                                    }
                                }
                            }
                            is QueueItem.EndOfStream -> {
                                Log.i(TAG, "收到流结束(EOS)，等待缓冲播放完毕后回调...")
                                launch {
                                    waitForPlaybackToFinish()
                                    if (item.gen == generation && !isStopped) {
                                        Log.i(TAG, "缓冲已播放完毕。执行EOS回调。")
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
        }
    }

    /**
     * 控制命令处理：
     * - SOFT_QUEUE_ONLY：仅保留指定句子的队列项；开启保护期；清空在线暂存缓冲。
     * - HARD：释放 AudioTrack 并结束保护期。
     */
    private suspend fun processControlCommand(control: Control) {
        Log.i(TAG, "正在处理控制命令: ${control.type}, 保留索引: ${control.preserveSentenceIndex}")
        val newGeneration = generation + 1
        generation = newGeneration
        Log.d(TAG, "代次已更新至: $newGeneration")

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
            Log.d(TAG, "进入保护期：仅允许句子 #${protectedSentenceIndex} 的离线数据。保留条目数=$preserved，已清空在线暂存桶。")
        } else {
            while (pcmChannel.tryReceive().isSuccess) { /* drain */ }
            Log.d(TAG, "PCM 队列已完全清空。")
        }

        when (control.type) {
            ResetType.HARD -> {
                Log.d(TAG, "执行硬重置：释放 AudioTrack，退出保护期并清空在线暂存。")
                releaseAudioTrack()
                currentPlaybackSource = null
                protectionActive = false
                protectedSentenceIndex = -1
                deferredOnline.clear()
            }
            ResetType.SOFT_QUEUE_ONLY -> {
                Log.d(TAG, "执行软重置：仅清空队列(保留指定句)，已进入保护期。")
            }
        }
        control.ack?.complete(Unit)
    }

    /**
     * 分块写入 PCM，处理控制命令：
     * - HARD：立即中断。
     * - SOFT_QUEUE_ONLY：若保留的是当前句，允许跨代次写完本块，避免断句；否则中断本块以尽快切换。
     * - 支持暂停：暂停期间不继续写入，但允许控制命令抢占，避免暂停期间 reset/软重置被卡住。
     */
    private suspend fun writePcmInChunks(item: QueueItem.Pcm) {
        val at = audioTrack ?: return
        var written = 0
        var allowContinueAcrossGenBump = false

        while (written < item.length) {
            // 控制命令抢占
            controlChannel.tryReceive().getOrNull()?.let { control ->
                val isHard = control.type == ResetType.HARD
                val isSoft = control.type == ResetType.SOFT_QUEUE_ONLY
                val preservesCurrent = control.preserveSentenceIndex == item.sentenceIndex

                Log.i(
                    TAG,
                    "写入中捕获控制命令: type=${control.type}, preserve=${control.preserveSentenceIndex}, " +
                            "currentSentence=${item.sentenceIndex}, written=$written/${item.length}"
                )

                processControlCommand(control)

                if (isHard) {
                    Log.w(TAG, "检测到硬重置(HARD)，立即中断当前 PCM 写入。")
                    return
                }
                if (isSoft && preservesCurrent) {
                    allowContinueAcrossGenBump = true
                    Log.d(TAG, "软重置(保当前句)，允许跨代次写完本块避免截断。")
                } else if (isSoft) {
                    Log.w(TAG, "软重置(非当前句)，中断本 PCM 块以尽快切换。")
                    return
                }
            }

            if (!coroutineContext.isActive || isStopped || (item.gen != generation && !allowContinueAcrossGenBump)) {
                Log.d(TAG, "写入PCM时状态/代次变化(allowAcrossGen=$allowContinueAcrossGenBump)，中断写入。")
                return
            }

            // 暂停处理：暂停期间不继续写入，但仍允许上面的控制命令抢占
            while (isPaused && coroutineContext.isActive && !isStopped) {
                delay(10)
                controlChannel.tryReceive().getOrNull()?.let { processControlCommand(it) }
                if (item.gen != generation && !allowContinueAcrossGenBump) {
                    Log.d(TAG, "暂停等待中检测到代次变化，结束写入。")
                    return
                }
            }

            val toWrite = (item.length - written).coerceAtMost(WRITE_CHUNK_SIZE)
            val result = at.write(item.data, item.offset + written, toWrite)
            if (result > 0) {
                written += result
            } else {
                Log.e(TAG, "AudioTrack 写入错误，代码: $result。中止当前音频块的写入。")
                break
            }
        }
    }

    private suspend fun waitForPlaybackToFinish() {
        val track = audioTrack ?: return
        Log.d(TAG, "开始监视播放缓冲区排空...")
        try {
            var last = -1; var stall = 0; val MAX_STALLS = 50
            while (coroutineContext.isActive && !isStopped) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                val pos = track.playbackHeadPosition
                if (last == -1 || pos > last) { stall = 0; last = pos } else { stall++ }
                if (stall > MAX_STALLS) break
                delay(20)
            }
        } catch (e: Exception) { Log.e(TAG, "等待播放完成时出错", e) }
    }

    private suspend fun switchSampleRate(newSampleRate: Int) {
        Log.i(TAG, "切换采样率: 从 $currentSampleRate Hz -> $newSampleRate Hz")
        releaseAudioTrack()
        audioTrack = createAudioTrack(newSampleRate)
        if (audioTrack == null) {
            Log.e(TAG, "创建新的 AudioTrack ($newSampleRate Hz) 失败，停止播放。")
            isStopped = true
        } else {
            currentSampleRate = newSampleRate
            Log.i(TAG, "新的 AudioTrack 已成功创建。")
        }
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack? {
        return try {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                Log.e(TAG, "无效的最小缓冲区大小: $minBufferSize @ $sampleRate Hz")
                return null
            }
            val bufferSizeBytes = (minBufferSize * 2).coerceAtLeast(minBufferSize)
            Log.d(TAG, "创建 AudioTrack: 采样率=$sampleRate Hz, 缓冲区大小=$bufferSizeBytes 字节")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSizeBytes,
                AudioTrack.MODE_STREAM
            ).also { at ->
                if (at.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack 初始化失败，状态: ${at.state}")
                    at.release()
                    return null
                }
                at.setStereoVolume(currentVolume, currentVolume)
            }
        } catch (e: Exception) { Log.e(TAG, "创建 AudioTrack 时出错", e); null }
    }

    private suspend fun releaseAudioTrack() {
        withContext(Dispatchers.IO) {
            audioTrack?.let { track ->
                Log.d(TAG, "正在释放 AudioTrack (采样率: ${track.sampleRate} Hz)...")
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause(); track.flush()
                    }
                    track.stop(); track.release()
                } catch (e: Exception) { Log.e(TAG, "释放 AudioTrack 时出现异常", e) }
            }
            audioTrack = null
        }
    }

    suspend fun resetBlocking() {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ResetType.HARD, ack, preserveSentenceIndex = -1))
        ack.await()
        Log.d(TAG, "播放器已确认[硬重置]完成。")
    }

    /**
     * 软重置：仅清队列，保留指定句，并进入保护期。
     */
    suspend fun resetQueueOnlyBlocking(preserveSentenceIndex: Int) {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ResetType.SOFT_QUEUE_ONLY, ack, preserveSentenceIndex))
        ack.await()
        Log.d(TAG, "播放器已确认[软重置]完成，进入保护期：仅允许句子 #$preserveSentenceIndex 的离线数据。")
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
            Log.i(TAG, "保护期丢弃入队的离线PCM：句子#$sentenceIndex (受保护句为#$protectedSentenceIndex)")
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
            Log.i(TAG, "保护期丢弃入队的离线Marker：句子#$sentenceIndex type=$type")
            return
        }
        Log.i(TAG, "入队Marker：句子#$sentenceIndex type=$type from $source")
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
        val v = volume.coerceIn(0f, 1f); currentVolume = v
        audioTrack?.setStereoVolume(v, v)
    }

    fun pause() {
        if (!isStopped && !isPaused) {
            isPaused = true
            runCatching { audioTrack?.pause() }
            Log.d(TAG, "音频已暂停 (用户操作)。")
        }
    }

    fun resume() {
        if (!isStopped && isPaused) {
            isPaused = false
            runCatching { audioTrack?.play() }
            Log.d(TAG, "音频已恢复 (用户操作)。")
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
        Log.d(TAG, "AudioPlayer 已同步停止并释放资源。")
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
}