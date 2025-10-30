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
import kotlin.coroutines.coroutineContext

/**
 * 高健壮性的音频播放器，基于协程和 Channel 构建。
 *
 * 核心特性:
 * 1.  **命令驱动**: 通过一个高优先级的 `controlChannel` 接收控制命令（如重置），响应非常迅速。
 * 2.  **非阻塞写入**: 使用 `AudioTrack.WRITE_NON_BLOCKING`，确保播放协程不会被底层音频缓冲区阻塞，时刻保持对控制命令的响应能力。
 * 3.  **代次(Generation)机制**: 通过 `generation` 计数器，在软重置后能自动丢弃所有旧的音频数据和回调（Marker），从根本上避免了音频错乱和回调错误的问题。
 * 4.  **同步清理**: 提供了 `stopAndReleaseBlocking` 和 `resetBlocking` 等挂起函数，允许调用者（如 TtsSynthesizer）可以同步等待播放器完成清理或重置，彻底解决了竞态条件。
 * 5.  **硬重置**: 在软重置时，通过销毁并重建 `AudioTrack` 实例来强制清空硬件缓冲区，实现真正的“立即停止”，解决了音频残留问题。
 */
class AudioPlayer(
    private val sampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE,
    private val queueCapacity: Int = 256
) {

    /**
     * 队列中的项目，可以是音频数据(Pcm)或回调标记(Marker)。
     * 所有项目都携带 `gen` (代次)信息。
     */
    private sealed class QueueItem(open val gen: Long) {
        data class Pcm(
            override val gen: Long,
            val data: ShortArray,
            val offset: Int = 0,
            val length: Int = data.size
        ) : QueueItem(gen) {
            // 注意：必须手动实现 equals 和 hashCode，因为 ShortArray 的默认比较是基于引用的。
            // 我们需要基于内容进行比较，以确保数据类的正确行为。
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

    /**
     * 内部控制命令。
     * @param resetPrerollMs 可选的新的预缓冲时间。
     * @param ack 用于实现同步等待的应答信号。
     */
    private data class Control(
        val resetPrerollMs: Int? = null,
        val ack: CompletableDeferred<Unit>? = null
    )

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var pcmChannel: Channel<QueueItem> = Channel(queueCapacity)
    private var controlChannel: Channel<Control> = Channel(Channel.CONFLATED) // CONFLATED确保最新的控制命令不会丢失

    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    @Volatile private var isPaused = false
    @Volatile private var isStopped = true
    @Volatile private var currentVolume: Float = 1.0f

    // 缓冲相关参数
    @Volatile private var prerollSamples: Int = msToSamples(250)
    @Volatile private var lowWatermarkSamples: Int = msToSamples(120)
    @Volatile private var highWatermarkSamples: Int = msToSamples(300)
    @Volatile private var autoRebufferEnabled: Boolean = true
    private val bufferedSamples = AtomicLong(0)
    @Volatile private var waitingForPreroll = true

    // 代次计数器，每次重置时递增，用于区分新旧数据
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

        // CoroutineStart.UNDISPATCHED 确保协程立即在当前线程开始执行，直到第一个挂起点，可以略微减少启动延迟。
        playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

            audioTrack = createAudioTrack()
            if (audioTrack == null) {
                Log.e(TAG, "Failed to create AudioTrack initially.")
                isStopped = true
                return@launch
            }

            try {
                // select 表达式确保了 controlChannel 的优先级高于 pcmChannel。
                // 这意味着即使队列中有大量音频数据，重置等控制命令也能被立即处理。
                while (isActive && !isStopped) {
                    select<Unit> {
                        // 优先处理控制命令
                        controlChannel.onReceive { control ->
                            Log.d(TAG, "Control message received. Applying soft reset. Gen bump to ${generation + 1}")
                            // 1. 提升代次，所有后续收到的旧代次数据都将被丢弃
                            generation++
                            // 2. 销毁并重建 AudioTrack，实现硬停止，清空所有硬件缓冲
                            recreateAudioTrack()

                            // 3. 清空软件队列 (pcmChannel)
                            var drainedSamples = 0L
                            while (true) {
                                val item = pcmChannel.tryReceive().getOrNull() ?: break
                                if (item is QueueItem.Pcm) drainedSamples += item.length
                            }
                            if (drainedSamples > 0) bufferedSamples.addAndGet(-drainedSamples)
                            Log.d(TAG, "Channel drained, removed samples: $drainedSamples")

                            // 4. 重置内部状态，准备接收新一代的数据
                            waitingForPreroll = true
                            isPaused = false
                            control.resetPrerollMs?.let { prerollSamples = msToSamples(it) }

                            // 5. 如果有应答请求，发出完成信号
                            control.ack?.complete(Unit)
                        }

                        // 处理音频数据和回调标记
                        pcmChannel.onReceive { item ->
                            // 校验代次，丢弃旧数据
                            if (item.gen != generation) {
                                if (item is QueueItem.Pcm) bufferedSamples.addAndGet(-item.length.toLong())
                                return@onReceive
                            }

                            // 循环等待，直到暂停状态结束或被更高优先级的控制命令中断
                            while (isPaused && isActive && !isStopped && item.gen == generation) {
                                delay(10)
                            }
                            if (!isActive || isStopped || item.gen != generation) { // 再次检查，防止在暂停时被重置
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
                                    if (item.gen == generation) { // 再次校验代次，确保回调正确
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
                // 确保协程结束时，AudioTrack 资源被彻底释放
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
            // 使用一个比最小建议值稍大的缓冲区，以减少音频下溢(underrun)的风险
            val bufferSizeBytes = (minBufferSize * 4).coerceAtLeast(minBufferSize)

            AudioTrack(
                AudioManager.STREAM_MUSIC, // 音频流类型
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSizeBytes,
                AudioTrack.MODE_STREAM // 流模式，适合播放实时生成的音频
            ).also { at ->
                at.setStereoVolume(currentVolume, currentVolume)
                at.pause() // 创建后立即暂停，等待预缓冲完成
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioTrack", e)
            null
        }
    }

    private fun releaseAudioTrack() {
        // 使用 runCatching 确保即使一个调用失败，另一个也能被尝试执行
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

    /**
     * 发送重置命令并挂起，直到播放器确认重置完成。
     * 这使得上层调用者可以同步地知道播放器已处于一个完全干净的状态。
     */
    suspend fun resetBlocking(prerollMs: Int? = null) {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(prerollMs, ack))
        ack.await() // 等待播放器协程完成重置并调用 ack.complete()
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
            // 只有在预缓冲完成后才真正播放
            if (!waitingForPreroll) {
                runCatching { audioTrack?.play() }
            }
            Log.d(TAG, "Audio resumed (user)")
        }
    }

    /**
     * 非挂起版本的停止方法。仅发出取消请求，立即返回（“发射后不管”）。
     * 适用于不需要等待清理完成的场景。
     */
    fun stopAndRelease() {
        if (isStopped) return
        isStopped = true
        isPaused = false
        playbackJob?.cancel()
        playbackJob = null
        pcmChannel.close()
        controlChannel.close()
        bufferedSamples.set(0)
        Log.d(TAG, "AudioPlayer stopAndRelease (fire-and-forget) called.")
    }

    /**
     * 挂起版本的停止方法，会取消并等待播放任务完全终止。
     * 这是确保资源被彻底释放的同步方法，对于需要立即重建播放器的场景至关重要。
     */
    suspend fun stopAndReleaseBlocking() {
        if (isStopped) return
        val jobToJoin = playbackJob

        isStopped = true
        isPaused = false

        // cancelAndJoin 是关键，它会挂起直到协程完全结束，包括其 finally 块中的清理逻辑。
        jobToJoin?.cancelAndJoin()

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
        while (written < length && !isStopped && coroutineContext.isActive) {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                at.write(data, offset + written, length - written, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                at.write(data, offset + written, length - written)
            }

            if (result > 0) {
                written += result
            } else if (result == 0) {
                // 缓冲区已满，短暂等待后重试，并让出CPU给其他协程
                delay(5)
            } else {
                Log.e(TAG, "AudioTrack write error: $result")
                break
            }
        }
    }

    private fun samplesToMs(samples: Long): Long = samples * 1000L / sampleRate
}