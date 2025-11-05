package com.qq.wx.offlinevoice.synthesizer.cache

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.collection.LruCache
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.DecodedPcm
import com.qq.wx.offlinevoice.synthesizer.clearDirectoryFunctional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
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
 * 内存缓存（LruCache，按字节计数）
 * - 上限：maxMemoryCacheBytes（默认 min(16MB, Runtime.maxMemory/16)，至少 2MB）。
 * - 计费：重写 sizeOf(key, value) 按字节估算（short 2 字节 + 适当头部）。
 * - 淘汰：LruCache 内置 LRU；当超限时自动逐出最久未使用的条目。
 * - 备注：如存在极大条目，可在业务上控制不放入内存（当前实现未强制拒绝，按需调整）。
 *
 * 磁盘缓存（目录 + TTL + LRU）
 * - 目录：context.externalCacheDir/tts_cache（外部存储不可用时跳过磁盘缓存）。
 * - 容量上限：maxDiskBytes = min(256MB, 可用空间 5%)，且不低于 32MB。
 * - 单文件上限：maxEntryBytes = 8MB（超过则跳过写盘，仅入内存）。
 * - 文件数上限：maxFileCount = 10,000（超过则触发 LRU 清理）。
 * - TTL：maxAgeMillis = 7 天，超过立即删除（比 LRU 优先）。
 * - 低存储保护：可用空间 < 300MB 时触发“紧急清理”（更激进水位），仍不足则放弃写盘。
 * - 水位压缩：当 (当前占用 + 待写入大小) > 上限时，按 lastModified 升序删除直至压到 trimTargetRatio（默认 90%）；紧急模式压到更低（如 70%）。
 * - LRU 基准：以文件 lastModified 作为“最近访问”时间；命中 get 时会触摸（setLastModified(now)）。
 * - 原子写：写入 {md5}.tmp，成功后 renameTo 为 {md5}，必要时删除旧文件再重命名；异常时删除 .tmp。
 * - 损坏修复：读出错（序列化不兼容/损坏）即删除该文件，避免反复失败。
 *
 * 并发与一致性
 * - 文件系统操作（读/写/清理/触摸）在 diskMutex 下串行化，避免竞态。
 * - IO 使用 Dispatchers.IO；内存缓存的并发按 LruCache 约束，建议由上层保证调用序或限定在同一线程上下文。
 *
 * 清理触发时机
 * - get：若命中文件已过期（TTL），立刻删除；命中有效文件会触摸 lastModified。
 * - put：写入前执行容量/文件数/低存储校验与清理，确保写入后不超标。
 * - clear：提供显式清理 API（同时清内存与磁盘）。
 * - （可选）上层可按需定时触发 scan/trimming，以平滑峰值开销。
 *
 * Key 与文件命名
 * - 文件名使用 key 的 MD5，避免非法字符与过长路径。
 * - 建议 key 充分包含影响 PCM 的维度（文本、speaker/voice、speed、sampleRate 等），避免冲突复用。
 *
 * 适用范围与扩展
 * - 适合单进程、中等规模（<= 数万文件、<= 数百 MB）场景。
 * - 若目录规模极大或需要更强日志式恢复，可考虑替换底层为 DiskLruCache 或维护独立索引；当前实现优先简单可控。
 */
class TtsCacheImpl(private val context: Context) : TtsCache {

    // -------------------- 配置（可按需调优/改为从外部注入） --------------------

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

    // -------------------- 内存缓存（按字节计数的 LRU） --------------------

    // 估算 PCM 的内存占用（short 2 字节，外加少量对象开销）
    private fun estimateMemoryBytes(value: DecodedPcm): Int {
        val pcmBytes = try {
            (value.pcmData.size * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } catch (_: Throwable) {
            // 防御：若结构字段不同，退化为固定估算
            0
        }
        return (pcmBytes + 64).coerceAtLeast(64) // 加 64B 头部估算
    }

    // 内存缓存：快速访问（将 sizeOf 定义为字节数）
    private val memoryCache: LruCache<String, DecodedPcm> =
        object : LruCache<String, DecodedPcm>(maxMemoryCacheBytes) {
            override fun sizeOf(key: String, value: DecodedPcm): Int = estimateMemoryBytes(value)
        }

    // -------------------- 磁盘缓存目录与并发互斥 --------------------

    // 磁盘缓存：持久化（外部缓存目录 tts_cache）
    private val diskCacheDir: File by lazy {
        val base = context.externalCacheDir ?: context.cacheDir
        File(base, "tts_cache").apply { mkdirs() }
    }

    // 统一的文件并发互斥，避免清理/写入/读取竞争（读取不一定要锁，但为了一致性，命中后更新 lastModified 时也在锁内）
    private val diskMutex = Mutex()

    // -------------------- 对外 API --------------------

    override suspend fun get(key: String): DecodedPcm? {
        // 1. 先从内存查找
        memoryCache[key]?.let {
            AppLogger.d("TtsCache", "缓存命中 (内存): $key")
            return it
        }

        // 2. 再从磁盘查找（带 TTL 检查与 LRU 触摸）
        return try {
            val cacheDir = getAndEnsureDiskCacheDir()
            if (cacheDir == null) {
                AppLogger.w("TtsCache", "磁盘缓存目录不可用，跳过写入磁盘缓存: $key")
                return null
            }
            val file = File(cacheDir, key.toMd5())
            if (!file.exists()) return null

            // 读取与 TTL 检查、LRU 触摸在 IO + 互斥中进行
            withContext(Dispatchers.IO) {
                diskMutex.withLock {
                    // TTL：过期则删除
                    val now = System.currentTimeMillis()
                    if (isExpired(file, now)) {
                        runCatching { file.delete() }
                        AppLogger.i("TtsCache", "TTL 过期，已删除磁盘缓存: ${file.name}")
                        return@withLock null
                    }

                    // 反序列化读取
                    val pcm = try {
                        withTimeout(3000) {
                            ObjectInputStream(file.inputStream()).use { it.readObject() as DecodedPcm }
                        }
                    } catch (e: Exception) {
                        // 读失败（可能是旧版本/损坏），删除文件避免反复失败
                        AppLogger.e("TtsCache", "读取磁盘缓存失败（将删除损坏文件）", e)
                        runCatching { file.delete() }
                        return@withLock null
                    }

                    // 放入内存缓存以备下次快速访问
                    memoryCache.put(key, pcm)
                    AppLogger.d("TtsCache", "缓存命中 (磁盘): $key")

                    // LRU 触摸：更新 lastModified 为当前时间
                    runCatching { file.setLastModified(now) }

                    pcm
                }
            }
        } catch (e: Exception) {
            AppLogger.e("TtsCache", "读取磁盘缓存失败", e)
            null
        }
    }

    override suspend fun put(key: String, pcm: DecodedPcm) {
        // 1) 内存：总是尝试放入（LruCache 会在超限时自动淘汰旧项）
        memoryCache.put(key, pcm)

        // 2) 磁盘：受限于容量/低存储/单文件大小/TTL 清理等策略
        try {
            val cacheDir = getAndEnsureDiskCacheDir()
            if (cacheDir == null) {
                AppLogger.w("TtsCache", "磁盘缓存目录不可用，跳过写入磁盘缓存: $key")
                return
            }

            // 估算条目大小（用于预判与清理）；实际落盘大小以文件为准
            val approxBytes = estimateDiskBytes(pcm)

            // 过大条目直接不落盘
            if (approxBytes > maxEntryBytes) {
                AppLogger.w("TtsCache", "条目过大，跳过磁盘写入: key=$key, approx=${approxBytes}B, max=$maxEntryBytes")
                return
            }

            withContext(Dispatchers.IO) {
                diskMutex.withLock {
                    // 低存储保护：空间过低则先清理，再不足则放弃写盘
                    val free = cacheDir.usableSpace
                    if (free < lowSpaceFreeBytesThreshold) {
                        AppLogger.w("TtsCache", "可用空间过低(usable=${free}B < $lowSpaceFreeBytesThreshold)，触发紧急清理。")
                        scanAndTrimDirectoryIfNeeded(cacheDir, incomingBytes = approxBytes, aggressive = true)
                        val freeAfter = cacheDir.usableSpace
                        if (freeAfter < lowSpaceFreeBytesThreshold) {
                            AppLogger.w("TtsCache", "紧急清理后可用空间仍不足(usable=${freeAfter}B)，跳过写入: $key")
                            return@withLock
                        }
                    }

                    // 常规清理：TTL + 超限压缩到目标水位
                    scanAndTrimDirectoryIfNeeded(cacheDir, incomingBytes = approxBytes, aggressive = false)

                    // 原子写：tmp -> renameTo 正式
                    val finalFile = File(cacheDir, key.toMd5())
                    val tmpFile = File(cacheDir, finalFile.name + ".tmp")

                    // 先写 tmp
                    try {
                        ObjectOutputStream(tmpFile.outputStream()).use { stream ->
                            stream.writeObject(pcm)
                            stream.flush()
                        }
                    } catch (e: Exception) {
                        runCatching { tmpFile.delete() }
                        AppLogger.e("TtsCache", "写入磁盘缓存临时文件失败", e)
                        return@withLock
                    }

                    // 重命名到正式文件（覆盖旧文件）
                    val renamed = if (finalFile.exists()) {
                        // 为保证覆盖成功，先删除旧文件再 rename
                        val deleted = runCatching { finalFile.delete() }.getOrDefault(false)
                        if (!deleted) {
                            // 若删除失败，尝试直接用 rename（有的文件系统允许覆盖）
                            tmpFile.renameTo(finalFile)
                        } else {
                            tmpFile.renameTo(finalFile)
                        }
                    } else {
                        tmpFile.renameTo(finalFile)
                    }

                    if (!renamed) {
                        // 最后尝试拷贝写入（极少数设备 rename 失败）
                        try {
                            ObjectOutputStream(finalFile.outputStream()).use { stream ->
                                stream.writeObject(pcm)
                                stream.flush()
                            }
                            runCatching { tmpFile.delete() }
                        } catch (e: Exception) {
                            runCatching { tmpFile.delete() }
                            AppLogger.e("TtsCache", "写入磁盘缓存失败（重命名与回退拷贝均失败）", e)
                            return@withLock
                        }
                    }

                    // 更新 LRU 时间
                    runCatching { finalFile.setLastModified(System.currentTimeMillis()) }

                    // 维持原有日志
                    AppLogger.d("TtsCache", "缓存已写入: $key")
                }
            }
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

    // -------------------- 辅助方法 --------------------

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

        val cacheDir = diskCacheDir
        // 如果目录不存在，则尝试创建它（包括所有必要的父目录）
        if (!cacheDir.exists()) {
            if (!cacheDir.mkdirs()) {
                AppLogger.e("TtsCache", "无法创建磁盘缓存目录: ${cacheDir.absolutePath}")
                return null // 如果创建失败，返回 null
            }
        }
        return cacheDir
    }

    // 判断文件是否过期（TTL）
    private fun isExpired(file: File, now: Long = System.currentTimeMillis()): Boolean {
        val age = now - (runCatching { file.lastModified() }.getOrDefault(0L))
        return age > maxAgeMillis
    }

    // 估算磁盘写入的大小（用于清理前的预估）；以 2 字节/short 简单估算
    private fun estimateDiskBytes(value: DecodedPcm): Long {
        return try {
            (value.pcmData.size.toLong() * 2L) + 64L // 加一点头部估算
        } catch (_: Throwable) {
            256L // 兜底
        }
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