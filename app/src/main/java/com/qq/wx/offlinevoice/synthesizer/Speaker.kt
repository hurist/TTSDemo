package com.qq.wx.offlinevoice.synthesizer

data class Speaker(
    val modelName: String,
    val isMale: Boolean
) {
    val offlineModelName: String
        get() = if (isMale) "lsl" else "F191"
}
