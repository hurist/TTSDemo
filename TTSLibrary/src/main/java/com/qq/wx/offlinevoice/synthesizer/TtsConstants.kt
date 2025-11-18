package com.qq.wx.offlinevoice.synthesizer

/**
 * Constants for TTS synthesis and audio playback
 */
object TtsConstants {
    // Audio Configuration
    const val DEFAULT_SAMPLE_RATE = 16000
    const val NUM_CHANNELS = 1
    const val PCM_BUFFER_SIZE = DEFAULT_SAMPLE_RATE * 4
    
    // Audio Processing

    // Playback Settings
    const val MIN_BUFFER_SIZE_FALLBACK = 2048

    // Retry Configuration
    const val MAX_PREPARE_RETRIES = 3
}
