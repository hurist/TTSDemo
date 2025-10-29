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
     * Cancel ongoing synthesis
     */
    fun cancel()
    
    /**
     * Synthesize text to speech
     * @param speed Speech speed (typically 0-100)
     * @param volume Speech volume (typically 0-100)
     * @param text Text to synthesize
     * @param callback Optional callback for synthesis events (kept for backward compatibility)
     */
    fun synthesize(speed: Float, volume: Float, text: String, callback: g?)
    
    /**
     * Release TTS engine resources
     */
    fun release()
}
