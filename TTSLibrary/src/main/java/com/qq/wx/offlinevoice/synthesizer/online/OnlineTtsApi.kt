package com.qq.wx.offlinevoice.synthesizer.online

import com.qq.wx.offlinevoice.synthesizer.Speaker

// 模拟的在线TTS API接口 (可以用Retrofit等实现)
interface OnlineTtsApi {
    /**
     * @return MP3 audio data as a ByteArray
     * @throws IOException for network errors
     */
    suspend fun fetchTtsAudio(text: String, speaker: Speaker): ByteArray

    fun setToken(token: String, uid: Long)
}