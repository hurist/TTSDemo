package com.qq.wx.offlinevoice.synthesizer

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Audio player for PCM data playback using AudioTrack
 */
class AudioPlayer(private val sampleRate: Int = TtsConstants.DEFAULT_SAMPLE_RATE) {
    
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile
    private var isPaused = false
    @Volatile
    private var isStopped = false
    private var onCompletionListener: (() -> Unit)? = null
    
    companion object {
        private const val TAG = "AudioPlayer"
    }
    
    /**
     * Start playback of PCM audio data
     * @param pcmData Short array containing PCM samples
     * @param volume Volume level (0.0 to 1.0)
     * @param onCompletion Callback invoked when playback completes
     */
    fun play(pcmData: ShortArray, volume: Float = 1.0f, onCompletion: (() -> Unit)? = null) {
        stopAndRelease()
        
        if (pcmData.isEmpty()) {
            Log.w(TAG, "PCM data is empty, nothing to play")
            onCompletion?.invoke()
            return
        }
        
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
            
            // Set volume to match the requested level (Issue 1 fix)
            val clampedVolume = volume.coerceIn(0.0f, 1.0f)
            audioTrack?.setStereoVolume(clampedVolume, clampedVolume)
            
            audioTrack?.play()
            
            val chunkSize = maxOf(minBufferSize / 2, TtsConstants.CHUNK_SIZE_MIN)
            playbackThread = Thread({
                playPcmData(pcmData, chunkSize)
                // Notify completion
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
     * Internal method to write PCM data to AudioTrack
     */
    private fun playPcmData(pcmData: ShortArray, chunkSize: Int) {
        var offset = 0
        
        while (!Thread.currentThread().isInterrupted && !isStopped && offset < pcmData.size) {
            val toWrite = minOf(chunkSize, pcmData.size - offset)
            val written = audioTrack?.write(pcmData, offset, toWrite) ?: 0
            
            when {
                written > 0 -> offset += written
                written == AudioTrack.ERROR_INVALID_OPERATION || 
                written == AudioTrack.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "AudioTrack write error: $written")
                    break
                }
            }
        }
    }
    
    /**
     * Pause playback
     */
    fun pause() {
        isPaused = true
        audioTrack?.pause()
    }
    
    /**
     * Resume playback
     */
    fun resume() {
        isPaused = false
        audioTrack?.play()
    }
    
    /**
     * Stop playback and release resources
     */
    fun stopAndRelease() {
        isStopped = true
        playbackThread?.let {
            it.interrupt()
            try {
                it.join(1000) // Wait up to 1 second for thread to finish
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
    }
}
