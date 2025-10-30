package com.qq.wx.offlinevoice.synthesizer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
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

/**
 * 基于“播放协程 + Channel 队列”的音频播放器 (最终健壮版)
 *
 * [最终修复] 新增 stopAndReleaseBlocking() 方法，使用 cancelAndJoin 实现同步停止，
 * 确保调用者可以等待播放器完全释放资源后再继续执行。
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

    private data class Control(
        val resetPrerollMs: Int? = null,
        val ack: CompletableDeferred<Unit>? = null
    )

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var pcmChannel: Channel<QueueItem> = Channel(queueCapacity)
    private var controlChannel: Channel<Control> = Channel(Channel.CONFLATED)

    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    @Volatile private var isPaused = false
    @Volatile private var isStopped = true
    @Volatile private var currentVolume: Float = 1.0f
    @Volatile private var prerollSamples: Int = msToSamples(250)
    @Volatile private var lowWatermarkSamples: Int = msToSamples(120)
    @Volatile private var highWatermarkSamples: Int = msToSamples(300)
    @Volatile private var autoRebufferEnabled: Boolean = true
    private val bufferedSamples = AtomicLong(0)
    @Volatile private var waitingForPreroll = true
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
    }

    private fun msToSamples(ms: Int): Int = (ms.toLong() * sampleRate / 1000L).toInt().coerceAtLeast(0)

    fun startIfNeeded(volume: Float = 1.0f) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (playbackJob?.isActive == true) return

        bufferedSamples.set(0)
        waitingForPreroll = true
        isStopped = false
        generation = 0L

        pcmChannel.close(); controlChannel.close()
        pcmChannel = Channel(queueCapacity)
        controlChannel = Channel(Channel.CONFLATED)

        playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

            audioTrack = createAudioTrack()
            if (audioTrack == null) {
                Log.e(TAG, "Failed to create AudioTrack initially.")
                isStopped = true
                return@launch
            }

            try {
                while (isActive && !isStopped) {
                    select<Unit> {
                        controlChannel.onReceive { control ->
                            Log.d(TAG, "Control message received. Applying soft reset. Gen bump to ${generation + 1}")
                            generation++
                            recreateAudioTrack()

                            var drainedSamples = 0L
                            while (true) {
                                val item = pcmChannel.tryReceive().getOrNull() ?: break
                                if (item is QueueItem.Pcm) drainedSamples += item.length
                            }
                            if (drainedSamples > 0) bufferedSamples.addAndGet(-drainedSamples)
                            Log.d(TAG, "Channel drained, removed samples: $drainedSamples")

                            waitingForPreroll = true
                            isPaused = false
                            control.resetPrerollMs?.let { prerollSamples = msToSamples(it) }

                            control.ack?.complete(Unit)
                        }

                        pcmChannel.onReceive { item ->
                            if (item.gen != generation) {
                                if (item is QueueItem.Pcm) bufferedSamples.addAndGet(-item.length.toLong())
                                return@onReceive
                            }

                            while (isPaused && isActive && !isStopped && item.gen == generation) {
                                delay(10)
                            }
                            if (!isActive || isStopped || item.gen != generation) {
                                if (item is QueueItem.Pcm) bufferedSamples.addAndGet(-item.length.toLong())
                                return@onReceive
                            }

                            when (item) {
                                is QueueItem.Pcm -> {
                                    maybeAutoRebuffer()
                                    maybeStartAfterPreroll()
                                    writePcmNonBlocking(item.data, item.offset, item.length)
                                    bufferedSamples.addAndGet(-item.length.toLong())
                                }
                                is QueueItem.Marker -> {
                                    if (item.gen == generation) {
                                        try { item.onReached?.invoke() } catch (t: Throwable) {
                                            Log.w(TAG, "Marker callback error", t)
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
    }

    private fun createAudioTrack(): AudioTrack? {
        return try {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            var minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                minBufferSize = TtsConstants.MIN_BUFFER_SIZE_FALLBACK
            }
            val bufferSizeBytes = (minBufferSize * 4).coerceAtLeast(minBufferSize)

            AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat,
                bufferSizeBytes, AudioTrack.MODE_STREAM
            ).also { at ->
                at.setStereoVolume(currentVolume, currentVolume)
                at.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioTrack", e)
            null
        }
    }

    private fun releaseAudioTrack() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }



    private fun recreateAudioTrack() {
        releaseAudioTrack()
        audioTrack = createAudioTrack()
        if (audioTrack == null) {
            Log.e(TAG, "Failed to recreate AudioTrack. Stopping playback.")
            isStopped = true
        }
    }

    suspend fun resetBlocking(prerollMs: Int? = null) {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(prerollMs, ack))
        ack.await()
        Log.d(TAG, "Reset acknowledged by AudioPlayer.")
    }

    suspend fun enqueuePcm(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size) {
        if (isStopped || length <= 0) return
        val currentGen = generation
        val item = QueueItem.Pcm(gen = currentGen, data = pcm, offset = offset, length = length)
        bufferedSamples.addAndGet(length.toLong())
        pcmChannel.send(item)
    }

    suspend fun enqueueMarker(onReached: (() -> Unit)? = null) {
        if (isStopped) return
        val currentGen = generation
        val item = QueueItem.Marker(gen = currentGen, onReached = onReached)
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

    /**
     * [修改点] 非挂起版本，仅发出取消请求，立即返回（“发射后不管”）。
     */
    fun stopAndRelease() {
        if (isStopped) return
        isStopped = true
        isPaused = false
        playbackJob?.cancel()
        playbackJob = null // 立即置空
        pcmChannel.close()
        controlChannel.close()
        bufferedSamples.set(0)
        Log.d(TAG, "AudioPlayer stopAndRelease (fire-and-forget) called.")
    }

    /**
     * [新增] 挂起版本，会取消并等待播放任务完全终止。
     * 这是确保资源被彻底释放的同步方法。
     */
    suspend fun stopAndReleaseBlocking() {
        if (isStopped) return
        val jobToJoin = playbackJob

        isStopped = true
        isPaused = false

        // 取消任务。任务的 finally 块会负责释放 AudioTrack。
        // cancelAndJoin 是关键，它会等待协程完全结束。
        jobToJoin?.cancelAndJoin()

        // 确认任务死亡后，我们可以安全地清理其他资源。
        playbackJob = null
        pcmChannel.close()
        controlChannel.close()
        bufferedSamples.set(0)
        Log.d(TAG, "AudioPlayer stopped and released synchronously.")
    }

    private suspend fun maybeStartAfterPreroll() {
        if (waitingForPreroll) {
            if (bufferedSamples.get() >= prerollSamples) {
                if (!isStopped && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    runCatching { audioTrack?.play() }
                    waitingForPreroll = false
                    Log.d(TAG, "Preroll reached, start playing. bufferedMs=${samplesToMs(bufferedSamples.get())}")
                }
            }
        }
    }

    private suspend fun maybeAutoRebuffer() {
        if (!autoRebufferEnabled || waitingForPreroll) return
        if (bufferedSamples.get() < lowWatermarkSamples) {
            runCatching { audioTrack?.pause() }
            waitingForPreroll = true
            Log.d(TAG, "Low watermark hit, auto rebuffer. bufferedMs=${samplesToMs(bufferedSamples.get())}")
            while (!isStopped && bufferedSamples.get() < highWatermarkSamples) {
                delay(10)
            }
        }
    }

    private suspend fun writePcmNonBlocking(data: ShortArray, offset: Int, length: Int) {
        val at = audioTrack ?: return
        var written = 0
        while (written < length && !isStopped) {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                at.write(data, offset + written, length - written, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                at.write(data, offset + written, length - written)
            }

            if (result > 0) {
                written += result
            } else if (result == 0) {
                delay(5)
            } else {
                Log.e(TAG, "AudioTrack write error: $result")
                break
            }
        }
    }

    private fun samplesToMs(samples: Long): Long = samples * 1000L / sampleRate
}