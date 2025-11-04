package com.qq.wx.offlinevoice.synthesizer

import java.io.Serializable

// 新增一个数据类来封装解码结果
data class DecodedPcm(
    val pcmData: ShortArray,
    val sampleRate: Int
): Serializable {
    // 为 ShortArray 实现 equals 和 hashCode，以保证数据类的正确行为
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedPcm
        if (!pcmData.contentEquals(other.pcmData)) return false
        if (sampleRate != other.sampleRate) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pcmData.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}