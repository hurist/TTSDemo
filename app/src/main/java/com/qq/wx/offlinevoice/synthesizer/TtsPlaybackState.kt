package com.qq.wx.offlinevoice.synthesizer

/**
 * Playback state of the TTS engine
 */
enum class TtsPlaybackState {
    /** TTS engine is idle (not initialized or stopped) */
    IDLE,
    
    /** TTS is currently synthesizing and playing */
    PLAYING,
    
    /** TTS playback is paused */
    PAUSED
}
