package com.qq.wx.offlinevoice.synthesizer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于“播放线程 + 队列”的音频播放器
 * - 新增：预缓冲(preroll)与自动回填(auto-rebuffer)以避免低端机合成跟不上导致的卡顿
 * - 保持不变：Marker 会在播放线程中按顺序执行，用于句首/句末回调
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
        ) : QueueItem()
        data class Marker(val onReached: (() -> Unit)? = null) : QueueItem()
    }

    private val queue = LinkedBlockingQueue<QueueItem>(queueCapacity)

    @Volatile
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    // 用户层暂停
    @Volatile
    private var isPaused = false

    // 播放器是否停止
    @Volatile
    private var isStopped = true

    private val isStarted = AtomicBoolean(false)
    private val pauseLock = Object()

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
        Log.d(TAG, "Buffering configured: preroll=${prerollMs}ms, low=${lowWatermarkMs}ms, high=${highWatermarkMs}ms, auto=$autoRebuffer")
    }

    private fun msToSamples(ms: Int): Int {
        // 单声道 16-bit
        return (ms.toLong() * sampleRate / 1000L).toInt().coerceAtLeast(0)
    }

    fun startIfNeeded(volume: Float = 1.0f) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (isStarted.compareAndSet(false, true)) {
            // 重置计数与门控
            bufferedSamples.set(0)
            waitingForPreroll = true
            isStopped = false
            startPlaybackThread()
        }
    }

    fun enqueuePcm(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size) {
        if (isStopped) return
        // 累积可用样本数
        bufferedSamples.addAndGet(length.toLong())
        queue.put(QueueItem.Pcm(pcm, offset, length))
    }

    fun enqueueMarker(onReached: (() -> Unit)? = null) {
        if (isStopped) return
        queue.put(QueueItem.Marker(onReached))
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        currentVolume = v
        audioTrack?.setStereoVolume(v, v)
    }

    fun pause() {
        if (!isStopped && !isPaused) {
            isPaused = true
            audioTrack?.pause()
            Log.d(TAG, "Audio paused (user)")
        }
    }

    fun resume() {
        if (!isStopped && isPaused) {
            isPaused = false
            synchronized(pauseLock) {
                pauseLock.notifyAll()
            }
            // 若仍在等待预缓冲，不立即播放；达到阈值后自动播放
            if (!waitingForPreroll) {
                audioTrack?.play()
            }
            Log.d(TAG, "Audio resumed (user)")
        }
    }

    fun stopAndRelease() {
        isStopped = true
        isPaused = false
        playbackThread?.interrupt()
        playbackThread = null

        queue.clear()
        bufferedSamples.set(0)

        audioTrack?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack already stopped")
            }
            it.release()
        }
        audioTrack = null
        isStarted.set(false)
        Log.d(TAG, "Audio stopped and released")
    }

    private fun startPlaybackThread() {
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

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
                return@Thread
            }

            while (!Thread.currentThread().isInterrupted && !isStopped) {
                try {
                    // 用户暂停
                    if (isPaused) {
                        synchronized(pauseLock) {
                            while (isPaused && !isStopped) pauseLock.wait()
                        }
                        if (isStopped) break
                    }

                    // 取项播放
                    val item = queue.take()
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
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "Playback loop error", t)
                    break
                }
            }

            try {
                audioTrack?.let {
                    try {
                        it.stop()
                    } catch (e: IllegalStateException) {
                        // ignore
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack", e)
            } finally {
                audioTrack = null
            }

            isStopped = true
            isStarted.set(false)
        }, "AudioPlaybackQueueThread")

        playbackThread?.start()
    }

    private fun maybeStartAfterPreroll() {
        if (waitingForPreroll) {
            // 等到缓冲累计达到预设阈值
            while (!isStopped && !Thread.currentThread().isInterrupted && bufferedSamples.get() < prerollSamples) {
                Thread.sleep(5)
            }
            if (!isStopped) {
                audioTrack?.play()
                waitingForPreroll = false
                Log.d(TAG, "Preroll reached, start playing. bufferedMs=${samplesToMs(bufferedSamples.get())}")
            }
        }
    }

    private fun maybeAutoRebuffer() {
        if (!autoRebufferEnabled || waitingForPreroll) return
        if (bufferedSamples.get() < lowWatermarkSamples) {
            // 进入回填等待
            audioTrack?.pause()
            waitingForPreroll = true
            Log.d(TAG, "Low watermark hit, auto rebuffer. bufferedMs=${samplesToMs(bufferedSamples.get())}")
            // 达到高水位再恢复
            while (!isStopped && !Thread.currentThread().isInterrupted && bufferedSamples.get() < highWatermarkSamples) {
                Thread.sleep(5)
            }
        }
    }

    private fun writePcmBlocking(item: QueueItem.Pcm) {
        val at = audioTrack ?: return
        var writtenTotal = 0
        val target = item.length
        val data = item.data
        val base = item.offset

        while (writtenTotal < target && !isStopped && !Thread.currentThread().isInterrupted) {
            if (isPaused) {
                synchronized(pauseLock) {
                    while (isPaused && !isStopped) pauseLock.wait()
                }
                if (isStopped) break
                if (!waitingForPreroll) at.play()
            }

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
                Thread.yield()
            }
        }
    }

    private fun samplesToMs(samples: Long): Long {
        return samples * 1000L / sampleRate
    }
}