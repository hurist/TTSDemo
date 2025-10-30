package com.qq.wx.offlinevoice.synthesizer

/**
 * 句子分割工具类
 * 用于将长文本分割成单独的句子。
 *
 * 支持的分隔符：。！？.!?；;
 *
 * 使用示例：
 * ```
 * val text = "这是第一句。这是第二句！这是第三句？"
 * val sentences = SentenceSplitter.splitWithDelimiters(text)
 * // 结果: ["这是第一句。", "这是第二句！", "这是第三句？"]
 * ```
 */
object SentenceSplitter {

    // 常见的中英文句子分隔符的正则表达式
    private val SENTENCE_DELIMITERS = Regex("[。！？.!?；;]+")

    // 默认的最小句子长度，用于过滤掉无意义的过短片段
    private const val MIN_SENTENCE_LENGTH = 2

    /**
     * 根据常见分隔符将文本分割成句子，并丢弃分隔符。
     * 此方法会过滤掉长度小于 MIN_SENTENCE_LENGTH 的句子。
     *
     * @param text 要分割的文本。
     * @return 句子列表（非空，已去除空白，并经过长度过滤）。
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        // 使用正则表达式进行分割，这会自动丢弃分隔符
        return text.split(SENTENCE_DELIMITERS)
            .map { it.trim() } // 去除每个部分前后的空白字符
            .filter { it.length >= MIN_SENTENCE_LENGTH } // 按最小长度过滤
            .toList()
    }

    /**
     * [新增方法]
     * 根据常见分隔符将文本分割成句子，但不包含分隔符本身。
     * 此方法没有最短句子长度限制，但会过滤掉因分割产生的空字符串。
     *
     * @param text 要分割的文本。
     * @return 句子列表，不包含分隔符。
     *
     * 示例：
     * 输入: "你好。世界！A."
     * 输出: ["你好", "世界", "A"]
     */
    fun splitWithoutDelimiters(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        // 使用正则表达式进行分割，这会自动丢弃分隔符
        return text.split(SENTENCE_DELIMITERS)
            .map { it.trim() }       // 去除每个部分前后的空白字符
            .filter { it.isNotEmpty() } // 过滤掉完全是空白或空的字符串部分（不过滤长度）
            .toList()                // 转换回 List
    }

    /**
     * 分割文本并保留分隔符。
     * 分隔符会附加在对应句子的末尾。
     *
     * @param text 要分割的文本。
     * @return 带有原始分隔符的句子列表（每个句子包含其后的分隔符）。
     *
     * 示例：
     * 输入: "你好。世界！"
     * 输出: ["你好。", "世界！"]
     */
    fun splitWithDelimiters(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val sentences = mutableListOf<String>()
        // 创建一个匹配器来查找所有分隔符
        val matcher = SENTENCE_DELIMITERS.toPattern().matcher(text)
        var lastEnd = 0

        // 遍历所有找到的分隔符
        while (matcher.find()) {
            // 截取从上一个分隔符结束位置到当前分隔符结束位置的子字符串
            val sentence = text.substring(lastEnd, matcher.end()).trim()
            if (sentence.length >= MIN_SENTENCE_LENGTH) {
                sentences.add(sentence)
            }
            // 更新下一个句子的起始位置
            lastEnd = matcher.end()
        }

        // 添加最后一个分隔符之后剩余的文本（如果存在）
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd).trim()
            if (remaining.length >= MIN_SENTENCE_LENGTH) {
                sentences.add(remaining)
            }
        }

        return sentences
    }
}