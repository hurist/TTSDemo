package com.qq.wx.offlinevoice.synthesizer

class SynthesizerNative {

    /*init {
        System.loadLibrary("hwTTS")
        System.loadLibrary("weread-tts")
    }*/

    external fun destroy()

    external fun init(bArr: ByteArray?): Int

    external fun prepare(bArr: ByteArray?): Int

    external fun prepareUTF8(bArr: ByteArray?): Int

    external fun reset()

    external fun setSpeed(f3: Float)

    external fun setVoiceName(str: String?)

    external fun setVolume(f3: Float)

    external fun synthesize(sArr: ShortArray?, i: Int, iArr: IntArray?, i8: Int): Int
}