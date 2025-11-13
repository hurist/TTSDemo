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

    /**
     * 基于“句末标点”切分，返回 TtsBag 列表。
     * 这里每一个片段视作一行（无换行场景），originalGroupId=idx，partInGroup=0。
     */
    fun sentenceSplitList(text: String): List<TtsSynthesizer.TtsBag> {
        val ranges = sentenceSplitByPeriod(text)
        val bags = ArrayList<TtsSynthesizer.TtsBag>(ranges.size)
        ranges.forEachIndexed { idx, r ->
            val segment = safeSubstring(text, r.start, r.end)
            bags.add(
                TtsSynthesizer.TtsBag(
                    text = segment,
                    index = idx,
                    utteranceId = "utt_$idx",
                    start = r.start,
                    end = r.end,
                    originalGroupId = idx,
                    partInGroup = 0,
                    groupStart = r.start,
                    groupEnd = r.end
                )
            )
        }
        return bags
    }

    /**
     * 先按换行分割，再对每行应用“最多 250 字”的切分规则，返回物理段列表（TtsBag）。
     * 超长行拆分：从 250 处向前寻找最近标点（句末/停顿/分隔），若找到则切在标点之后；否则硬切 250。
     * 所有分段保留行元数据 originalGroupId / partInGroup / groupStart / groupEnd。
     *
     * 新增：beginPos (可选)
     * 若 beginPos 位于最终某一分段的内部 (start < beginPos < end)，则将该分段在 beginPos 处分裂为两段：
     *   左段 [start, beginPos)，右段 [beginPos, end)
     * “标点归右”自然满足（标点字符位于 beginPos 位置时落入右段）。
     */
    fun sentenceSplitListByLine(text: String, beginPos: Int? = null): List<TtsSynthesizer.TtsBag> {
        val MAX_LEN = 150
        val lineRanges = toSplit(listOf(BagRange(0, text.length, 0, false)), text, RegexConfig.LineBreak)

        val punctuationSet = hashSetOf(
            '。','?','？','!','！','…',
            '.',
            ',','，',';','；',':','：',
            '、'
        )

        data class Piece(val start: Int, val end: Int, val lineId: Int, val part: Int, val lineStart: Int, val lineEnd: Int)

        val pieces = mutableListOf<Piece>()
        lineRanges.forEachIndexed { lineIdx, r ->
            var curStart = r.start
            val lineEnd = r.end
            var part = 0
            while (lineEnd - curStart > MAX_LEN) {
                val tentativeCut = curStart + MAX_LEN
                var cut = -1
                var i = tentativeCut - 1
                while (i >= curStart) {
                    val c = text.getOrNull(i)
                    if (c != null && c in punctuationSet) { cut = i + 1; break }
                    i--
                }
                if (cut == -1) cut = tentativeCut
                pieces += Piece(curStart, cut, lineIdx, part, r.start, r.end)
                curStart = cut
                part++
            }
            if (curStart < lineEnd) {
                pieces += Piece(curStart, lineEnd, lineIdx, part, r.start, r.end)
            }
        }

        // beginPos 二次拆分（后处理）
        if (beginPos != null && beginPos > 0 && beginPos < text.length) {
            val targetIndex = pieces.indexOfFirst { it.start < beginPos && beginPos < it.end }
            if (targetIndex >= 0) {
                val target = pieces[targetIndex]
                // 拆分为两段，标点归右：左段不包含 beginPos 位置字符
                val left = Piece(target.start, beginPos, target.lineId, target.part, target.lineStart, target.lineEnd)
                val right = Piece(beginPos, target.end, target.lineId, target.part + 1, target.lineStart, target.lineEnd)

                // 替换原目标段
                pieces.removeAt(targetIndex)
                pieces.add(targetIndex, right)
                pieces.add(targetIndex, left)

                // 重新计算该行内的 part 序号（保持从 0 递增）
                val lineId = target.lineId
                var p = 0
                for (i in pieces.indices) {
                    val piece = pieces[i]
                    if (piece.lineId == lineId) {
                        pieces[i] = piece.copy(part = p)
                        p++
                    }
                }
            }
        }

        val bags = ArrayList<TtsSynthesizer.TtsBag>(pieces.size)
        pieces.forEachIndexed { segIdx, p ->
            val segment = safeSubstring(text, p.start, p.end)
            bags.add(
                TtsSynthesizer.TtsBag(
                    text = segment,
                    index = segIdx,
                    utteranceId = "utt_$segIdx",
                    start = p.start,
                    end = p.end,
                    originalGroupId = p.lineId,
                    partInGroup = p.part,
                    groupStart = p.lineStart,
                    groupEnd = p.lineEnd
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