package com.qq.wx.offlinevoice.synthesizer

import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.cache.TtsCache
import com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder
import com.qq.wx.offlinevoice.synthesizer.online.OnlineTtsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest

class TtsRepository(
    val onlineApi: OnlineTtsApi,
    private val mp3Decoder: Mp3Decoder,
    private val cache: TtsCache,
    private val networkMonitor: NetworkMonitor
) {
    // 使用 Mutex 避免对同一个 key 的并发网络请求
    private val requestMutexes = mutableMapOf<String, Mutex>()
    private val mapMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 获取解码后的 PCM 数据。
     * 优先从缓存获取，如果缓存未命中，则根据 allowNetwork 参数决定是否发起网络请求。
     *
     * @param text 要合成的文本。
     * @param speaker 发音人。
     * @param allowNetwork 是否允许在缓存未命中时发起网络请求。默认为 true。
     * @return 解码后的 DecodedPcm 数据。
     * @throws IOException 如果缓存未命中且 allowNetwork 为 false，或发生网络/解码错误。
     */
    suspend fun getDecodedPcm(
        text: String,
        speaker: Speaker,
        allowNetwork: Boolean = true // --- 1. 添加了缺失的 allowNetwork 参数，并提供默认值 ---
    ): DecodedPcm {
        val text = text.trim()
        val cacheKey = createCacheKey(text, speaker)

        // 1) 无锁快速路径：先查 MP3 缓存
        cache.get(cacheKey)?.let { mp3Bytes ->
            val decoded = runCatching {
                mp3Decoder.decode(mp3Bytes)
            }.onFailure {
                AppLogger.e("TtsRepository", "MP3缓存 解码失败，尝试重新请求网络: $cacheKey, text: $text", it)
            }.getOrNull()
            if (decoded != null) {
                AppLogger.d("TtsRepository", "MP3 缓存命中: $cacheKey, text: $text")
                return decoded
            }
        }

        // 获取或创建特定于此key的锁
        val mutex = mapMutex.withLock {
            requestMutexes.getOrPut(cacheKey) { Mutex() }
        }

        mutex.withLock {
            try {


                // --- 2. 新增的核心逻辑：在请求网络前检查 allowNetwork 标志 ---
                if (!allowNetwork) {
                    // 如果缓存未命中且网络请求被禁止，则必须失败。
                    throw ForbiddenNetworkException("缓存未命中，且网络请求被禁止 (例如：正处于冷却期)")
                }

                if (networkMonitor.isNetworkGood.value.not()) {
                    throw IOException("当前网络状况不佳，无法进行在线请求")
                }

                // 只有在允许网络请求时才继续
                AppLogger.d("TtsRepository", "缓存未命中，开始网络请求: $cacheKey")
                val mp3Data = onlineApi.fetchTtsAudio(text, speaker)
                val decodedPcm = mp3Decoder.decode(mp3Data)

                // 2) 将成功获取并解码的结果存入缓存
                cache.put(cacheKey, mp3Data)

                return decodedPcm
            } catch (e: Exception) {
                // 在 Repository 层面记录带有上下文的详细日志
                AppLogger.e("TtsRepository", "获取或解码在线PCM失败: $cacheKey}")
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

    /**
     * 创建一个安全的、固定长度的缓存键。
     * 使用 SHA-1 算法对组合了所有影响因素的原始字符串进行哈希。
     */
    private fun createCacheKey(text: String, speaker: Speaker): String {
        // 组合所有可能影响音频输出的变量
        val originalKey = "${speaker.modelName}|$text"

        // 使用 MessageDigest 来进行哈希
        val digest = MessageDigest.getInstance("SHA-1")
        val result = digest.digest(originalKey.toByteArray())

        // 将字节数组转换为十六进制字符串
        return result.joinToString("") { "%02x".format(it) }
    }

    fun clearCache() {
        scope.launch {
            cache.clear()
        }
    }
}

internal class ForbiddenNetworkException(message: String) : Exception(message)