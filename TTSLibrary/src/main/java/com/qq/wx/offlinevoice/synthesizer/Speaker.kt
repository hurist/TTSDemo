package com.qq.wx.offlinevoice.synthesizer

import android.content.Context

enum class Speaker(
    val modelName: String,
    val isMale: Boolean
) {
    MALE1("tts_valle_m468_19_0718", true),
    MALE2("tts_valle", true),
    FEMALE1("tts_valle_caiyu515", false),
    FEMALE2("tts_valle_10373_f561_0619", false);

    val offlineModelName: String
        get() = if (isMale) if (modelName == "tts_valle") "dtn" else "lsl" else "F191"

    fun isResourceAvailable(context: Context): Boolean {
        return PathUtils.checkVoiceResourceExists(context, offlineModelName)
    }

    companion object {
        fun fromModelName(name: String?): Speaker? {
            return entries.firstOrNull { it.modelName == name }
        }
    }
}
