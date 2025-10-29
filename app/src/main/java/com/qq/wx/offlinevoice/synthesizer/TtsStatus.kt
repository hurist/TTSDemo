package com.qq.wx.offlinevoice.synthesizer

/**
 * Current status of TTS playback
 */
data class TtsStatus(
    /** Current playback state */
    val state: TtsPlaybackState = TtsPlaybackState.UNINITIALIZED,
    
    /** Total number of sentences to be read */
    val totalSentences: Int = 0,
    
    /** Current sentence index (0-based) */
    val currentSentenceIndex: Int = 0,
    
    /** Text of the current sentence being read */
    val currentSentence: String = "",
    
    /** Error message if state is ERROR */
    val errorMessage: String? = null
)
