package com.qq.wx.offlinevoice.synthesizer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
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
 * 高健壮性的动态采样率音频播放器。
 *
 * 核心特性:
 * 1.  **动态采样率切换**: 能够根据接收到的 PCM 数据中指定的采样率，自动重建 AudioTrack 以无缝播放不同采样率的音频流。
 * 2.  **命令驱动与双重重置**:
 *      - **硬重置 (Hard Reset)**: 立即清空所有软硬件缓冲区，用于全新播放或强制停止。
 *      - **温柔重置 (Soft Reset)**: 仅清空软件队列中“未来”的数据，保留正在播放的句子数据，用于平滑的模式升级。
 * 3.  **代次(Generation)机制**: 在任何重置操作后，通过增加代次计数器来自动丢弃所有过时的音频数据和回调，从根本上杜绝状态错乱。
 * 4.  **优雅的流结束处理 (EOS)**: 能够接收一个“流结束”标记，在播放完所有缓冲数据后触发回调，实现“播完再暂停”等高级功能。
 * 5.  **快速停止**: 优化了停止逻辑，通过 `pause()` 和 `flush()` 确保 `stop()` 命令能被立即响应。
 */
class AudioPlayer(
    private val initialSampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE,
    private val queueCapacity: Int = 256
) {
    // --- 修改：MarkerType现在是一个公开的enum，以便TtsSynthesizer可以访问 ---
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
                if (source != other.source) return false
                if (sentenceIndex != other.sentenceIndex) return false
                if (!data.contentEquals(other.data)) return false
                return true
            }
            override fun hashCode(): Int {
                var result = gen.hashCode()
                result = 31 * result + offset
                result = 31 * result + length
                result = 31 * result + sampleRate
                result = 31 * result + source.hashCode()
                result = 31 * result + sentenceIndex
                result = 31 * result + data.contentHashCode()
                return result
            }
        }
        data class Marker(
            override val gen: Long,
            val sentenceIndex: Int,
            val type: MarkerType,
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

    companion object {
        private const val TAG = "AudioPlayer"
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
                        Log.i(TAG, "收到控制命令: ${control.type}, 保留句子索引: ${control.preserveSentenceIndex}")
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
                                    // --- 核心修复：用新的代次重新创建 item ---
                                    val newItem = when(item) {
                                        is QueueItem.Pcm -> item.copy(gen = newGeneration)
                                        is QueueItem.Marker -> item.copy(gen = newGeneration)
                                        else -> item
                                    }
                                    Log.d(TAG, "保留并更新代次的数据项: $newItem")
                                    newPcmChannel.trySend(newItem)
                                    preservedCount++
                                }
                            }
                            pcmChannel.close()
                            pcmChannel = newPcmChannel
                            Log.d(TAG, "播放队列已部分清空，保留并更新了 ${preservedCount} 个属于句子 ${control.preserveSentenceIndex} 的数据项。")
                        } else {
                            while (pcmChannel.tryReceive().isSuccess) { /* drain */ }
                            Log.d(TAG, "播放队列(pcmChannel)已完全清空。")
                        }

                        when (control.type) {
                            ResetType.HARD -> {
                                Log.d(TAG, "执行硬重置：正在重建 AudioTrack...")
                                releaseAudioTrack()
                                currentPlaybackSource = null
                            }
                            ResetType.SOFT_QUEUE_ONLY -> {
                                Log.d(TAG, "执行温柔重置：仅清空队列，保留 AudioTrack。")
                            }
                        }

                        control.ack?.complete(Unit)
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
                                writePcmBlocking(item.data, item.offset, item.length)
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
                                        Log.i(TAG, "所有缓冲已播放完毕。")
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

    private suspend fun waitForPlaybackToFinish() {
        val track = audioTrack ?: return
        Log.d(TAG, "开始监视播放缓冲区排空...")
        try {
            var lastPosition = -1
            var stallCount = 0
            val MAX_STALLS = 50

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
        } catch (e: Exception) {
            Log.e(TAG, "等待播放完成时出错", e)
        }
    }

    private suspend fun switchSampleRate(newSampleRate: Int) {
        Log.i(TAG, "采样率切换: 从 $currentSampleRate Hz -> $newSampleRate Hz")
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
                AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                audioFormat, bufferSizeBytes, AudioTrack.MODE_STREAM
            ).also { at ->
                if (at.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack 初始化失败，状态: ${at.state}")
                    at.release()
                    return null
                }
                at.setStereoVolume(currentVolume, currentVolume)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 AudioTrack 时出错", e)
            null
        }
    }

    private suspend fun releaseAudioTrack() {
        withContext(Dispatchers.IO) {
            audioTrack?.let { track ->
                Log.d(TAG, "正在释放 AudioTrack (采样率: ${track.sampleRate} Hz)...")
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                        track.flush()
                    }
                    track.stop()
                    track.release()
                } catch (e: Exception) {
                    Log.e(TAG, "释放 AudioTrack 时出现异常", e)
                }
            }
            audioTrack = null
        }
    }

    suspend fun resetBlocking() {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ResetType.HARD, ack, preserveSentenceIndex = -1))
        ack.await()
        Log.d(TAG, "AudioPlayer 已确认[硬重置]完成。")
    }

    suspend fun resetQueueOnlyBlocking(preserveSentenceIndex: Int) {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ResetType.SOFT_QUEUE_ONLY, ack, preserveSentenceIndex))
        ack.await()
        Log.d(TAG, "AudioPlayer 已确认[温柔重置]完成，并尝试保留句子 $preserveSentenceIndex 的数据。")
    }

    suspend fun enqueuePcm(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size, sampleRate: Int, source: SynthesisMode, sentenceIndex: Int) {
        if (isStopped || length <= 0) return
        val currentGen = generation
        val item = QueueItem.Pcm(currentGen, pcm, offset, length, sampleRate, source, sentenceIndex)
        pcmChannel.send(item)
    }

    suspend fun enqueueMarker(sentenceIndex: Int, type: MarkerType, onReached: (() -> Unit)? = null) {
        if (isStopped) return
        val currentGen = generation
        val item = QueueItem.Marker(currentGen, sentenceIndex, type, onReached)
        pcmChannel.send(item)
    }

    suspend fun enqueueEndOfStream(onDrained: () -> Unit) {
        if (isStopped) return
        val currentGen = generation
        val item = QueueItem.EndOfStream(currentGen, onDrained)
        pcmChannel.send(item)
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        currentVolume = v
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
        isStopped = true
        isPaused = false
        controlChannel.close()
        pcmChannel.close()
        jobToJoin?.cancelAndJoin()
        playbackJob = null
        Log.d(TAG, "AudioPlayer 已同步停止并释放资源。")
    }

    private suspend fun writePcmBlocking(data: ShortArray, offset: Int, length: Int) {
        val at = audioTrack ?: return
        var written = 0
        while (written < length && !isStopped && coroutineContext.isActive) {
            val result = at.write(data, offset + written, length - written)
            if (result > 0) {
                written += result
            } else {
                Log.e(TAG, "AudioTrack 写入错误，代码: $result")
                break
            }
        }
    }
}