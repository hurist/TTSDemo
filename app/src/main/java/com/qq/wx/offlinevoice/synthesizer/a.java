package com.qq.wx.offlinevoice.synthesizer;

import android.content.Context;

/**
 * Legacy TTS synthesizer class
 * This class is kept for backward compatibility but delegates to the new TtsSynthesizer
 * @deprecated Use {@link TtsSynthesizer} instead
 */
@Deprecated
public final class a implements h {
    
    private final TtsSynthesizer synthesizer;

    public a(Context context, Speaker speaker) {
        this.synthesizer = new TtsSynthesizer(context, speaker);
    }

    @Override
    public final synchronized void c() {
        synthesizer.initialize();
    }

    @Override
    public final synchronized void cancel() {
        synthesizer.cancel();
    }

    @Override
    public final synchronized void d(float f3, float f8, String str, g gVar) {
        synthesizer.synthesize(f3, f8, str, gVar);
    }

    @Override
    public final synchronized void release() {
        synthesizer.release();
    }
}
