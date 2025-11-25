package com.qq.wx.offlinevoice.synthesizer

fun String.isOnlyPunctuationAndWhitespace(): Boolean {
    if (this.isBlank()) return true

    // 只要包含至少一个字母或数字，就不是纯符号
    return this.none { it.isLetterOrDigit() }
}