package com.qq.wx.offlinevoice.synthesizer

import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.cache.TtsCache
import com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder
import com.qq.wx.offlinevoice.synthesizer.online.OnlineTtsApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

class TtsRepository(
    private val onlineApi: OnlineTtsApi,
    private val mp3Decoder: Mp3Decoder,
    private val cache: TtsCache
) {
    // 使用 Mutex 避免对同一个 key 的并发网络请求
    private val requestMutexes = mutableMapOf<String, Mutex>()
    private val mapMutex = Mutex()

    suspend fun getDecodedPcm(text: String, speaker: Speaker): DecodedPcm {
        val cacheKey = createCacheKey(text, speaker)
        
        // 1. 首先检查缓存
        cache.get(cacheKey)?.let { return it }

        // 获取或创建特定于此key的锁
        val mutex = mapMutex.withLock {
            requestMutexes.getOrPut(cacheKey) { Mutex() }
        }

        mutex.withLock {
            try {
                // 再次检查缓存
                cache.get(cacheKey)?.let { return it }

                Log.d("TtsRepository", "缓存未命中，开始网络请求: $cacheKey")
                val mp3Data = onlineApi.fetchTtsAudio(text, speaker)
                val decodedPcm = mp3Decoder.decode(mp3Data)

                // 成功后，存入缓存并返回
                cache.put(cacheKey, decodedPcm)
                return decodedPcm
            } catch (e: Exception) {
                // 在 Repository 层面记录带有上下文的详细日志
                Log.e("TtsRepository", "获取或解码在线PCM失败: $cacheKey", e)
                // 将原始异常重新抛出，让调用方来决定如何处理
                throw e
            } finally {
                // 确保无论成功还是失败，mutex 都会被从 map 中移除
                mapMutex.withLock {
                    requestMutexes.remove(cacheKey)
                }
            }
        }
    }

    private fun createCacheKey(text: String, speaker: Speaker): String {
        // 组合所有可能影响音频输出的变量
        val originalKey = "${speaker.modelName}|${text}"

        // 使用 MessageDigest 来进行哈希
        val digest = MessageDigest.getInstance("SHA-1")
        val result = digest.digest(originalKey.toByteArray())

        // 将字节数组转换为十六进制字符串
        return result.joinToString("") { "%02x".format(it) }
    }
}