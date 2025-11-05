package com.qq.wx.offlinevoice.synthesizer.cache

import android.content.Context
import android.widget.Toast
import androidx.collection.LruCache
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.clearDirectoryFunctional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * TtsCacheImpl：带容量与老化控制的两级缓存实现（内存 + 磁盘）。
 *
 * 设计目标
 * - 有界：严格限制内存与磁盘占用，避免无限膨胀。
 * - 可回收：采用 LRU（最近最少使用）与 TTL（到期）双策略清理陈旧内容。
 * - 稳态：在写入前、低存储告警时、start/定期（可由上层触发）进行清理，避免在极端时刻才处理。
 * - 安全：写盘使用“临时文件 + 原子重命名”，避免半写文件；读失败时清理损坏文件。
 * - 可观测：关键路径打印日志（命中、写入、清理、异常），便于排查。
 *
 * 内存缓存（按字节计数）
 * - 上限：maxMemoryCacheBytes（默认 min(16MB, Runtime.maxMemory/16)，至少 2MB）。
 * - 计费：以内容字节长度计费。
 * - 淘汰：LRU；超限自动逐出最久未使用条目。
 *
 * 磁盘缓存（目录 + TTL + LRU）
 * - 目录：context.externalCacheDir/tts_cache（外部存储不可用时跳过磁盘缓存）。
 * - 容量上限：maxDiskBytes = min(256MB, 可用空间 5%)，且不低于 32MB。
 * - 单文件上限：maxEntryBytes = 8MB（超过则跳过写盘）。
 * - 文件数上限：maxFileCount = 10,000（超过则触发 LRU 清理）。
 * - TTL：maxAgeMillis = 7 天，超过立即删除（比 LRU 优先）。
 * - 低存储保护：可用空间 < 300MB 时触发“紧急清理”，仍不足则放弃写盘。
 * - 水位压缩：当 (当前占用 + 待写入大小) > 上限时，按 lastModified 升序删除直至压到 trimTargetRatio（默认 90%）；紧急模式更低（如 70%）。
 * - LRU 基准：以文件 lastModified 作为“最近访问”时间；命中 get 时会触摸（setLastModified(now)）。
 * - 原子写：写入 {md5}.tmp，成功后 renameTo 为 {md5}；异常时删除 .tmp。
 * - 损坏修复：读出错即删除该文件，避免反复失败。
 */
class TtsCacheImpl(private val context: Context) : TtsCache {

    // -------------------- 配置 --------------------

    // 内存缓存最大字节数（默认取 min(16MB, maxMemory/16)）
    private val maxMemoryCacheBytes: Int by lazy {
        val maxFromRuntime = (Runtime.getRuntime().maxMemory() / 16L).coerceAtMost(16L * 1024 * 1024).toInt()
        maxFromRuntime.coerceAtLeast(2 * 1024 * 1024) // 至少 2MB
    }

    // 磁盘缓存上限（默认 256MB 或可用空间的 5%，取较小者）
    private val maxDiskBytes: Long by lazy {
        val dir = context.externalCacheDir ?: context.cacheDir
        val available = dir?.usableSpace ?: 0L
        val byPercent = (available * 0.05).toLong()
        min(256L * 1024 * 1024, byPercent.coerceAtLeast(32L * 1024 * 1024)) // 至少 32MB
    }

    // 单文件最大字节（超过则不落盘，默认 8MB）
    private val maxEntryBytes: Long = 8L * 1024 * 1024

    // 磁盘最大文件数（超过则按 LRU 淘汰），默认 10k
    private val maxFileCount: Int = 10_000

    // TTL：超过该天数的文件优先被清理（默认 7 天）
    private val maxAgeMillis: Long = TimeUnit.DAYS.toMillis(7)

    // 低存储剩余空间阈值：低于该值跳过写盘并尝试清理（默认 300MB）
    private val lowSpaceFreeBytesThreshold: Long = 300L * 1024 * 1024

    // 超限清理后的目标水位（例如清到上限的 90%）
    private val trimTargetRatio: Double = 0.90

    // -------------------- 内存缓存（MP3 字节） --------------------

    // 简单 LRU（按字节计费）
    private val memoryCache = object : LruCache<String, ByteArray>(maxMemoryCacheBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // -------------------- 磁盘缓存目录与并发互斥 --------------------

    // 磁盘缓存：持久化（外部缓存目录 tts_cache）
    private val diskCacheDir: File by lazy {
        val base = context.externalCacheDir ?: context.cacheDir
        File(base, "tts_cache").apply { mkdirs() }
    }

    // 统一的文件并发互斥，避免清理/写入/读取竞争
    private val diskMutex = Mutex()

    // -------------------- 对外 API（MP3 字节） --------------------

    override suspend fun get(key: String): ByteArray? {
        // 1. 先从内存查找
        memoryCache.get(key)?.let {
            AppLogger.d("TtsCache", "缓存命中 (内存): $key")
            return it
        }

        // 2. 再从磁盘查找
        val cacheDir = getAndEnsureDiskCacheDir() ?: return null
        val file = File(cacheDir, key.toMd5())
        if (!file.exists()) return null

        return withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val now = System.currentTimeMillis()
                if (isExpired(file, now)) {
                    runCatching { file.delete() }
                    AppLogger.i("TtsCache", "TTL 过期，已删除磁盘缓存: ${file.name}")
                    return@withLock null
                }
                try {
                    val bytes = withTimeout(3000) {
                        BufferedInputStream(file.inputStream()).use { it.readBytes() }
                    }
                    // 放入内存缓存以备下次快速访问
                    memoryCache.put(key, bytes)
                    AppLogger.d("TtsCache", "缓存命中 (磁盘): $key")
                    // LRU 触摸：更新 lastModified 为当前时间
                    runCatching { file.setLastModified(now) }
                    bytes
                } catch (e: Exception) {
                    AppLogger.e("TtsCache", "读取磁盘缓存失败（将删除损坏文件）", e)
                    runCatching { file.delete() }
                    null
                }
            }
        }
    }

    override suspend fun put(key: String, data: ByteArray) {
        // 1) 先写入内存
        memoryCache.put(key, data)

        // 2) 再写入磁盘（受限于容量/低存储/单文件大小/TTL 清理等策略）
        val cacheDir = getAndEnsureDiskCacheDir() ?: run {
            AppLogger.w("TtsCache", "磁盘缓存目录不可用，跳过写入磁盘缓存: $key")
            return
        }
        val size = data.size.toLong()
        if (size > maxEntryBytes) {
            AppLogger.w("TtsCache", "条目过大，跳过磁盘写入: key=$key, approx=${size}B, max=$maxEntryBytes")
            return
        }

        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                // 低存储保护
                val free = cacheDir.usableSpace
                if (free < lowSpaceFreeBytesThreshold) {
                    AppLogger.w("TtsCache", "可用空间过低(usable=${free}B < $lowSpaceFreeBytesThreshold)，触发紧急清理。")
                    scanAndTrimDirectoryIfNeeded(cacheDir, incomingBytes = size, aggressive = true)
                    val freeAfter = cacheDir.usableSpace
                    if (freeAfter < lowSpaceFreeBytesThreshold) {
                        AppLogger.w("TtsCache", "紧急清理后可用空间仍不足(usable=${freeAfter}B)，跳过写入: $key")
                        return@withLock
                    }
                }

                // 常规清理
                scanAndTrimDirectoryIfNeeded(cacheDir, incomingBytes = size, aggressive = false)

                // 原子写 tmp -> rename
                val finalFile = File(cacheDir, key.toMd5())
                val tmpFile = File(cacheDir, finalFile.name + ".tmp")

                try {
                    BufferedOutputStream(tmpFile.outputStream()).use { bos ->
                        bos.write(data)
                        bos.flush()
                    }
                } catch (e: Exception) {
                    runCatching { tmpFile.delete() }
                    AppLogger.e("TtsCache", "写入磁盘缓存临时文件失败", e)
                    return@withLock
                }

                val renamed = if (finalFile.exists()) {
                    val deleted = runCatching { finalFile.delete() }.getOrDefault(false)
                    if (!deleted) tmpFile.renameTo(finalFile) else tmpFile.renameTo(finalFile)
                } else {
                    tmpFile.renameTo(finalFile)
                }

                if (!renamed) {
                    try {
                        BufferedOutputStream(finalFile.outputStream()).use { bos ->
                            bos.write(data)
                            bos.flush()
                        }
                        runCatching { tmpFile.delete() }
                    } catch (e: Exception) {
                        runCatching { tmpFile.delete() }
                        AppLogger.e("TtsCache", "写入磁盘缓存失败（重命名与回退拷贝均失败）", e)
                        return@withLock
                    }
                }

                runCatching { finalFile.setLastModified(System.currentTimeMillis()) }
                AppLogger.d("TtsCache", "缓存已写入: $key")
            }
        }
    }

    override suspend fun clear() {
        runCatching {
            val memorySize = memoryCache.snapshot().size
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

    // -------------------- 辅助方法 --------------------

    private fun String.toMd5(): String {
        return MessageDigest.getInstance("MD5").digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun getAndEnsureDiskCacheDir(): File? {
        val cacheDir = diskCacheDir
        if (!cacheDir.exists()) {
            if (!cacheDir.mkdirs()) {
                AppLogger.e("TtsCache", "无法创建磁盘缓存目录: ${cacheDir.absolutePath}")
                return null
            }
        }
        return cacheDir
    }

    private fun isExpired(file: File, now: Long = System.currentTimeMillis()): Boolean {
        val age = now - (runCatching { file.lastModified() }.getOrDefault(0L))
        return age > maxAgeMillis
    }

    /**
     * 扫描与修剪目录：
     * - 先进行 TTL 清理；
     * - 计算当前总占用与文件数；
     * - 若 (当前占用 + incomingBytes) 超过上限，或文件数超过上限，则按 LRU（lastModified 升序）删除直至达到目标水位；
     * - aggressive=true 时代表低存储紧急清理：目标水位更低（例如清到 70%），此处采用比 trimTargetRatio 更激进。
     */
    private fun scanAndTrimDirectoryIfNeeded(
        dir: File,
        incomingBytes: Long,
        aggressive: Boolean
    ) {
        if (!dir.exists()) return
        val files = dir.listFiles()?.toList().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
        if (files.isEmpty()) return

        // 1) TTL 清理
        val now = System.currentTimeMillis()
        var deletedByTtl = 0
        var totalBytes = 0L
        files.forEach { f ->
            if (isExpired(f, now)) {
                if (f.delete()) deletedByTtl++
            }
        }
        if (deletedByTtl > 0) {
            AppLogger.i("TtsCache", "TTL 清理完成，删除 $deletedByTtl 个过期文件。")
        }

        // 2) 重新统计
        val aliveFiles = dir.listFiles()?.toList().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
        var fileCount = aliveFiles.size
        totalBytes = aliveFiles.sumOf { it.length() }

        // 3) 判断是否需要 LRU 压缩
        val targetBytes = (maxDiskBytes * (if (aggressive) 0.70 else trimTargetRatio)).toLong()
        val needTrim = (totalBytes + incomingBytes) > maxDiskBytes || fileCount >= maxFileCount

        if (needTrim) {
            // 升序（最旧优先删）
            val sorted = aliveFiles.sortedBy { runCatching { it.lastModified() }.getOrDefault(0L) }
            var deletedCount = 0
            var deletedBytes = 0L

            for (f in sorted) {
                if ((totalBytes + incomingBytes) <= maxDiskBytes && totalBytes <= targetBytes && fileCount < maxFileCount) {
                    break
                }
                val len = f.length()
                if (f.delete()) {
                    deletedCount++
                    deletedBytes += len
                    totalBytes -= len
                    fileCount -= 1
                }
            }
            if (deletedCount > 0) {
                AppLogger.i(
                    "TtsCache",
                    "LRU 清理完成：删除 $deletedCount 个文件，释放 ${deletedBytes}B，" +
                            "当前占用=${totalBytes}B/${maxDiskBytes}B，文件数=$fileCount/$maxFileCount，" +
                            "incoming=$incomingBytes，模式=${if (aggressive) "aggressive" else "normal"}"
                )
            }
        }
    }
}