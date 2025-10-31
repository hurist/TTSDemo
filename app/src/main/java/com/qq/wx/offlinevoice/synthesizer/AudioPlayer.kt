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
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AudioPlayer(
    // 注意：默认采样率现在只是一个初始值
    private val initialSampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE,
    private val queueCapacity: Int = 256
) {
    private sealed class QueueItem(open val gen: Long) {
        // Pcm 类现在携带采样率信息
        data class Pcm(
            override val gen: Long,
            val data: ShortArray,
            val offset: Int = 0,
            val length: Int = data.size,
            val sampleRate: Int,
            val source: SynthesisMode
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
                if (!data.contentEquals(other.data)) return false
                return true
            }
            override fun hashCode(): Int {
                var result = gen.hashCode()
                result = 31 * result + offset
                result = 31 * result + length
                result = 31 * result + sampleRate
                result = 31 * result + source.hashCode()
                result = 31 * result + data.contentHashCode()
                return result
            }
        }
        data class Marker(override val gen: Long, val onReached: (() -> Unit)? = null) : QueueItem(gen)
    }

    private data class Control(val ack: CompletableDeferred<Unit>? = null)

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

    // 缓冲配置方法现在不再需要，因为缓冲大小由AudioTrack动态管理
    // fun configureBuffering(...) {}

    fun startIfNeeded(volume: Float = 1.0f) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (playbackJob?.isActive == true) return

        isStopped = false
        generation = 0L
        currentSampleRate = initialSampleRate // 重置为初始采样率
        currentPlaybackSource = null

        pcmChannel.close(); controlChannel.close()
        pcmChannel = Channel(queueCapacity)
        controlChannel = Channel(Channel.CONFLATED)

        playbackJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

            // 初始时不创建 AudioTrack，等待第一个 PCM 数据到达
            audioTrack = null

            try {
                while (isActive && !isStopped) {
                    select<Unit> {
                        controlChannel.onReceive { control ->
                            Log.d(TAG, "收到重置命令。代次更新至 ${generation + 1}")
                            generation++
                            // 重置时，销毁现有 AudioTrack
                            releaseAudioTrack()
                            currentPlaybackSource = null

                            // 清空软件队列
                            while (pcmChannel.tryReceive().isSuccess) { /* drain */ }
                            Log.d(TAG, "播放队列已清空。")

                            control.ack?.complete(Unit)
                        }

                        pcmChannel.onReceive { item ->
                            if (item.gen != generation) return@onReceive

                            while (isPaused && isActive && !isStopped && item.gen == generation) {
                                delay(10)
                            }
                            if (!isActive || isStopped || item.gen != generation) return@onReceive

                            when (item) {
                                is QueueItem.Pcm -> {
                                    // 检查采样率是否需要切换
                                    if (audioTrack == null || item.sampleRate != currentSampleRate) {
                                        switchSampleRate(item.sampleRate)
                                    }

                                    if (currentPlaybackSource != item.source) {
                                        currentPlaybackSource = item.source
                                        Log.i(TAG, ">>> 开始播放来自 [${item.source}] 的音频 (采样率: ${item.sampleRate} Hz) <<<")
                                    }

                                    audioTrack?.play() // 确保已启动播放
                                    writePcmBlocking(item.data, item.offset, item.length)
                                }
                                is QueueItem.Marker -> {
                                    // 在执行回调前，确保之前的所有音频都已播放完毕
                                    audioTrack?.let { track ->
                                        // 这是一个简化的同步点，实际效果取决于缓冲区大小
                                        // 对于精确回调，需要更复杂的机制
                                        Log.d(TAG, "处理回调标记，等待播放缓冲区排空...")
                                    }
                                    if (item.gen == generation) {
                                        try {
                                            // 回调切换到主线程或默认线程执行，避免阻塞IO线程
                                            withContext(Dispatchers.Default) {
                                                item.onReached?.invoke()
                                            }
                                        } catch (t: Throwable) {
                                            Log.w(TAG, "执行 Marker 回调时出错", t)
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

    /**
     * 切换 AudioTrack 的采样率。这是一个挂起函数，会安全地销毁旧实例并创建新实例。
     */
    private suspend fun switchSampleRate(newSampleRate: Int) {
        Log.i(TAG, "采样率切换: 从 $currentSampleRate Hz -> $newSampleRate Hz")
        releaseAudioTrack() // 安全地释放旧的 AudioTrack
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
        // 使用 withContext 确保这些非挂起调用在正确的调度器上运行
        withContext(Dispatchers.IO) {
            audioTrack?.let {
                Log.d(TAG, "正在释放 AudioTrack (采样率: ${it.sampleRate} Hz)...")
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
            audioTrack = null
        }
    }

    suspend fun resetBlocking() {
        if (isStopped || playbackJob?.isActive != true) return
        val ack = CompletableDeferred<Unit>()
        controlChannel.send(Control(ack))
        ack.await()
        Log.d(TAG, "AudioPlayer 已确认重置完成。")
    }

    suspend fun enqueuePcm(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size, sampleRate: Int, source: SynthesisMode) {
        if (isStopped || length <= 0) return
        val currentGen = generation
        val item = QueueItem.Pcm(currentGen, pcm, offset, length, sampleRate, source)
        pcmChannel.send(item)
    }

    suspend fun enqueueMarker(onReached: (() -> Unit)? = null) {
        if (isStopped) return
        val currentGen = generation
        val item = QueueItem.Marker(currentGen, onReached)
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
        jobToJoin?.cancelAndJoin()
        playbackJob = null
        pcmChannel.close()
        controlChannel.close()
        Log.d(TAG, "AudioPlayer 已同步停止并释放资源。")
    }

    // 改为阻塞写入，因为非阻塞在快速切换采样率时会变得复杂
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