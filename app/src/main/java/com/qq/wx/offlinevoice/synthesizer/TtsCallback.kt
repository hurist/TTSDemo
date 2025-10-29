package com.qq.wx.offlinevoice.synthesizer

/**
 * Callback interface for TTS synthesis events
 */
interface TtsCallback {
    /**
     * Called when TTS engine initialization is complete
     * @param success True if initialization was successful
     */
    fun onInitialized(success: Boolean) {}
    
    /**
     * Called when overall TTS synthesis starts (for all sentences)
     */
    fun onSynthesisStart() {}
    
    /**
     * Called when a specific sentence starts being read
     * @param sentenceIndex The index of the sentence (0-based)
     * @param sentence The text of the sentence
     * @param totalSentences Total number of sentences
     */
    fun onSentenceStart(sentenceIndex: Int, sentence: String, totalSentences: Int) {}
    
    /**
     * Called when a specific sentence finishes being read
     * @param sentenceIndex The index of the sentence (0-based)
     * @param sentence The text of the sentence
     */
    fun onSentenceComplete(sentenceIndex: Int, sentence: String) {}
    
    /**
     * Called when playback state changes
     * @param newState The new playback state
     */
    fun onStateChanged(newState: TtsPlaybackState) {}
    
    /**
     * Called when PCM data is available for playback
     * @param pcmData The synthesized PCM audio data
     * @param length The length of valid PCM data
     */
    fun onPcmDataAvailable(pcmData: ByteArray, length: Int) {}
    
    /**
     * Called when all sentences have been read successfully
     */
    fun onSynthesisComplete() {}
    
    /**
     * Called when playback is paused
     */
    fun onPaused() {}
    
    /**
     * Called when playback is resumed
     */
    fun onResumed() {}
    
    /**
     * Called when an error occurs during synthesis
     * @param errorMessage Description of the error
     */
    fun onError(errorMessage: String) {}
}
