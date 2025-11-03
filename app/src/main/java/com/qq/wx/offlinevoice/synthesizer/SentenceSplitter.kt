package com.qq.wx.offlinevoice.synthesizer


import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 句子分割器，用于将长文本按语义合理的句子边界拆分。
 * 支持换行、句号、逗号、顿号等多层分割规则。
 */
object SentenceSplitter {

    /** 正则配置类型 */
    enum class RegexConfig(val regex: String) {
        /** 换行分割 */
        LineBreak("[\r\n]+"),

        /** 句末标点（句号、问号、感叹号、省略号等） */
        BreakPunctuation("([.](?![0-9])|[。?？!！…]+)[\"”'’)）]*"),

        /** 停顿标点（逗号、分号、冒号等） */
        EndPunctuation("[,，;；]+|[:：][\"“'‘]+"),

        /** 分隔标点（顿号等） */
        SeparationPunctuation("[、]+");
    }

    /**
     * 根据正则拆分字符串，返回对应的 BagRange 列表。
     */
    private fun textToIndexList(
        text: String,
        offset: Int,
        config: RegexConfig
    ): List<BagRange> {
        val list = mutableListOf<BagRange>()
        val matcher = Pattern.compile(config.regex).matcher(text)

        var lastEnd = 0
        while (matcher.find()) {
            val end = matcher.end()
            list.add(BagRange(lastEnd + offset, end + offset, config.ordinal, false))
            lastEnd = end
        }

        // 处理剩余部分
        if (lastEnd != text.length) {
            list.add(BagRange(lastEnd + offset, text.length + offset, config.ordinal, false))
        }

        return list
    }

    /**
     * 对一批 BagRange 再按指定正则进行细分。
     */
    private fun toSplit(
        ranges: List<BagRange>,
        text: String,
        config: RegexConfig
    ): List<BagRange> {
        val result = mutableListOf<BagRange>()

        for (range in ranges) {
            val segment = try {
                text.substring(range.start, range.end)
            } catch (e: Exception) {
                ""
            }

            val splitList = if (segment.length >= 10) {
                textToIndexList(segment, range.start, config)
            } else {
                listOf(range)
            }

            result.addAll(splitList)
        }

        return result
    }

    /**
     * 按多级标点拆分句子。
     */
    fun sentenceSplit(text: String): List<BagRange> {
        var result = listOf(BagRange(0, text.length, 0, false))
        result = toSplit(result, text, RegexConfig.LineBreak)
        result = toSplit(result, text, RegexConfig.BreakPunctuation)
        result = toSplit(result, text, RegexConfig.EndPunctuation)
        result = toSplit(result, text, RegexConfig.SeparationPunctuation)
        return result
    }

    /**
     * 仅按句号/问号/感叹号拆分（不含逗号等）。
     */
    fun sentenceSplitByPeriod(text: String): List<BagRange> {
        var result = listOf(BagRange(0, text.length, 0, false))
        result = toSplit(result, text, RegexConfig.LineBreak)
        result = toSplit(result, text, RegexConfig.BreakPunctuation)
        return result
    }

    fun sentenceSplitList(text: String): List<String> {
        val ranges = sentenceSplitByPeriod(text)
        val result = mutableListOf<String>()
        for (range in ranges) {
            val segment = try {
                text.substring(range.start, range.end)
            } catch (e: Exception) {
                ""
            }
            result.add(segment)
        }
        return result
    }

    fun sentenceSplitListByLine(text: String): List<String> {
        val ranges = toSplit(listOf(BagRange(0, text.length, 0, false)), text, RegexConfig.LineBreak)
        val result = mutableListOf<String>()
        for (range in ranges) {
            val segment = try {
                text.substring(range.start, range.end)
            } catch (e: Exception) {
                ""
            }
            result.add(segment)
        }
        return result
    }
}
