package com.qq.wx.offlinevoice.synthesizer;

/**
 * Legacy interface for TTS operations
 * @deprecated Use {@link TtsEngine} instead
 */
@Deprecated
public interface h {
    /**
     * Initialize the TTS engine
     * @deprecated Use {@link TtsEngine#initialize()} instead
     */
    void c();

    /**
     * Cancel ongoing synthesis
     * @deprecated Use {@link TtsEngine#cancel()} instead
     */
    void cancel();

    /**
     * Synthesize text to speech
     * @deprecated Use {@link TtsEngine#synthesize(float, float, String, g)} instead
     */
    void d(float f3, float f8, String str, g gVar);

    /**
     * Release TTS engine resources
     * @deprecated Use {@link TtsEngine#release()} instead
     */
    void release();
}