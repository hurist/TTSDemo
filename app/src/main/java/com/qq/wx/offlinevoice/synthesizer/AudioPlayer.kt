package com.qq.wx.offlinevoice.synthesizer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * 基于“播放协程 + Channel 队列”的音频播放器
 * - 预缓冲 + 自动回填
 * - Marker 在播放协程中顺序执行
 * - 软重置：清空队列并 flush 掉 AudioTrack 内部缓冲，立刻切换到新配置
 * - 控制消息拥有最高优先级（独立 controlChannel + select）
 * - 引入 generation：软重启后自动丢弃旧代的 PCM/Marker，避免跳句与错位回调
 */
class AudioPlayer(
    private val sampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE,
    private val queueCapacity: Int = 256
) {

    private sealed class QueueItem(open val gen: Long) {
        data class Pcm(
            override val gen: Long,
            val data: ShortArray,
            val offset: Int = 0,
            val length: Int = data.size
        ) : QueueItem(gen) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Pcm
                if (gen != other.gen) return false
                if (offset != other.offset) return false
                if (length != other.length) return false
                if (!data.contentEquals(other.data)) return false
                return true
            }
            override fun hashCode(): Int {
                var result = gen.hashCode()
                result = 31 * result + offset
                result = 31 * result + length
                result = 31 * result + data.contentHashCode()
                return result
            }
        }
        data class Marker(override val gen: Long, val onReached: (() -> Unit)? = null) : QueueItem(gen)
    }

    private data class Control(val resetPrerollMs: Int? = null)

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    // 队列
    private var pcmChannel: Channel<QueueItem> = Channel(queueCapacity)
    private var controlChannel: Channel<Control> = Channel(Channel.CONFLATED)

    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    @Volatile private var isPaused = false
    @Volatile private var isStopped = true
    @Volatile private var currentVolume: Float = 1.0f

    // 缓冲与回填
    @Volatile private var prerollSamples: Int = msToSamples(250)
    @Volatile private var lowWatermarkSamples: Int = msToSamples(120)
    @Volatile private var highWatermarkSamples: Int = msToSamples(300)
    @Volatile private var autoRebufferEnabled: Boolean = true
    private val bufferedSamples = AtomicLong(0)
    @Volatile private var waitingForPreroll = true

    // 软清空请求 & 代次
    @Volatile private var clearRequested: Boolean = false
    @Volatile private var clearPrerollMs: Int? = null
    @Volatile private var generation: Long = 0L

    companion object {
        private const val TAG = "AudioPlayer"
    }

    fun configureBuffering(
        prerollMs: Int = 250,
        lowWatermarkMs: Int = 120,
        highWatermarkMs: Int = 300,
        autoRebuffer: Boolean = true
    ) {
        prerollSamples = msToSamples(prerollMs)
        lowWatermarkSamples = msToSamples(lowWatermarkMs)
        highWatermarkSamples = msToSamples(highWatermarkMs)
        autoRebufferEnabled = autoRebuffer
        Log.d(TAG, "Buffering configured: preroll=${prerollMs}ms, low=${lowWatermarkMs}ms, high=${highWatermarkMs}ms, auto=$autoRebuffer")
    }

    private fun msToSamples(ms: Int): Int = (ms.toLong() * sampleRate / 1000L).toInt().coerceAtLeast(0)

    fun startIfNeeded(volume: Float = 1.0f) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (playbackJob?.isActive == true) return

        bufferedSamples.set(0)
        waitingForPreroll = true
        isStopped = false
        clearRequested = false
        clearPrerollMs = null
        generation = 0L

        // 重建队列
        pcmChannel.close(); controlChannel.close()
        pcmChannel = Channel(queueCapacity)
        controlChannel = Channel(Channel.CONFLATED)

        playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            var minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                Log.w(TAG, "Invalid buffer size, using fallback: ${TtsConstants.MIN_BUFFER_SIZE_FALLBACK}")
                minBufferSize = TtsConstants.MIN_BUFFER_SIZE_FALLBACK
            }
            val bufferSizeBytes = (minBufferSize * 4).coerceAtLeast(minBufferSize)

            try {
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSizeBytes,
                    AudioTrack.MODE_STREAM
                ).also { at ->
                    at.setStereoVolume(currentVolume, currentVolume)
                    at.pause()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating AudioTrack", e)
                isStopped = true
                return@launch
            }

            try {
                while (isActive && !isStopped) {
                    // 优先处理控制消息（立刻生效）
                    select<Unit> {
                        controlChannel.onReceiveCatching { res ->
                            if (res.isSuccess) {
                                val ctrl = res.getOrNull()
                                clearRequested = true
                                clearPrerollMs = ctrl?.resetPrerollMs
                                // bump generation 并清空
                                generation += 1
                                applyClearNow()
                            }
                        }
                        pcmChannel.onReceive { item ->
                            // 丢弃老代次项
                            if (item.gen != generation) {
                                if (item is QueueItem.Pcm) {
                                    bufferedSamples.addAndGet(-item.length.toLong())
                                }
                                return@onReceive
                            }

                            // 用户暂停
                            while (isPaused && isActive && !isStopped) {
                                delay(5)
                            }
                            if (!isActive || isStopped) return@onReceive

                            when (item) {
                                is QueueItem.Pcm -> {
                                    maybeAutoRebuffer()
                                    maybeStartAfterPreroll()
                                    val fully = writePcmBlocking(item)
                                    if (fully) {
                                        bufferedSamples.addAndGet(-item.length.toLong())
                                    }
                                }
                                is QueueItem.Marker -> {
                                    // 再次校验代次，保障一致
                                    if (item.gen == generation) {
                                        try { item.onReached?.invoke() } catch (cb: Throwable) {
                                            Log.w(TAG, "Marker callback error", cb)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { audioTrack?.stop() }
                runCatching { audioTrack?.release() }
                audioTrack = null
                isStopped = true
            }
        }
    }

    // 软重置（高优先级）：不销毁 AudioTrack，清空内部与队列，立刻切换配置
    suspend fun softReset(prerollMs: Int? = null) {
        if (isStopped) return
        val sent = controlChannel.trySend(Control(prerollMs)).isSuccess
        if (!sent) controlChannel.send(Control(prerollMs))
    }

    suspend fun enqueuePcm(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size) {
        if (isStopped) return
        val item = QueueItem.Pcm(gen = generation, data = pcm, offset = offset, length = length)
        bufferedSamples.addAndGet(length.toLong())
        pcmChannel.send(item)
    }

    suspend fun enqueueMarker(onReached: (() -> Unit)? = null) {
        if (isStopped) return
        val item = QueueItem.Marker(gen = generation, onReached = onReached)
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
            Log.d(TAG, "Audio paused (user)")
        }
    }

    fun resume() {
        if (!isStopped && isPaused) {
            isPaused = false
            if (!waitingForPreroll) {
                runCatching { audioTrack?.play() }
            }
            Log.d(TAG, "Audio resumed (user)")
        }
    }

    fun stopAndRelease() {
        isStopped = true
        isPaused = false

        pcmChannel.close()
        controlChannel.close()
        playbackJob?.cancel()
        playbackJob = null

        bufferedSamples.set(0)
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null

        Log.d(TAG, "Audio stopped and released")
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun maybeStartAfterPreroll() {
        if (waitingForPreroll) {
            while (!isStopped && !pcmChannel.isClosedForSend && bufferedSamples.get() < prerollSamples) {
                delay(5)
            }
            if (!isStopped) {
                runCatching { audioTrack?.play() }
                waitingForPreroll = false
                Log.d(TAG, "Preroll reached, start playing. bufferedMs=${samplesToMs(bufferedSamples.get())}")
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun maybeAutoRebuffer() {
        if (!autoRebufferEnabled || waitingForPreroll) return
        if (bufferedSamples.get() < lowWatermarkSamples) {
            runCatching { audioTrack?.pause() }
            waitingForPreroll = true
            Log.d(TAG, "Low watermark hit, auto rebuffer. bufferedMs=${samplesToMs(bufferedSamples.get())}")
            while (!isStopped && !pcmChannel.isClosedForSend && bufferedSamples.get() < highWatermarkSamples) {
                delay(5)
            }
        }
    }

    // 立即执行清空：暂停 + flush + 清空队列 + 重置预缓冲门控
    private fun applyClearNow() {
        val at = audioTrack
        try {
            at?.pause()
            runCatching { at?.flush() } // 丢弃未播帧
        } catch (_: Throwable) { /* ignore */ }

        // 可选修改 preroll
        clearPrerollMs?.let { prerollSamples = msToSamples(it) }

        // 丢弃队列剩余项（包括老 Marker/PCM）
        var drained = 0L
        while (true) {
            val res = pcmChannel.tryReceive()
            if (res.isSuccess) {
                when (val dropped = res.getOrNull()) {
                    is QueueItem.Pcm -> drained += dropped.length
                    else -> { /* drop marker */ }
                }
            } else break
        }
        if (drained != 0L) bufferedSamples.addAndGet(-drained)

        waitingForPreroll = true
        clearRequested = false
        clearPrerollMs = null
        Log.d(TAG, "Soft clear applied: drainedSamples=$drained, waitingForPreroll=$waitingForPreroll, gen=$generation")
    }

    // 返回是否完整写入；若中途被清空，内部会扣减剩余样本数并返回 false
    private suspend fun writePcmBlocking(item: QueueItem.Pcm): Boolean {
        val at = audioTrack ?: return false
        var writtenTotal = 0
        val target = item.length
        val data = item.data
        val base = item.offset

        return withContext(Dispatchers.IO) {
            while (writtenTotal < target && !isStopped && isActive) {
                // 若期间来了清空请求，立刻停止写入并修正计数
                if (clearRequested) {
                    val remain = target - writtenTotal
                    if (remain > 0) bufferedSamples.addAndGet(-remain.toLong())
                    try {
                        at.pause()
                        runCatching { at.flush() }
                    } catch (_: Throwable) {}
                    waitingForPreroll = true
                    return@withContext false
                }

                // 用户暂停
                while (isPaused && isActive && !isStopped) {
                    delay(5)
                }
                if (isStopped) return@withContext false

                val toWrite = target - writtenTotal
                val written = try {
                    at.write(data, base + writtenTotal, toWrite)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack write error", e)
                    return@withContext false
                }

                if (written > 0) {
                    writtenTotal += written
                } else if (written == AudioTrack.ERROR_INVALID_OPERATION || written == AudioTrack.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioTrack write error code: $written")
                    return@withContext false
                } else {
                    delay(1)
                }
            }
            true
        }
    }

    private fun samplesToMs(samples: Long): Long = samples * 1000L / sampleRate
}