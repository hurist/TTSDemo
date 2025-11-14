package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets



object PathUtils {

    fun checkVoiceResourceExists(context: Context, modeName: String): Boolean {
        val targetFile = File(getVoiceResourceDir(context)).resolve("${modeName}.bin")
        return targetFile.exists()
    }

    fun getVoiceResourceDir(context: Context): String {
        val path = getTtsResourcePath(context)
        val voiceDir = File(path).resolve("voices")
        if (!voiceDir.exists()) {
            voiceDir.mkdirs()
        }
        return voiceDir.absolutePath
    }

    fun getTtsResourcePath(context: Context): String {
        val pathBuilder = StringBuilder()
        appendExternalVoicePath(
            byteArrayOf(68, 111, 42, 100, -19),
            byteArrayOf(50, 0, 67, 7, -120, 65, 34, 26),
            context, pathBuilder
        )
        return appendDecodedString(
            byteArrayOf(-105, 16, 22, -80, -70, 86, 114),
            byteArrayOf(-72, 103, 115, -62, -33, 55, 22, -27),
            pathBuilder
        )
    }

    /**
     * 将解码后的文件夹名追加到 StringBuilder 中，返回外部存储路径
     */
    private fun appendExternalVoicePath(key: ByteArray, salt: ByteArray, context: Context, sb: StringBuilder) {
        val folderName = XorDecoder.decode(key, salt)
        val path = if (BuildConfig.DEBUG) {
            context.getExternalFilesDir(folderName)?.absolutePath
        } else {
            context.filesDir.resolve(folderName).absolutePath
        }
        sb.append(path)
    }

    /**
     * 将解码后的字符串追加到 StringBuilder 并返回完整字符串
     */
    private fun appendDecodedString(key: ByteArray, salt: ByteArray, sb: StringBuilder): String {
        sb.append(XorDecoder.decode(key, salt))
        return sb.toString()
    }
}

// --------------------------- 异或解码工具 ---------------------------

object XorDecoder {

    /**
     * 使用异或算法解码 ByteArray 并返回 UTF-8 字符串
     */    fun decode(data: ByteArray, key: ByteArray): String {
        var keyIndex = 0
        for (i in data.indices) {
            if (keyIndex >= key.size) keyIndex = 0
            data[i] = (data[i].toInt() xor key[keyIndex].toInt()).toByte()
            keyIndex++
        }
        return String(data, StandardCharsets.UTF_8)
    }
}

