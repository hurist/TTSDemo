package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import java.nio.charset.StandardCharsets



object PathUtils {

    /**
     * 将解码后的文件夹名追加到 StringBuilder 中，返回外部存储路径
     */
    fun appendExternalVoicePath(key: ByteArray, salt: ByteArray, context: Context, sb: StringBuilder) {
        val folderName = XorDecoder.decode(key, salt)
        sb.append(context.getExternalFilesDir(folderName)?.absolutePath)
    }

    /**
     * 将解码后的字符串追加到 StringBuilder 并返回完整字符串
     */
    fun appendDecodedString(key: ByteArray, salt: ByteArray, sb: StringBuilder): String {
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

