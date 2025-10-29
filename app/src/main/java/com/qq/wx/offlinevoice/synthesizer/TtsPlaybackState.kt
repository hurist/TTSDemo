package com.qq.wx.offlinevoice.synthesizer

/**
 * Playback state of the TTS engine
 */
enum class TtsPlaybackState {
    /** TTS engine is not initialized */
    UNINITIALIZED,
    
    /** TTS engine is initialized but not playing */
    IDLE,
    
    /** TTS is currently synthesizing and playing */
    PLAYING,
    
    /** TTS playback is paused */
    PAUSED,
    
    /** TTS is in the process of stopping */
    STOPPING,
    
    /** An error has occurred */
    ERROR
}
