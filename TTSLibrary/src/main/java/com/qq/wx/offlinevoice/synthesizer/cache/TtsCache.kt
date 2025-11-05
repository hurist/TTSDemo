package com.qq.wx.offlinevoice.synthesizer.cache


/**
 * 统一的 TTS 缓存接口（MP3 优先）。
 * - get/put 针对 MP3 原始字节；
 * - 解码为 PCM 的工作由上层 Repository 负责。
 */
interface TtsCache {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, data: ByteArray)
    suspend fun clear()
}