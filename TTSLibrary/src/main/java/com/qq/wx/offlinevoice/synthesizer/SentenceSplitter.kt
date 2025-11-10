package com.qq.wx.offlinevoice.synthesizer

import java.util.regex.Pattern

/**
 * 句子分割器，用于将长文本按语义合理的句子边界拆分。
 * 支持换行、句末标点、停顿标点、分隔标点等多层分割规则。
 *
 * 更新：
 * - sentenceSplitList 与 sentenceSplitListByLine 直接返回 TtsSynthesizer.TtsBag 列表。
 * - sentenceSplitListByLine 增加长度约束：每个 TtsBag 最多 250 字。
 *   规则（简化版）：
 *     > 当某一行超过 250 字：
 *       1) 从第 250 个字符往前寻找最近的标点（句末/停顿/分隔标点）。
 *       2) 若找到，则在该标点“之后”截断（包含标点）。
 *       3) 若未找到任何标点，则直接在 250 处截断。
 *       4) 继续处理剩余部分，重复上述逻辑，直到剩余长度 ≤ 250。
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
            val segment = safeSubstring(text, range.start, range.end)
            val splitList = if (segment.length >= 10) {
                textToIndexList(segment, range.start, config)
            } else {
                listOf(range)
            }
            result.addAll(splitList)
        }
        return result
    }

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

    fun sentenceSplitList(text: String): List<TtsSynthesizer.TtsBag> {
        val ranges = sentenceSplitByPeriod(text)
        val result = ArrayList<TtsSynthesizer.TtsBag>(ranges.size)
        ranges.forEachIndexed { idx, range ->
            val segment = safeSubstring(text, range.start, range.end)
            result.add(
                TtsSynthesizer.TtsBag(
                    text = segment,
                    index = idx,
                    utteranceId = "utt_$idx",
                    start = range.start,
                    end = range.end
                )
            )
        }
        return result
    }

    /**
     * 按换行分割，并应用“每段最多 250 字”的简化规则。
     * 超长行的拆分逻辑：
     *   - 在 250 位置向前扫描标点；若找到，切分点=该标点位置+1；
     *   - 未找到标点则切分点=起始位置+250；
     *   - 递归处理剩余文本。
     */
    fun sentenceSplitListByLine(text: String): List<TtsSynthesizer.TtsBag> {
        val MAX_LEN = 150
        val initialRanges = toSplit(listOf(BagRange(0, text.length, 0, false)), text, RegexConfig.LineBreak)

        val punctuationSet = hashSetOf(
            '。','?','？','!','！','…',
            '.',
            ',','，',';','；',':','：',
            '、'
        )

        fun splitLongRange(range: BagRange): List<BagRange> {
            val res = mutableListOf<BagRange>()
            var curStart = range.start
            val end = range.end
            while (end - curStart > MAX_LEN) {
                val tentativeCut = curStart + MAX_LEN
                // 回扫标点（不跨越行末）
                var cut = -1
                var i = tentativeCut - 1
                while (i >= curStart) {
                    val c = text.getOrNull(i)
                    if (c != null && c in punctuationSet) {
                        cut = i + 1 // 包含标点
                        break
                    }
                    i--
                }
                if (cut == -1) {
                    cut = tentativeCut // 无标点，硬切
                }
                res += BagRange(curStart, cut, range.type, false)
                curStart = cut
            }
            // 剩余部分
            if (curStart < end) {
                res += BagRange(curStart, end, range.type, false)
            }
            return res
        }

        val normalized = mutableListOf<BagRange>()
        for (r in initialRanges) {
            val len = r.end - r.start
            if (len > MAX_LEN) {
                normalized += splitLongRange(r)
            } else {
                normalized += r
            }
        }

        // 转为 TtsBag（重新编号）
        val bags = ArrayList<TtsSynthesizer.TtsBag>(normalized.size)
        normalized.forEachIndexed { idx, r ->
            val segment = safeSubstring(text, r.start, r.end)
            bags.add(
                TtsSynthesizer.TtsBag(
                    text = segment,
                    index = idx,
                    utteranceId = "utt_$idx",
                    start = r.start,
                    end = r.end
                )
            )
        }
        return bags
    }

    private fun safeSubstring(text: String, start: Int, end: Int): String {
        return try {
            if (start in 0..text.length && end in 0..text.length && start <= end) {
                text.substring(start, end)
            } else ""
        } catch (_: Exception) {
            ""
        }
    }
}