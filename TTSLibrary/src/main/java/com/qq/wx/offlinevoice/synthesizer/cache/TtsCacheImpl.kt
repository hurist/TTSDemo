package com.qq.wx.offlinevoice.synthesizer.cache

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.collection.LruCache
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.DecodedPcm
import com.qq.wx.offlinevoice.synthesizer.clearDirectoryFunctional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest

class TtsCacheImpl(private val context: Context) : TtsCache {
    // 内存缓存：快速访问
    private val memoryCache: LruCache<String, DecodedPcm> = LruCache(5 * 1024 * 1024) // 5MB

    // 磁盘缓存：持久化
    private val diskCacheDir: File = File(context.externalCacheDir, "tts_cache").apply { mkdirs() }

    override suspend fun get(key: String): DecodedPcm? {
        // 1. 先从内存查找
        memoryCache[key]?.let {
            AppLogger.d("TtsCache", "缓存命中 (内存): $key")
            return it
        }

        // 2. 再从磁盘查找
        return try {
            val cacheDir = getAndEnsureDiskCacheDir()
            if (cacheDir == null) {
                AppLogger.w("TtsCache", "磁盘缓存目录不可用，跳过写入磁盘缓存: $key")
                return null
            }
            val file = File(cacheDir, key.toMd5())
            if (file.exists()) {
                ObjectInputStream(file.inputStream()).use { stream ->
                    val pcm = stream.readObject() as DecodedPcm
                    // 放入内存缓存以备下次快速访问
                    memoryCache.put(key, pcm)
                    AppLogger.d("TtsCache", "缓存命中 (磁盘): $key")
                    pcm
                }
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e("TtsCache", "读取磁盘缓存失败", e)
            null
        }
    }

    override suspend fun put(key: String, pcm: DecodedPcm) {
        // 同时写入内存和磁盘
        memoryCache.put(key, pcm)
        try {
            // 2. 再从磁盘查找
            // 获取并确保目录存在，如果失败则直接返回 null
            val cacheDir = getAndEnsureDiskCacheDir()
            if (cacheDir == null) {
                AppLogger.w("TtsCache", "磁盘缓存目录不可用，跳过写入磁盘缓存: $key")
                return
            }
            val file = File(cacheDir, key.toMd5())
            if (file.exists().not()) {
                file.createNewFile()
            }
            ObjectOutputStream(file.outputStream()).use { stream ->
                stream.writeObject(pcm)
            }
            AppLogger.d("TtsCache", "缓存已写入: $key")
        } catch (e: Exception) {
            AppLogger.e("TtsCache", "写入磁盘缓存失败", e)
        }
    }

    override suspend fun clear() {
        runCatching {
            val memorySize = memoryCache.size()
            val diskFiles = diskCacheDir.listFiles()?.size ?: 0
            // 清空内存缓存
            memoryCache.evictAll()
            // 清空磁盘缓存
            diskCacheDir.clearDirectoryFunctional()
            AppLogger.d("TtsCache", "缓存已清空, 内存项: $memorySize, 磁盘项: $diskFiles")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "缓存已清空, 内存项: $memorySize, 磁盘项: $diskFiles", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            AppLogger.e("TtsCache", "清空缓存失败", it)
        }
    }

    private fun String.toMd5(): String {
        return MessageDigest.getInstance("MD5").digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }


    /**
     * 获取并确保磁盘缓存目录存在。
     * 这是解决问题的核心函数。
     * @return 如果目录可用则返回 File 对象，否则返回 null。
     */
    private fun getAndEnsureDiskCacheDir(): File? {
        // 1. 检查外部存储是否已挂载并可读写
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            AppLogger.w("TtsCache", "外部存储不可用，磁盘缓存将禁用。")
            return null
        }

        val cacheDir = diskCacheDir
        // 2. 如果目录不存在，则尝试创建它（包括所有必要的父目录）
        if (!cacheDir.exists()) {
            if (!cacheDir.mkdirs()) {
                AppLogger.e("TtsCache", "无法创建磁盘缓存目录: ${cacheDir.absolutePath}")
                return null // 如果创建失败，返回 null
            }
        }
        return cacheDir
    }

}