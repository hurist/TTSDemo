package com.qq.wx.offlinevoice.synthesizer.cache

import com.qq.wx.offlinevoice.synthesizer.DecodedPcm

interface TtsCache {
    suspend fun get(key: String): DecodedPcm?
    suspend fun put(key: String, pcm: DecodedPcm)

    suspend fun clear()
}