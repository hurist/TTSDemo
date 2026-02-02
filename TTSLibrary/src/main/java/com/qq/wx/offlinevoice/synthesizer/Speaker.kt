package com.qq.wx.offlinevoice.synthesizer

import android.content.Context

enum class Speaker(
    val modelName: String,
    val isMale: Boolean
) {
    MALE1("tts_valle", true),
    MALE2("tts_valle_m468_19_0718", true),
    FEMALE1("tts_valle_caiyu515", false),
    FEMALE2("tts_valle_10373_f561_0619", false);

    val offlineModelName: String
        get() = if (isMale) if (modelName == "tts_valle") "dtn" else "lsl" else "F191"

    fun isResourceAvailable(context: Context): Boolean {
        return PathUtils.checkVoiceResourceExists(context, offlineModelName)
    }

    companion object {
        /** 通过模型名称获取枚举实例,仅匹配在线模型名称 */
        fun fromModelName(name: String?): Speaker? {
            return entries.firstOrNull { it.modelName == name }
        }

        /** 是否为微信离线 TTS 支持的模型名称（含在线与离线两种命名） */
        fun isWxModel(name: String?): Boolean {
            return entries.any { it.modelName == name || it.offlineModelName == name }
        }

        /** 是否为微信离线 TTS 支持的离线模型名称 */
        fun isOfflineModel(name: String?): Boolean {
            return entries.any { it.offlineModelName == name }
        }

        /** 是否为微信离线 TTS 支持的在线模型名称 */
        fun isOnlineModel(name: String?): Boolean {
            return entries.any { it.modelName == name }
        }
    }
}
