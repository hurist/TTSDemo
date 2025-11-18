package com.qq.wx.offlinevoice.synthesizer

/**
 * 文本清理工具类（适用于 TTS、小说朗读、字幕分句等场景）
 */
object WrTextUtil {

    /** 要过滤的标点和特殊字符 */
    private const val FILTER_CHARS =
        "·`,，.。!！?？：:；;（(）)¥「」＂、[【】]{}#%-*+=_\\\\／|~＜《＞》$€^•’@#%^&*_+’\"‘’”“—…"

    /** 去除 [插图] 之类标记 */
    fun filterIllustration(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text.replace("[插图]", "")
    }

    /**
     * 去掉句首句尾的无意义符号。
     * 对中文文本清理更彻底，对英文保留空格。
     */
    fun filterSpecialChars(text: String?): String? {
        if (text.isNullOrBlank()) return null

        var s = filterIllustration(text)?.trim() ?: return null

        // 找到第一个不是特殊字符的索引
        var start = 0
        while (start < s.length && FILTER_CHARS.contains(s[start])) start++

        // 找到最后一个不是特殊字符的索引
        var end = s.length - 1
        while (end >= 0 && FILTER_CHARS.contains(s[end])) end--

        // 英文句子（不含中文且有空格）则不过滤末尾
        val isChinese = s.any { it in '\u4e00'..'\u9fff' }
        if (!isChinese && s.contains(' ')) return s

        // 截取有效部分
        if (start <= end) {
            s = s.substring(start, end + 1)
        }

        // 如果清理后为空，返回原文
        return s.ifEmpty { text }
    }

    /**
     * 按正则表达式分割字符串，同时返回每个片段在原文中的起始索引。
     *
     * @return Pair(索引数组, 分段字符串数组)
     */
    fun splitWithIndices(input: String, regex: String): Pair<IntArray, Array<String>> {
        val pattern = Regex(regex)
        val indices = mutableListOf<Int>()
        val segments = mutableListOf<String>()
        var lastIndex = 0

        pattern.findAll(input).forEach { match ->
            val segment = input.substring(lastIndex, match.range.first).trim()
            if (segment.isNotEmpty()) {
                indices.add(lastIndex)
                segments.add(segment)
            }
            lastIndex = match.range.last + 1
        }

        val tail = input.substring(lastIndex).trim()
        if (tail.isNotEmpty()) {
            indices.add(lastIndex)
            segments.add(tail)
        }

        return indices.toIntArray() to segments.toTypedArray()
    }

    /** 去除首尾空白字符 */
    fun strip(text: String?): String? = text?.trim()
}
