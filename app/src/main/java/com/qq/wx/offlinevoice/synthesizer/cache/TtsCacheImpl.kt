package com.qq.wx.offlinevoice.synthesizer.cache

import android.content.Context
import android.util.Log
import androidx.collection.LruCache
import com.qq.wx.offlinevoice.synthesizer.DecodedPcm
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest

class TtsCacheImpl(context: Context) : TtsCache {
    // 内存缓存：快速访问
    private val memoryCache: LruCache<String, DecodedPcm> = LruCache(5 * 1024 * 1024) // 5MB

    // 磁盘缓存：持久化
    private val diskCacheDir: File = File(context.externalCacheDir, "tts_cache").apply { mkdirs() }

    override suspend fun get(key: String): DecodedPcm? {
        // 1. 先从内存查找
        memoryCache[key]?.let {
            Log.d("TtsCache", "缓存命中 (内存): $key")
            return it
        }

        // 2. 再从磁盘查找
        return try {
            val file = File(diskCacheDir, key.toMd5())
            if (file.exists()) {
                ObjectInputStream(file.inputStream()).use { stream ->
                    val pcm = stream.readObject() as DecodedPcm
                    // 放入内存缓存以备下次快速访问
                    memoryCache.put(key, pcm)
                    Log.d("TtsCache", "缓存命中 (磁盘): $key")
                    pcm
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("TtsCache", "读取磁盘缓存失败", e)
            null
        }
    }

    override suspend fun put(key: String, pcm: DecodedPcm) {
        // 同时写入内存和磁盘
        memoryCache.put(key, pcm)
        try {
            val file = File(diskCacheDir, key.toMd5())
            ObjectOutputStream(file.outputStream()).use { stream ->
                stream.writeObject(pcm)
            }
            Log.d("TtsCache", "缓存已写入: $key")
        } catch (e: Exception) {
            Log.e("TtsCache", "写入磁盘缓存失败", e)
        }
    }

    private fun String.toMd5(): String {
        return MessageDigest.getInstance("MD5").digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}