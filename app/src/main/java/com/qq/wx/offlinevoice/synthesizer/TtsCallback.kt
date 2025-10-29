package com.qq.wx.offlinevoice.synthesizer

/**
 * Callback interface for TTS synthesis events
 */
interface TtsCallback {
    /**
     * Called when synthesis starts
     */
    fun onSynthesisStart() {}
    
    /**
     * Called when PCM data is available for playback
     * @param pcmData The synthesized PCM audio data
     * @param length The length of valid PCM data
     */
    fun onPcmDataAvailable(pcmData: ByteArray, length: Int) {}
    
    /**
     * Called when synthesis completes successfully
     */
    fun onSynthesisComplete() {}
    
    /**
     * Called when an error occurs during synthesis
     * @param errorMessage Description of the error
     */
    fun onError(errorMessage: String) {}
}
