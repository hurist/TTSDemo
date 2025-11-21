package com.qq.wx.offlinevoice.synthesizer

fun String.isOnlyPunctuationAndWhitespace(): Boolean {
    if (this.isEmpty()) {
        return true
    }

    //    \p{P} -> Punctuation (标点)
    //    \p{S} -> Symbol (符号, 包括数学=,+,<,>等)
    //    \p{Z} -> Separator (空白分隔符)
    val regex = Regex("^[\\p{P}\\p{S}\\p{Z}]+$")

    return this.matches(regex)
}