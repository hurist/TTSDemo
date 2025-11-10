package com.hurist.ttsdemo // 改成你项目的实际包名

import android.content.Context
import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.PathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 一个工具类，用于管理解压 assets 中的 zip 资源包、
 * 根据清单文件校验其完整性，并在必要时进行修复。
 */
object AssetUnpacker {

    private const val TAG = "AssetUnpacker"
    private const val ZIP_FILE_NAME = "tts.zip"
    private const val MANIFEST_FILE_NAME = "manifest.json"
    private var TARGET_DIR_NAME = "" // 文件将被解压到的目标目录名
    private const val BUFFER_SIZE = 8192 // 用于 I/O 操作的 8KB 缓冲区

    /**
     * 确保所有来自 asset zip 的资源都已正确解压并有效。
     * 这是主要的公共入口方法。
     * 它会在首次运行时执行完整解压，在后续运行时执行校验和修复。
     *
     * @param context 应用上下文。
     * @return 如果资源准备就绪则返回 true，如果发生不可恢复的错误则返回 false。
     */
    suspend fun ensureResourcesAreReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        TARGET_DIR_NAME = PathUtils.getVoicePath(context)
        val targetDir = File(TARGET_DIR_NAME)

        if (!targetDir.exists()) {
            Log.i(TAG, "目标目录不存在，执行完整解压。")
            return@withContext try {
                targetDir.mkdirs()
                unpackFullArchive(context, targetDir)
                Log.i(TAG, "完整解压成功。")
                true
            } catch (e: Exception) {
                Log.e(TAG, "完整解压失败，正在清理。", e)
                targetDir.deleteRecursively() // 清理可能不完整的文件
                false
            }
        } else {
            Log.i(TAG, "目标目录已存在，开始校验完整性...")
            return@withContext verifyAndRepair(context, targetDir)
        }
    }

    private fun verifyAndRepair(context: Context, targetDir: File): Boolean {
        try {
            val manifest = parseManifest(context)
            val missingOrCorruptedFiles = mutableSetOf<String>()

            // 1. 检查清单中列出的每个文件
            for ((filePath, expectedHash) in manifest) {
                val localFile = File(targetDir, filePath)
                if (!localFile.exists()) {
                    missingOrCorruptedFiles.add(filePath)
                } else {
                    val actualHash = calculateFileSha256(localFile)
                    if (actualHash != expectedHash) {
                        missingOrCorruptedFiles.add(filePath)
                    }
                }
            }

            // 2. 如果需要，进行修复
            if (missingOrCorruptedFiles.isNotEmpty()) {
                Log.w(TAG, "发现 ${missingOrCorruptedFiles.size} 个缺失或损坏的文件，开始修复。")
                Log.d(TAG, "待修复文件列表: $missingOrCorruptedFiles")
                repairFiles(context, targetDir, missingOrCorruptedFiles)
                Log.i(TAG, "修复流程完成。")
            } else {
                Log.i(TAG, "所有文件均有效，无需操作。")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "校验和修复过程中发生错误，建议进行一次完整的清理和解压。", e)
            // 发生严重故障，最好的办法是清理掉目录，让下次启动时触发完整解压
            targetDir.deleteRecursively()
            return false
        }
    }

    private fun unpackFullArchive(context: Context, targetDir: File) {
        context.assets.open(ZIP_FILE_NAME).use { inputStream ->
            ZipInputStream(inputStream).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    val newFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        // 确保父目录存在
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            zipStream.copyTo(fos, BUFFER_SIZE)
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        }
    }

    private fun repairFiles(context: Context, targetDir: File, filesToRepair: Set<String>) {
        // 首先，删除所有损坏的文件，以确保写入的是干净的文件
        filesToRepair.forEach {
            File(targetDir, it).delete()
        }

        // 现在，仅从 zip 包中提取需要的文件
        context.assets.open(ZIP_FILE_NAME).use { inputStream ->
            ZipInputStream(inputStream).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (filesToRepair.contains(entry.name)) {
                        val targetFile = File(targetDir, entry.name)
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            zipStream.copyTo(fos, BUFFER_SIZE)
                        }
                        Log.i(TAG, "已修复: ${entry.name}")
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        }
    }

    private fun parseManifest(context: Context): Map<String, String> {
        val manifestJson = context.assets.open(MANIFEST_FILE_NAME).bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(manifestJson)
        val map = mutableMapOf<String, String>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jsonObject.getString(key)
        }
        return map
    }

    private fun calculateFileSha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            // 将字节数组转换为十六进制字符串
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "为文件计算 SHA-256 失败: ${file.path}", e)
            null // 失败时返回 null，这将触发不匹配并启动修复
        }
    }
}