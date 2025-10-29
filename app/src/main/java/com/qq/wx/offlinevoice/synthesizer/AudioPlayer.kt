package com.qq.wx.offlinevoice.synthesizer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * 音频播放器 - 使用AudioTrack播放PCM数据
 * 支持暂停后从精确位置恢复播放
 */
class AudioPlayer(private val sampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE) {
    
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile
    private var isPaused = false
    @Volatile
    private var isStopped = false
    private var onCompletionListener: (() -> Unit)? = null
    
    // 保存当前播放的PCM数据和位置，用于暂停后恢复
    private var currentPcmData: ShortArray? = null
    @Volatile
    private var currentOffset: Int = 0
    private var currentChunkSize: Int = 0
    
    companion object {
        private const val TAG = "AudioPlayer"
    }
    
    /**
     * 开始播放PCM音频数据
     * @param pcmData 包含PCM采样的短整型数组
     * @param volume 音量级别 (0.0 到 1.0)
     * @param onCompletion 播放完成时调用的回调
     */
    fun play(pcmData: ShortArray, volume: Float = 1.0f, onCompletion: (() -> Unit)? = null) {
        stopAndRelease()
        
        if (pcmData.isEmpty()) {
            Log.w(TAG, "PCM data is empty, nothing to play")
            onCompletion?.invoke()
            return
        }
        
        // 保存当前播放数据，用于暂停后恢复
        this.currentPcmData = pcmData
        this.currentOffset = 0
        this.onCompletionListener = onCompletion
        isPaused = false
        isStopped = false
        
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        var minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        if (minBufferSize <= 0) {
            Log.w(TAG, "Invalid buffer size, using fallback: ${TtsConstants.MIN_BUFFER_SIZE_FALLBACK}")
            minBufferSize = TtsConstants.MIN_BUFFER_SIZE_FALLBACK
        }
        
        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize,
                AudioTrack.MODE_STREAM
            )
            
            // 设置音量匹配请求的级别
            val clampedVolume = volume.coerceIn(0.0f, 1.0f)
            audioTrack?.setStereoVolume(clampedVolume, clampedVolume)
            
            audioTrack?.play()
            
            currentChunkSize = maxOf(minBufferSize / 2, TtsConstants.CHUNK_SIZE_MIN)
            playbackThread = Thread({
                playPcmData(pcmData, currentChunkSize)
                // 通知播放完成
                if (!isStopped && !isPaused) {
                    onCompletionListener?.invoke()
                }
            }, "AudioPlaybackThread")
            
            playbackThread?.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio playback", e)
            stopAndRelease()
            onCompletion?.invoke()
        }
    }
    
    /**
     * 内部方法 - 将PCM数据写入AudioTrack
     * 支持暂停后从中断位置继续
     */
    private fun playPcmData(pcmData: ShortArray, chunkSize: Int) {
        var offset = currentOffset
        
        while (!Thread.currentThread().isInterrupted && !isStopped && offset < pcmData.size) {
            // 检查是否暂停
            if (isPaused) {
                // 保存当前位置
                currentOffset = offset
                Log.d(TAG, "Playback paused at offset: $offset/${pcmData.size}")
                break
            }
            
            val toWrite = minOf(chunkSize, pcmData.size - offset)
            val written = audioTrack?.write(pcmData, offset, toWrite) ?: 0
            
            when {
                written > 0 -> {
                    offset += written
                    currentOffset = offset // 实时更新当前位置
                }
                written == AudioTrack.ERROR_INVALID_OPERATION || 
                written == AudioTrack.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "AudioTrack write error: $written")
                    break
                }
            }
        }
        
        // 播放完成，重置位置
        if (offset >= pcmData.size) {
            currentOffset = 0
        }
    }
    
    /**
     * 暂停播放
     */
    fun pause() {
        if (!isPaused && audioTrack != null) {
            isPaused = true
            audioTrack?.pause()
            Log.d(TAG, "Audio paused at position: $currentOffset")
        }
    }
    
    /**
     * 恢复播放 - 从暂停的位置继续
     */
    fun resume() {
        if (isPaused && currentPcmData != null) {
            isPaused = false
            
            // 如果AudioTrack还存在，直接恢复
            if (audioTrack != null) {
                audioTrack?.play()
                Log.d(TAG, "Audio resumed from position: $currentOffset")
                
                // 继续播放线程
                playbackThread = Thread({
                    currentPcmData?.let { pcmData ->
                        playPcmData(pcmData, currentChunkSize)
                        // 通知播放完成
                        if (!isStopped && !isPaused && currentOffset >= pcmData.size) {
                            onCompletionListener?.invoke()
                        }
                    }
                }, "AudioPlaybackThread-Resume")
                playbackThread?.start()
            } else {
                // AudioTrack已释放，需要重新创建并从保存的位置继续
                Log.d(TAG, "Recreating AudioTrack to resume from position: $currentOffset")
                currentPcmData?.let { pcmData ->
                    play(pcmData, 1.0f, onCompletionListener)
                }
            }
        }
    }
    
    /**
     * 停止播放并释放资源
     */
    fun stopAndRelease() {
        isStopped = true
        playbackThread?.let {
            it.interrupt()
            try {
                it.join(1000) // 等待最多1秒让线程结束
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for playback thread to finish")
            }
        }
        playbackThread = null
        
        audioTrack?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack already stopped")
            }
            it.release()
        }
        audioTrack = null
        onCompletionListener = null
        currentPcmData = null
        currentOffset = 0
    }
}
