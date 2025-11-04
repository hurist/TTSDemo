package com.qq.wx.offlinevoice.synthesizer

data class Speaker(
    val modelName: String,
    val isMale: Boolean
) {
    val offlineModelName: String
        get() = if (isMale) if (modelName == "tts_valle") "dtn" else "lsl" else "F191"
}
