package com.qq.wx.offlinevoice.synthesizer

/**
 * Utility for splitting text into sentences
 */
object SentenceSplitter {
    
    // Common Chinese and English sentence delimiters
    private val SENTENCE_DELIMITERS = Regex("[。！？.!?；;]+")
    
    // Minimum sentence length to avoid overly short fragments
    private const val MIN_SENTENCE_LENGTH = 2
    
    /**
     * Split text into sentences based on common delimiters
     * @param text The text to split
     * @return List of sentences (non-empty, trimmed)
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }
        
        // Split by sentence delimiters
        val parts = text.split(SENTENCE_DELIMITERS)
        
        return parts
            .map { it.trim() }
            .filter { it.length >= MIN_SENTENCE_LENGTH }
            .toList()
    }
    
    /**
     * Split text and preserve delimiters with sentences
     * @param text The text to split
     * @return List of sentences with their original delimiters
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
        
        // Add remaining text if any
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd).trim()
            if (remaining.length >= MIN_SENTENCE_LENGTH) {
                sentences.add(remaining)
            }
        }
        
        return sentences
    }
}
