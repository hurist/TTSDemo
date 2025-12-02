package com.qq.wx.offlinevoice.synthesizer

import java.util.regex.Pattern

/**
 * 句子分割器，用于将长文本按语义合理的句子边界拆分。
 * 支持换行、句末标点、停顿标点、分隔标点等多层分割规则。
 *
 * 更新：
 * - sentenceSplitList 和 sentenceSplitListByLine 均支持长度约束和 beginPos 精准切分。
 * - 核心逻辑被重构为可复用的私有函数，以减少代码重复。
 */
object SentenceSplitter {

    // 为了清晰，将 BagRange 重命名为 GroupRange，代表一个初始分组（如一行或一个完整句子）
    private data class GroupRange(val start: Int, val end: Int, val level: Int, val flag: Boolean)

    // Piece 代表从 GroupRange 中切分出的最终小片段
    private data class Piece(
        val start: Int,
        val end: Int,
        val originalGroupId: Int,
        var partInGroup: Int, // var for re-numbering after beginPos split
        val groupStart: Int,
        val groupEnd: Int
    )

    /** 正则配置类型 */
    private enum class RegexConfig(val regex: String) {
        /** 换行分割 */
        LineBreak("[\r\n]+"),

        /** 句末标点（句号、问号、感叹号、省略号等） */
        BreakPunctuation("([.](?![0-9])|[。?？!！…]+)[\"”'’)）]*"),

        /** 停顿标点（逗号、分号、冒号等） */
        EndPunctuation("[,，;；]+|[:：][\"“'‘]+"),

        /** 分隔标点（顿号等） */
        SeparationPunctuation("[、]+");
    }

    // 提取出所有用于切分的标点集合
    private val PUNCTUATION_SET = hashSetOf(
        '。', '?', '？', '!', '！', '…',
        '.',
        ',', '，', ';', '；', ':', '：',
        '、'
    )

    /**
     * 根据正则拆分字符串，返回对应的 GroupRange 列表。
     */
    private fun textToIndexList(
        text: String,
        offset: Int,
        config: RegexConfig
    ): List<GroupRange> {
        val list = mutableListOf<GroupRange>()
        val matcher = Pattern.compile(config.regex).matcher(text)
        var lastEnd = 0
        while (matcher.find()) {
            val end = matcher.end()
            list.add(GroupRange(lastEnd + offset, end + offset, config.ordinal, false))
            lastEnd = end
        }
        if (lastEnd != text.length) {
            list.add(GroupRange(lastEnd + offset, text.length + offset, config.ordinal, false))
        }
        return list
    }

    /**
     * 对一批 GroupRange 再按指定正则进行细分。
     */
    private fun toSplit(
        ranges: List<GroupRange>,
        text: String,
        config: RegexConfig
    ): List<GroupRange> {
        val result = mutableListOf<GroupRange>()
        for (range in ranges) {
            val segment = safeSubstring(text, range.start, range.end)
            // 长度判断可以移除或调整，这里为保持原逻辑暂时保留
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
     * [重构] 核心步骤1: 将初始分组按最大长度切分为片段(Piece)
     */
    private fun chunkGroupsByLength(
        groups: List<GroupRange>,
        text: String,
        maxLength: Int
    ): MutableList<Piece> {
        val pieces = mutableListOf<Piece>()
        groups.forEachIndexed { groupId, group ->
            var currentStart = group.start
            val groupEnd = group.end
            var part = 0
            while (groupEnd - currentStart > maxLength) {
                // 从 maxLength 处向前找标点
                val tentativeCut = currentStart + maxLength
                var cutPosition = -1
                for (i in tentativeCut - 1 downTo currentStart) {
                    if (text.getOrNull(i) in PUNCTUATION_SET) {
                        cutPosition = i + 1 // 切在标点之后
                        break
                    }
                }
                // 如果找不到标点，则硬切
                if (cutPosition == -1) {
                    cutPosition = tentativeCut
                }
                pieces.add(Piece(currentStart, cutPosition, groupId, part, group.start, group.end))
                currentStart = cutPosition
                part++
            }
            // 添加剩余的部分
            if (currentStart < groupEnd) {
                pieces.add(Piece(currentStart, groupEnd, groupId, part, group.start, group.end))
            }
        }
        return pieces
    }

    /**
     * [重构] 核心步骤2: 在片段列表中应用 beginPos 切分
     */
    private fun applyBeginPosSplit(
        pieces: MutableList<Piece>,
        beginPos: Int?,
        textLength: Int
    ) {
        if (beginPos == null || beginPos <= 0 || beginPos >= textLength) return

        val targetIndex = pieces.indexOfFirst { it.start < beginPos && beginPos < it.end }
        if (targetIndex != -1) {
            val target = pieces[targetIndex]
            val left = Piece(target.start, beginPos, target.originalGroupId, target.partInGroup, target.groupStart, target.groupEnd)
            val right = Piece(beginPos, target.end, target.originalGroupId, target.partInGroup + 1, target.groupStart, target.groupEnd)

            pieces.removeAt(targetIndex)
            pieces.add(targetIndex, right)
            pieces.add(targetIndex, left)

            // 重新计算该分组内的 part 序号，确保从 0 连续递增
            val groupId = target.originalGroupId
            var currentPart = 0
            for (i in pieces.indices) {
                if (pieces[i].originalGroupId == groupId) {
                    pieces[i] = pieces[i].copy(partInGroup = currentPart)
                    currentPart++
                }
            }
        }
    }

    /**
     * [重构] 核心步骤3: 将最终的片段(Piece)列表转换为 TtsBag 列表
     */
    private fun piecesToTtsBags(pieces: List<Piece>, text: String): List<TtsSynthesizer.TtsBag> {
        val bags = ArrayList<TtsSynthesizer.TtsBag>(pieces.size)
        pieces.forEachIndexed { finalIndex, piece ->
            val segment = safeSubstring(text, piece.start, piece.end)
            bags.add(
                TtsSynthesizer.TtsBag(
                    text = segment,
                    index = finalIndex,
                    utteranceId = "utt_$finalIndex",
                    start = piece.start,
                    end = piece.end,
                    originalGroupId = piece.originalGroupId,
                    partInGroup = piece.partInGroup,
                    groupStart = piece.groupStart,
                    groupEnd = piece.groupEnd
                )
            )
        }
        return bags
    }

    /**
     * 基于“句末标点”切分，并应用长度约束和 beginPos 切分。
     */
    fun sentenceSplitList(text: String, beginPos: Int? = null): List<TtsSynthesizer.TtsBag> {
        val maxLength = 70
        // 1. 获取初始分组（按句末标点）
        var initialGroups = listOf(GroupRange(0, text.length, 0, false))
        initialGroups = toSplit(initialGroups, text, RegexConfig.LineBreak)
        initialGroups = toSplit(initialGroups, text, RegexConfig.BreakPunctuation)

        // 2. 按长度切分分组
        val pieces = chunkGroupsByLength(initialGroups, text, maxLength)

        // 3. 应用 beginPos 切分
        applyBeginPosSplit(pieces, beginPos, text.length)

        // 4. 生成最终结果
        return piecesToTtsBags(pieces, text)
    }

    /**
     * 先按换行分割，再对每行应用长度约束和 beginPos 切分。
     */
    fun sentenceSplitListByLine(text: String, beginPos: Int? = null): List<TtsSynthesizer.TtsBag> {
        val maxLength = 70
        // 1. 获取初始分组（按换行）
        val initialGroups = toSplit(listOf(GroupRange(0, text.length, 0, false)), text, RegexConfig.LineBreak)

        // 2. 按长度切分分组
        val pieces = chunkGroupsByLength(initialGroups, text, maxLength)

        // 3. 应用 beginPos 切分
        applyBeginPosSplit(pieces, beginPos, text.length)

        // 4. 生成最终结果
        return piecesToTtsBags(pieces, text)
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