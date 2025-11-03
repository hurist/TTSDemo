package com.hurist.ttsdemo

import com.qq.wx.offlinevoice.synthesizer.SentenceSplitter1
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for SentenceSplitter
 */
class SentenceSplitterTest {
    
    @Test
    fun testSplitSimpleChinese() {
        val text = "这是第一句。这是第二句！这是第三句？"
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        
        assertEquals(3, sentences.size)
        assertEquals("这是第一句", sentences[0])
        assertEquals("这是第二句", sentences[1])
        assertEquals("这是第三句", sentences[2])
    }
    
    @Test
    fun testSplitWithDelimiters() {
        val text = "第一句。第二句！第三句？"
        val sentences = SentenceSplitter1.splitWithDelimiters(text)
        
        assertEquals(3, sentences.size)
        assertEquals("第一句。", sentences[0])
        assertEquals("第二句！", sentences[1])
        assertEquals("第三句？", sentences[2])
    }
    
    @Test
    fun testSplitEnglish() {
        val text = "This is first. This is second! This is third?"
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        
        assertEquals(3, sentences.size)
        assertTrue(sentences[0].contains("first"))
        assertTrue(sentences[1].contains("second"))
        assertTrue(sentences[2].contains("third"))
    }
    
    @Test
    fun testSplitMultipleDelimiters() {
        val text = "句子一。。句子二！！！句子三？？"
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        
        assertEquals(3, sentences.size)
    }
    
    @Test
    fun testSplitWithSemicolon() {
        val text = "部分一；部分二；部分三"
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        
        assertEquals(3, sentences.size)
    }
    
    @Test
    fun testEmptyText() {
        val sentences = SentenceSplitter1.splitIntoSentences("")
        assertTrue(sentences.isEmpty())
    }
    
    @Test
    fun testWhitespaceOnly() {
        val sentences = SentenceSplitter1.splitIntoSentences("   ")
        assertTrue(sentences.isEmpty())
    }
    
    @Test
    fun testNoDelimiters() {
        val text = "这是一段没有分隔符的文本"
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        
        // Should return the whole text as one sentence (or empty if no delimiters)
        // Depending on implementation
    }
    
    @Test
    fun testMixedLanguage() {
        val text = "Hello world. 你好世界！How are you? 你好吗？"
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        
        assertTrue(sentences.size >= 2)
    }
    
    @Test
    fun testLongText() {
        val text = """
            这是第一段文字。包含多个句子！有问题吗？
            这是第二段。也有多个句子！对吧？
        """.trimIndent()
        
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        assertTrue(sentences.size >= 4)
    }
    
    @Test
    fun testMinimumLength() {
        // Test that very short fragments are filtered out
        val text = "长句子。a.另一个长句子"
        val sentences = SentenceSplitter1.splitIntoSentences(text)
        
        // Should not include single character "a"
        assertTrue(sentences.all { it.length >= 2 })
    }
}
