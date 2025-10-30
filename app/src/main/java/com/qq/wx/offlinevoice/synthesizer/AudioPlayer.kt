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
import kotlinx.coroutines.withContext

/**
 * 基于“播放协程 + Channel 队列”的音频播放器
 * - 预缓冲(preroll)与自动回填(auto-rebuffer)避免低端机合成跟不上导致的卡顿
 * - Marker 在播放协程中顺序执行，用于句首/句末回调
 */
class AudioPlayer(
    private val sampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE,
    private val queueCapacity: Int = 256
) {

    private sealed class QueueItem {
        data class Pcm(
            val data: ShortArray,
            val offset: Int = 0,
            val length: Int = data.size
        ) : QueueItem() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Pcm

                if (offset != other.offset) return false
                if (length != other.length) return false
                if (!data.contentEquals(other.data)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = offset
                result = 31 * result + length
                result = 31 * result + data.contentHashCode()
                return result
            }
        }

        data class Marker(val onReached: (() -> Unit)? = null) : QueueItem()
    }

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    // 播放队列（协程 Channel）
    private var channel: Channel<QueueItem> = Channel(queueCapacity)

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var playbackJob: Job? = null

    // 用户层暂停
    @Volatile
    private var isPaused = false

    // 播放器是否停止
    @Volatile
    private var isStopped = true

    @Volatile
    private var currentVolume: Float = 1.0f

    // 预缓冲与回填策略（单位：样本数）
    @Volatile private var prerollSamples: Int = msToSamples(250)
    @Volatile private var lowWatermarkSamples: Int = msToSamples(120)
    @Volatile private var highWatermarkSamples: Int = msToSamples(300)
    @Volatile private var autoRebufferEnabled: Boolean = true

    // 队列中尚未写入（或正在写入）的样本数，用于大致评估可供写入的前馈数据量
    private val bufferedSamples = AtomicLong(0)

    // 内部门控：尚未达到预缓冲阈值时不启动播放
    @Volatile
    private var waitingForPreroll = true

    companion object {
        private const val TAG = "AudioPlayer"
    }

    /**
     * 配置缓冲策略（毫秒）
     */
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
        Log.d(
            TAG,
            "Buffering configured: preroll=${prerollMs}ms, low=${lowWatermarkMs}ms, high=${highWatermarkMs}ms, auto=$autoRebuffer"
        )
    }

    private fun msToSamples(ms: Int): Int {
        // 单声道 16-bit
        return (ms.toLong() * sampleRate / 1000L).toInt().coerceAtLeast(0)
    }

    fun startIfNeeded(volume: Float = 1.0f) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (playbackJob?.isActive == true) return

        // 重置计数与门控、队列与 Job
        bufferedSamples.set(0)
        waitingForPreroll = true
        isStopped = false

        // 重建 Channel，丢弃旧队列
        channel.close()
        channel = Channel(queueCapacity)

        playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            // 标记当前播放线程为 AUDIO 优先级
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            var minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                Log.w(TAG, "Invalid buffer size, using fallback: ${TtsConstants.MIN_BUFFER_SIZE_FALLBACK}")
                minBufferSize = TtsConstants.MIN_BUFFER_SIZE_FALLBACK
            }
            // 放大系统缓冲，降低下溢概率
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
                    // 初始不播放，等待预缓冲达到阈值
                    at.pause()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating AudioTrack", e)
                isStopped = true
                return@launch
            }

            try {
                for (item in channel) {
                    if (!isActive || isStopped) break

                    // 用户暂停
                    while (isPaused && isActive && !isStopped) {
                        delay(5)
                    }
                    if (!isActive || isStopped) break

                    when (item) {
                        is QueueItem.Pcm -> {
                            // 自动回填：如果缓冲不足，先暂停，等补充到高水位再继续
                            maybeAutoRebuffer()

                            // 预缓冲：首次启动时等到达到阈值再 play
                            maybeStartAfterPreroll()

                            writePcmBlocking(item)
                            // 完整写入后，扣减剩余可用样本计数
                            bufferedSamples.addAndGet(-item.length.toLong())
                        }
                        is QueueItem.Marker -> {
                            try {
                                item.onReached?.invoke()
                            } catch (cb: Throwable) {
                                Log.w(TAG, "Marker callback error", cb)
                            }
                        }
                    }
                }
            } finally {
                // 释放
                runCatching { audioTrack?.stop() }
                runCatching { audioTrack?.release() }
                audioTrack = null
                isStopped = true
            }
        }
    }

    suspend fun enqueuePcm(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size) {
        if (isStopped) return
        // 累积可用样本数
        bufferedSamples.addAndGet(length.toLong())
        channel.send(QueueItem.Pcm(pcm, offset, length))
    }

    suspend fun enqueueMarker(onReached: (() -> Unit)? = null) {
        if (isStopped) return
        channel.send(QueueItem.Marker(onReached))
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
            // 若仍在等待预缓冲，不立即播放；达到阈值后自动播放
            if (!waitingForPreroll) {
                runCatching { audioTrack?.play() }
            }
            Log.d(TAG, "Audio resumed (user)")
        }
    }

    fun stopAndRelease() {
        isStopped = true
        isPaused = false

        // 关闭队列并取消播放 Job
        channel.close()
        playbackJob?.cancel()
        playbackJob = null

        bufferedSamples.set(0)

        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null

        Log.d(TAG, "Audio stopped and released")
    }

    private suspend fun maybeStartAfterPreroll() {
        if (waitingForPreroll) {
            // 等到缓冲累计达到预设阈值
            while (!isStopped && !channel.isClosedForSend && bufferedSamples.get() < prerollSamples) {
                delay(5)
            }
            if (!isStopped) {
                runCatching { audioTrack?.play() }
                waitingForPreroll = false
                Log.d(
                    TAG,
                    "Preroll reached, start playing. bufferedMs=${samplesToMs(bufferedSamples.get())}"
                )
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun maybeAutoRebuffer() {
        if (!autoRebufferEnabled || waitingForPreroll) return
        if (bufferedSamples.get() < lowWatermarkSamples) {
            // 进入回填等待
            runCatching { audioTrack?.pause() }
            waitingForPreroll = true
            Log.d(
                TAG,
                "Low watermark hit, auto rebuffer. bufferedMs=${samplesToMs(bufferedSamples.get())}"
            )
            // 达到高水位再恢复
            while (!isStopped && !channel.isClosedForSend && bufferedSamples.get() < highWatermarkSamples) {
                delay(5)
            }
        }
    }

    private suspend fun writePcmBlocking(item: QueueItem.Pcm) {
        val at = audioTrack ?: return
        var writtenTotal = 0
        val target = item.length
        val data = item.data
        val base = item.offset

        withContext(Dispatchers.IO) {
            while (writtenTotal < target && !isStopped && isActive) {
                // 用户暂停
                while (isPaused && isActive && !isStopped) {
                    delay(5)
                }
                if (isStopped) break

                val toWrite = target - writtenTotal
                val written = try {
                    at.write(data, base + writtenTotal, toWrite)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack write error", e)
                    break
                }

                if (written > 0) {
                    writtenTotal += written
                } else if (written == AudioTrack.ERROR_INVALID_OPERATION || written == AudioTrack.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioTrack write error code: $written")
                    break
                } else {
                    // 0 or unknown, 让出一下
                    delay(1)
                }
            }
        }
    }

    private fun samplesToMs(samples: Long): Long {
        return samples * 1000L / sampleRate
    }
}