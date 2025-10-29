package com.qq.wx.offlinevoice.synthesizer

/**
 * Interface defining the TTS engine operations
 */
interface TtsEngine {
    /**
     * Initialize the TTS engine
     */
    fun initialize()
    
    /**
     * Synthesize and speak text (automatically splits into sentences)
     * @param text Text to synthesize and speak
     * @param speed Speech speed (typically 0-100)
     * @param volume Speech volume (typically 0-100)
     * @param callback Optional callback for synthesis events
     */
    fun speak(text: String, speed: Float = 50f, volume: Float = 50f, callback: TtsCallback? = null)
    
    /**
     * Pause ongoing synthesis/playback
     */
    fun pause()
    
    /**
     * Resume paused synthesis/playback
     */
    fun resume()
    
    /**
     * Stop ongoing synthesis and clear queue
     */
    fun stop()
    
    /**
     * Get current playback status
     * @return Current TTS status
     */
    fun getStatus(): TtsStatus
    
    /**
     * Check if TTS is currently speaking
     * @return True if speaking, false otherwise
     */
    fun isSpeaking(): Boolean
    
    /**
     * Release TTS engine resources
     */
    fun release()
    
    // Legacy method for backward compatibility (deprecated)
    @Deprecated("Use speak() instead", ReplaceWith("speak(text, speed, volume, null)"))
    fun synthesize(speed: Float, volume: Float, text: String, callback: g?)
    
    @Deprecated("Use stop() instead", ReplaceWith("stop()"))
    fun cancel()
}
