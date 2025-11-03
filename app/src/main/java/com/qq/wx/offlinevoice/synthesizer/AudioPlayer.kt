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
 * 一个健壮的、高响应性的音频播放器，专为流式 TTS 设计。
 *
 * 核心特性:
 * 1.  **中断式播放**: 能够将大的音频数据块分解成小块进行写入。在每个块之间，它会检查控制命令，
 *      从而可以被外部命令（如 stop）几乎立即中断，解决了播放长音频时无响应的问题。
 * 2.  **动态采样率切换**: 无缝地重建 AudioTrack 以播放不同采样率的音频流，支持在线/离线模式的混合播放。
 * 3.  **代次(Generation)机制**: 通过递增的 'generation' 计数器，自动丢弃所有过时的数据和回调，
 *      从根本上防止了在快速重置或状态切换（如模式升降级）时发生的状态错乱。
 * 4.  **双重重置模式**:
 *      - **硬重置 (Hard Reset)**: 立即清空所有软硬件缓冲区，用于全新播放或强制停止。
 *      - **温柔重置 (Soft Reset)**: 仅清空软件队列中的“未来”数据，保留指定句子数据，用于平滑的模式升级。
 * 5.  **优雅的流结束处理 (EOS)**: 能够接收一个“流结束”标记，在播放完所有缓冲数据后触发回调。
 */
class AudioPlayer(
    private val initialSampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE,
    private val queueCapacity: Int = 256
) {
    enum class MarkerType { SENTENCE_START, SENTENCE_END }

    private sealed class QueueItem(open val gen: Long) {
        data class Pcm(
            override val gen: Long, val data: ShortArray, val offset: Int = 0, val length: Int = data.size,
            val sampleRate: Int, val source: SynthesisMode, val sentenceIndex: Int
        ) : QueueItem(gen) { /* equals/hashCode 已由 data class 自动生成 */ }
        data class Marker(
            override val gen: Long, val sentenceIndex: Int, val type: MarkerType, val onReached: (() -> Unit)? = null
        ) : QueueItem(gen)
        data class EndOfStream(override val gen: Long, val onDrained: () -> Unit) : QueueItem(gen)
    }

    private enum class ResetType { HARD, SOFT_QUEUE_ONLY }
    private data class Control(
        val type: ResetType, val ack: CompletableDeferred<Unit>? = null, val preserveSentenceIndex: Int = -1
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

    companion object {
        private const val TAG = "AudioPlayer"
        /**
         * 每次写入 AudioTrack 的数据块大小 (单位: shorts)。
         * 这个值足够小，可以确保每次 write() 调用耗时很短，
         * 使得播放循环可以频繁地检查新的控制命令，从而实现高响应性。
         */
        private const val WRITE_CHUNK_SIZE = 2048
    }

    fun startIfNeeded(volume: Float = 1.0f) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (playbackJob?.isActive == true) return
        isStopped = false
        generation = 0L
        currentSampleRate = initialSampleRate
        currentPlaybackSource = null
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
                select<Unit> {
                    controlChannel.onReceive { control ->
                        processControlCommand(control)
                    }
                    pcmChannel.onReceive { item ->
                        if (item.gen != generation) {
                            Log.v(TAG, "丢弃旧代次(${item.gen})的数据，当前代次为 $generation")
                            return@onReceive
                        }
                        while (isPaused && isActive && !isStopped && item.gen == generation) { delay(10) }
                        if (!isActive || isStopped || item.gen != generation) return@onReceive

                        when (item) {
                            is QueueItem.Pcm -> {
                                if (audioTrack == null || item.sampleRate != currentSampleRate) {
                                    switchSampleRate(item.sampleRate)
                                }
                                if (currentPlaybackSource != item.source || audioTrack?.sampleRate != item.sampleRate) {
                                    currentPlaybackSource = item.source
                                    Log.i(TAG, ">>> 开始播放来自 [${item.source}] 的音频 (采样率: ${item.sampleRate} Hz, 句子: ${item.sentenceIndex}) <<<")
                                }
                                audioTrack?.play()
                                // 核心修改：使用可中断的分块写入代替阻塞的整块写入
                                writePcmInChunks(item)
                            }
                            is QueueItem.Marker -> {
                                if (item.gen == generation) {
                                    try {
                                        withContext(Dispatchers.Default) { item.onReached?.invoke() }
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "执行 Marker 回调时出错", t)
                                    }
                                }
                            }
                            is QueueItem.EndOfStream -> {
                                Log.i(TAG, "收到流结束(EOS)标记，将等待所有缓冲播放完毕后执行回调...")
                                launch {
                                    waitForPlaybackToFinish()
                                    if (item.gen == generation && !isStopped) {
                                        Log.i(TAG, "音频缓冲已播放完毕。执行EOS回调。")
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
        }
    }

    /**
     * 统一处理所有控制命令（如重置）。
     * 这个函数会更新代次(generation)，清空或部分清空队列，并根据需要重建 AudioTrack。
     */
    private suspend fun processControlCommand(control: Control) {
        Log.i(TAG, "正在处理控制命令: ${control.type}, 保留索引: ${control.preserveSentenceIndex}")
        val newGeneration = generation + 1
        generation = newGeneration
        Log.d(TAG, "代次已更新至: $newGeneration")

        if (control.preserveSentenceIndex != -1) {
            val newPcmChannel = Channel<QueueItem>(queueCapacity)
            var preservedCount = 0
            while(true) {
                val item = pcmChannel.tryReceive().getOrNull() ?: break
                val shouldPreserve = when(item) {
                    is QueueItem.Pcm -> item.sentenceIndex == control.preserveSentenceIndex
                    is QueueItem.Marker -> item.sentenceIndex == control.preserveSentenceIndex
                    else -> false
                }
                if (shouldPreserve) {
                    val newItem = when(item) {
                        is QueueItem.Pcm -> item.copy(gen = newGeneration)
                        is QueueItem.Marker -> item.copy(gen = newGeneration)
                        else -> item
                    }
                    newPcmChannel.trySend(newItem)
                    preservedCount++
                }
            }
            pcmChannel.close()
            pcmChannel = newPcmChannel
            Log.d(TAG, "队列已部分清空。保留并更新了 ${preservedCount} 个属于句子 ${control.preserveSentenceIndex} 的数据项。")
        } else {
            while (pcmChannel.tryReceive().isSuccess) { /* drain */ }
            Log.d(TAG, "PCM 队列已完全清空。")
        }

        when (control.type) {
            ResetType.HARD -> {
                Log.d(TAG, "执行硬重置：正在释放 AudioTrack...")
                releaseAudioTrack()
                currentPlaybackSource = null
            }
            ResetType.SOFT_QUEUE_ONLY -> {
                Log.d(TAG, "执行软重置：仅清空队列，保留 AudioTrack。")
            }
        }
        control.ack?.complete(Unit)
    }

    /**
     * 核心修复：以可中断的方式分块写入 PCM 数据。
     *
     * 在写入每个小数据块之前，此函数会检查 `controlChannel` 中是否有新的命令。
     * 如果检测到命令（例如 `stop` 导致的 `reset`），它会立即停止写入当前音频，
     * 并处理该命令，从而实现对外部控制的即时响应。
     */
    private suspend fun writePcmInChunks(item: QueueItem.Pcm) {
        val at = audioTrack ?: return
        var written = 0
        // 若在本块中处理了“保当前句”的软重置，允许忽略代次变化以继续写完本块
        var allowContinueAcrossGenBump = false

        while (written < item.length) {
            // 1) 每个小块前检查控制命令
            val control = controlChannel.tryReceive().getOrNull()
            if (control != null) {
                val isHard = control.type == ResetType.HARD
                val isSoft = control.type == ResetType.SOFT_QUEUE_ONLY
                val preservesCurrent = control.preserveSentenceIndex == item.sentenceIndex

                Log.i(
                    TAG,
                    "写入中捕获控制命令: type=${control.type}, preserve=${control.preserveSentenceIndex}, " +
                            "currentSentence=${item.sentenceIndex}, written=$written/${item.length}"
                )

                // 处理命令（完成代次切换/保留/清队等，同时会完成 ack）
                processControlCommand(control)

                if (isHard) {
                    // 强制停止：立即中断
                    Log.w(TAG, "检测到硬重置(HARD)，立即中断当前 PCM 写入。")
                    return
                }

                if (isSoft && preservesCurrent) {
                    // 这是“升级在线、保留当前句”的典型场景：继续把本块写完，避免断句
                    allowContinueAcrossGenBump = true
                    Log.d(
                        TAG,
                        "已处理软重置且保留当前句，允许跨代次继续完成本 PCM 块写入，避免截断。"
                    )
                    // 注意：此后 item.gen 可能和 generation 不一致，但本块内允许继续
                } else {
                    // 未保留当前句（或其它软命令）：为保证快速切换，仍然中断当前块
                    Log.w(
                        TAG,
                        "收到软重置但未保留当前句，为提升响应速度，中断本 PCM 块写入。"
                    )
                    return
                }
            }

            // 2) 协程/播放器状态检查；若代次变化且未允许跨代次写完，则中断
            if (!coroutineContext.isActive || isStopped || (item.gen != generation && !allowContinueAcrossGenBump)) {
                Log.d(
                    TAG,
                    "写入中检测到状态/代次变化(allowAcrossGen=$allowContinueAcrossGenBump)，中断本 PCM 块写入。"
                )
                return
            }

            // 3) 暂停等待
            while (isPaused && coroutineContext.isActive && !isStopped) { delay(10) }

            // 4) 计算本次写入的分块大小
            val toWrite = (item.length - written).coerceAtMost(WRITE_CHUNK_SIZE)

            // 5) 写入
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
            var lastPosition = -1; var stallCount = 0; val MAX_STALLS = 50
            while (coroutineContext.isActive && !isStopped) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    Log.d(TAG, "AudioTrack 不在播放状态，停止监视。")
                    break
                }
                val currentPosition = track.playbackHeadPosition
                if (lastPosition == -1 || currentPosition > lastPosition) {
                    stallCount = 0
                    lastPosition = currentPosition
                } else {
                    stallCount++
                }
                if (stallCount > MAX_STALLS) {
                    Log.d(TAG, "播放头位置长时间未改变 ($currentPosition)，认为播放结束。")
                    break
                }
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
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO; val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                Log.e(TAG, "无效的最小缓冲区大小: $minBufferSize @ $sampleRate Hz")
                return null
            }
            val bufferSizeBytes = (minBufferSize * 2).coerceAtLeast(minBufferSize)
            Log.d(TAG, "创建 AudioTrack: 采样率=$sampleRate Hz, 缓冲区大小=$bufferSizeBytes 字节")
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, bufferSizeBytes, AudioTrack.MODE_STREAM)
                .also { at ->
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

    suspend fun resetQueueOnlyBlocking(preserveSentenceIndex: Int) {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ResetType.SOFT_QUEUE_ONLY, ack, preserveSentenceIndex))
        ack.await()
        Log.d(TAG, "播放器已确认[软重置]完成，并尝试保留句子 $preserveSentenceIndex 的数据。")
    }

    suspend fun enqueuePcm(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size, sampleRate: Int, source: SynthesisMode, sentenceIndex: Int) {
        if (isStopped || length <= 0) return
        pcmChannel.send(QueueItem.Pcm(generation, pcm, offset, length, sampleRate, source, sentenceIndex))
    }

    suspend fun enqueueMarker(sentenceIndex: Int, type: MarkerType, onReached: (() -> Unit)? = null) {
        if (isStopped) return
        pcmChannel.send(QueueItem.Marker(generation, sentenceIndex, type, onReached))
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
            runCatching { audioTrack?.pause() }; Log.d(TAG, "音频已暂停 (用户操作)。")
        }
    }

    fun resume() {
        if (!isStopped && isPaused) {
            isPaused = false
            runCatching { audioTrack?.play() }; Log.d(TAG, "音频已恢复 (用户操作)。")
        }
    }

    suspend fun stopAndReleaseBlocking() {
        if (isStopped) return
        val jobToJoin = playbackJob
        isStopped = true; isPaused = false
        controlChannel.close(); pcmChannel.close()
        jobToJoin?.cancelAndJoin()
        playbackJob = null
        Log.d(TAG, "AudioPlayer 已同步停止并释放资源。")
    }
}