package com.qq.wx.offlinevoice.synthesizer

/**
 * 句子分割工具类
 * 用于将长文本分割成单独的句子
 */
object SentenceSplitter {
    
    // 常见的中英文句子分隔符
    private val SENTENCE_DELIMITERS = Regex("[。！？.!?；;]+")
    
    // 最小句子长度，避免过短的片段
    private const val MIN_SENTENCE_LENGTH = 2
    
    /**
     * 根据常见分隔符将文本分割成句子
     * @param text 要分割的文本
     * @return 句子列表（非空，已去除空白）
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }
        
        // 按句子分隔符分割
        val parts = text.split(SENTENCE_DELIMITERS)
        
        return parts
            .map { it.trim() }
            .filter { it.length >= MIN_SENTENCE_LENGTH }
            .toList()
    }
    
    /**
     * 分割文本并保留分隔符
     * @param text 要分割的文本
     * @return 带有原始分隔符的句子列表
     */
    fun splitWithDelimiters(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }
        
        val sentences = mutableListOf<String>()
        val matcher = SENTENCE_DELIMITERS.toPattern().matcher(text)
        var lastEnd = 0
        
        while (matcher.find()) {
            val sentence = text.substring(lastEnd, matcher.end()).trim()
            if (sentence.length >= MIN_SENTENCE_LENGTH) {
                sentences.add(sentence)
            }
            lastEnd = matcher.end()
        }
        
        // 添加剩余的文本（如果有）
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd).trim()
            if (remaining.length >= MIN_SENTENCE_LENGTH) {
                sentences.add(remaining)
            }
        }
        
        return sentences
    }
}
