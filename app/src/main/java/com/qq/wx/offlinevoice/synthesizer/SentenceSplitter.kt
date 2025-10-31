package com.qq.wx.offlinevoice.synthesizer

import java.util.ArrayDeque

/**
 * 句子分割工具类
 * 用于将长文本分割成单独的句子，并能智能处理对话、引文、嵌入式引述和不规范的格式。
 */
object SentenceSplitter {

    // 常见的中英文句子分隔符的正则表达式
    private val SENTENCE_DELIMITERS_REGEX = Regex("[。！？.!?；;]+")
    // 将分隔符转换为字符集合，便于单字符查找
    private val SENTENCE_DELIMITER_CHARS = setOf('。', '！', '？', '.', '!', '?', '；', ';')

    // 默认的最小句子长度，用于过滤掉无意义的过短片段
    private const val MIN_SENTENCE_LENGTH = 2

    // 定义成对的标点
    private val OPENING_PUNCTUATION = setOf('“', '‘', '(', '（', '《', '『')
    private val CLOSING_PUNCTUATION_MAP = mapOf(
        '”' to '“', '’' to '‘', ')' to '(', '）' to '（', '》' to '《', '』' to '『'
    )

    /**
     * 根据常见分隔符将文本分割成句子，并丢弃分隔符。
     * (此方法实现不变)
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(SENTENCE_DELIMITERS_REGEX)
            .map { it.trim() }
            .filter { it.length >= MIN_SENTENCE_LENGTH }
            .toList()
    }


    /**
     * [最终重写方法]
     * 分割文本并保留分隔符，引入了“向后看”（Lookahead）逻辑来区分嵌入式引述和独立对话句。
     *
     * 分割规则 (按优先级):
     * 1. 标准句末规则: 如果句末标点（。！？）出现在所有成对标点之外，则分割。
     * 2. 闭合标点规则: 如果一个闭合标点（”、』、）使嵌套层级归零，则“向后看”：
     *    a. 如果其后紧跟的是句子的延续（普通文字），则不分割。
     *    b. 如果其后是句子的结尾（换行、文末、另一个句末标点），则分割。
     * 3. 换行符规则: 如果在引号/括号内遇到换行，立即分割，并重置状态。
     *
     * @param text 要分割的文本。
     * @return 带有原始分隔符的、智能分割后的句子列表。
     */
    fun splitWithDelimiters(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val sentences = mutableListOf<String>()
        val punctuationStack = ArrayDeque<Char>()
        var lastSplitIndex = 0

        text.forEachIndexed { index, char ->
            var isSplitPoint = false

            // 1. 处理标点栈
            when (char) {
                in OPENING_PUNCTUATION -> punctuationStack.addLast(char)
                in CLOSING_PUNCTUATION_MAP.keys -> {
                    if (punctuationStack.isNotEmpty() && punctuationStack.last() == CLOSING_PUNCTUATION_MAP[char]) {
                        punctuationStack.removeLast()
                    }
                }
            }

            // 2. 根据当前字符和标点栈状态，判断是否为分割点
            when {
                // 主要规则：标准句末标点，且不在任何括号/引号内
                char in SENTENCE_DELIMITER_CHARS && punctuationStack.isEmpty() -> {
                    isSplitPoint = true
                }

                // 辅助规则1：闭合标点（”、』等）刚刚使栈变为空
                char in CLOSING_PUNCTUATION_MAP.keys && punctuationStack.isEmpty() -> {
                    // “向后看”，检查这个闭合标点是否真正结束了一个句子
                    if (isSentenceBreakAfter(text, index)) {
                        isSplitPoint = true
                    }
                }

                // 辅助规则2：换行符，用于处理不规范的、未闭合的对话
                char == '\n' && punctuationStack.isNotEmpty() -> {
                    isSplitPoint = true
                    // 强制清空栈，以防错误状态污染下一行的解析
                    punctuationStack.clear()
                }
            }

            // 3. 如果确定是分割点，则执行分割
            if (isSplitPoint) {
                val sentence = text.substring(lastSplitIndex, index + 1).trim()
                if (sentence.isNotEmpty()) {
                    sentences.add(sentence)
                }
                lastSplitIndex = index + 1
            }
        }

        // 4. 添加最后一个分割点之后剩余的文本
        if (lastSplitIndex < text.length) {
            val remaining = text.substring(lastSplitIndex).trim()
            if (remaining.isNotEmpty()) {
                sentences.add(remaining)
            }
        }

        return sentences.filter { it.length >= MIN_SENTENCE_LENGTH }
    }

    /**
     * “向后看”辅助函数。
     * 检查在给定的索引（通常是一个闭合标点的位置）之后，是否是一个句子的自然结束点。
     *
     * @param text 全文
     * @param currentIndex 当前检查点的索引
     * @return 如果是句子结束点，返回 true；否则返回 false。
     */
    private fun isSentenceBreakAfter(text: String, currentIndex: Int): Boolean {
        var nextIndex = currentIndex + 1

        // 跳过所有后续的空白字符
        while (nextIndex < text.length && text[nextIndex].isWhitespace()) {
            // 如果在空白字符中发现了换行符，这本身就是一个强烈的句子结束信号
            if (text[nextIndex] == '\n') {
                return true
            }
            nextIndex++
        }

        // 如果已经到达文本末尾，那么这自然是一个结束点
        if (nextIndex >= text.length) {
            return true
        }

        // 获取第一个非空白字符
        val nextChar = text[nextIndex]

        // 如果下一个字符是句末标点，或者另一个开/关标点，
        // 那么当前闭合标点很可能结束了一个独立的对话句。
        // 反之，如果是一个普通文字（如“这”），则说明是嵌入式引述，不应分割。
        return nextChar in SENTENCE_DELIMITER_CHARS ||
                nextChar in OPENING_PUNCTUATION ||
                nextChar in CLOSING_PUNCTUATION_MAP.keys
    }
}