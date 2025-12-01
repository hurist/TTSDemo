package com.qq.wx.offlinevoice.synthesizer

fun String.isOnlyPunctuationAndWhitespace(): Boolean {
    if (this.isBlank()) return true

    // 只要包含至少一个字母或数字，就不是纯符号
    return this.none { it.isLetterOrDigit() }
}
private val charMap = mapOf(
    "「" to "“",
    "」" to "”",
    "『" to "“",
    "』" to "”",
)

private val specialRegex = Regex(charMap.keys.joinToString(prefix = "[", postfix = "]") { Regex.escape(it) })

fun String.replaceSpecialChar(): String =
    replace(specialRegex) { matchResult ->
        charMap[matchResult.value] ?: matchResult.value
    }