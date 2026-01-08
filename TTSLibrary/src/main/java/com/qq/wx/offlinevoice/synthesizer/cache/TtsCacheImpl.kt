package com.qq.wx.offlinevoice.synthesizer.cache

import android.content.Context
import android.widget.Toast
import androidx.collection.LruCache
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.disklrucache.DiskLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

class TtsCacheImpl(private val context: Context) : TtsCache {

    // -------------------- 配置 --------------------

    // 内存缓存：默认 min(16MB, maxMemory/16)，保底 2MB
    private val maxMemoryCacheBytes: Int by lazy {
        val maxFromRuntime = (Runtime.getRuntime().maxMemory() / 16L).coerceAtMost(16L * 1024 * 1024).toInt()
        maxFromRuntime.coerceAtLeast(2 * 1024 * 1024)
    }

    // 磁盘缓存上限：默认 256MB
    private val maxDiskBytes: Long = 256L * 1024 * 1024

    // -------------------- 缓存对象 --------------------

    // 1. 第一级：内存缓存 (LruCache)
    private val memoryCache = object : LruCache<String, ByteArray>(maxMemoryCacheBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // 2. 第二级：磁盘缓存 (DiskLruCache)
    @Volatile
    private var diskCache: DiskLruCache? = null

    init {
        diskCache = openDiskCache()
    }

    @Synchronized
    private fun openDiskCache(): DiskLruCache? {
        return try {
            val cacheDir = getDiskCacheDir(context, "tts_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            DiskLruCache.open(cacheDir, 1, 1, maxDiskBytes)
        } catch (e: Exception) {
            AppLogger.e("TtsCache", "打开 DiskLruCache 失败", e)
            null
        }
    }

    // -------------------- 对外 API --------------------

    override suspend fun get(key: String): ByteArray? {
        val safeKey = hashKeyForDisk(key)
        // 1. 查内存
        memoryCache.get(safeKey)?.let {
            AppLogger.d("TtsCache", "缓存命中 (内存): $key")
            return it
        }

        // 2. 查磁盘
        // DiskLruCache 内部是 synchronized 的，但在 IO 线程执行更安全
        return withContext(Dispatchers.IO) {
            try {

                diskCache?.get(safeKey)?.use { snapshot ->
                    val bytes = snapshot.getInputStream(0).use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        memoryCache.put(safeKey, bytes)
                        AppLogger.d("TtsCache", "缓存命中 (磁盘): $key")
                        return@withContext bytes
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("TtsCache", "读取磁盘缓存异常", e)
            }
            return@withContext null
        }
    }

    override suspend fun put(key: String, data: ByteArray) {
        val safeKey = hashKeyForDisk(key)
        // 1. 存内存
        memoryCache.put(safeKey, data)

        // 2. 存磁盘
        withContext(Dispatchers.IO) {
            try {
                // 获取编辑器 (原子事务)
                // 如果当前 key 正在被编辑，edit() 可能返回 null
                val editor = diskCache?.edit(safeKey) ?: run {
                    AppLogger.w("TtsCache", "无法获取编辑器 (可能正在被其他线程写入): $key")
                    return@withContext
                }

                try {
                    // 写入流
                    editor.newOutputStream(0).use { output ->
                        output.write(data)
                    }
                    // 提交事务：此时文件才会正式生效 (自动处理 .tmp -> rename)
                    editor.commit()

                    // 不需要手动调用 flush，DiskLruCache 内部策略会处理
                    AppLogger.d("TtsCache", "缓存写入磁盘成功: $key")
                } catch (e: Exception) {
                    // 发生异常，回滚事务 (删除临时文件)
                    editor.abort()
                    AppLogger.e("TtsCache", "写入事务失败，已回滚", e)
                }
            } catch (e: Exception) {
                AppLogger.e("TtsCache", "获取 DiskLruCache 编辑器失败", e)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            try {
                memoryCache.evictAll()

                // 关闭并删除磁盘缓存
                diskCache?.close()
                diskCache?.delete()

                // 重新打开
                diskCache = openDiskCache()

                AppLogger.i("TtsCache", "缓存已全部清空")

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "缓存已清空", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e("TtsCache", "清空缓存失败", e)
            }
        }
    }

    // -------------------- 辅助方法 --------------------

    /**
     * 获取缓存目录：优先外部存储，其次内部存储
     */
    private fun getDiskCacheDir(context: Context, uniqueName: String): File {
        // 检查外部存储是否挂载
        val cachePath =
            if (context.externalCacheDir != null) {
                context.externalCacheDir?.path
            } else {
                context.cacheDir.path
            }
        return File(cachePath + File.separator + uniqueName)
    }

    /**
     * DiskLruCache 对 key 有严格要求：[a-z0-9_-]{1,120}
     * 所以必须对原始 URL/Text 进行 MD5 哈希
     */
    private fun hashKeyForDisk(key: String): String {
        if (regex.matches(key)) {
            return key
        }

        return try {
            val mDigest = MessageDigest.getInstance("MD5")
            mDigest.update(key.toByteArray())
            bytesToHexString(mDigest.digest())
        } catch (e: Exception) {
            // fallback: 移除特殊字符，截断
            key.hashCode().toString()
        }
    }

    private fun bytesToHexString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val hex = Integer.toHexString(0xFF and b.toInt())
            if (hex.length == 1) {
                sb.append('0')
            }
            sb.append(hex)
        }
        return sb.toString()
    }

    companion object {
        private val regex = Regex("[a-z0-9_-]{1,120}")
    }
}