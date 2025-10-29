package com.qq.wx.offlinevoice.synthesizer

/**
 * Constants for TTS synthesis and audio playback
 */
object TtsConstants {
    // Audio Configuration
    const val DEFAULT_SAMPLE_RATE = 24000
    const val SONIC_SAMPLE_RATE = 24000
    const val NUM_CHANNELS = 1
    const val PCM_BUFFER_SIZE = 64000
    
    // Audio Processing
    const val PITCH_FACTOR = 0.68f
    const val SONIC_SPEED = 0.72f
    const val SONIC_RATE = 1.0f
    const val SONIC_QUALITY = 1
    
    // Playback Settings
    const val MIN_BUFFER_SIZE_FALLBACK = 2048
    const val CHUNK_SIZE_MIN = 1024
    const val PLAYBACK_SLEEP_MS = 1000L
    
    // Retry Configuration
    const val MAX_PREPARE_RETRIES = 3
    
    // Speed and Volume Scaling
    const val SPEED_VOLUME_SCALE = 50.0f
}
