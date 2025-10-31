package com.qq.wx.offlinevoice.synthesizer.online

import com.qq.wx.offlinevoice.synthesizer.DecodedPcm

// MP3解码器接口
interface Mp3Decoder {
    /**
     * @return Decoded PCM data as a ShortArray
     * @throws Exception if decoding fails
     */
    fun decode(mp3Data: ByteArray): DecodedPcm
}