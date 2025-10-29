package com.hurist.ttsdemo

import com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState
import com.qq.wx.offlinevoice.synthesizer.TtsStatus
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for TTS data classes and enums
 */
class TtsDataClassesTest {
    
    @Test
    fun testPlaybackStateEnumValues() {
        // Verify all expected states exist (simplified to 3 states)
        val states = TtsPlaybackState.values()
        
        assertEquals(3, states.size)
        assertTrue(states.contains(TtsPlaybackState.IDLE))
        assertTrue(states.contains(TtsPlaybackState.PLAYING))
        assertTrue(states.contains(TtsPlaybackState.PAUSED))
    }
    
    @Test
    fun testTtsStatusDefaultValues() {
        val status = TtsStatus()
        
        assertEquals(TtsPlaybackState.IDLE, status.state)
        assertEquals(0, status.totalSentences)
        assertEquals(0, status.currentSentenceIndex)
        assertEquals("", status.currentSentence)
    }
    
    @Test
    fun testTtsStatusWithValues() {
        val status = TtsStatus(
            state = TtsPlaybackState.PLAYING,
            totalSentences = 5,
            currentSentenceIndex = 2,
            currentSentence = "这是第三句"
        )
        
        assertEquals(TtsPlaybackState.PLAYING, status.state)
        assertEquals(5, status.totalSentences)
        assertEquals(2, status.currentSentenceIndex)
        assertEquals("这是第三句", status.currentSentence)
    }
    
    @Test
    fun testTtsStatusCopy() {
        val original = TtsStatus(
            state = TtsPlaybackState.PLAYING,
            totalSentences = 3,
            currentSentenceIndex = 1
        )
        
        val copy = original.copy(currentSentenceIndex = 2)
        
        assertEquals(TtsPlaybackState.PLAYING, copy.state)
        assertEquals(3, copy.totalSentences)
        assertEquals(2, copy.currentSentenceIndex)
    }
    
    @Test
    fun testTtsStatusEquality() {
        val status1 = TtsStatus(
            state = TtsPlaybackState.PLAYING,
            totalSentences = 5,
            currentSentenceIndex = 2
        )
        
        val status2 = TtsStatus(
            state = TtsPlaybackState.PLAYING,
            totalSentences = 5,
            currentSentenceIndex = 2
        )
        
        assertEquals(status1, status2)
    }
    
    @Test
    fun testPlaybackProgress() {
        val status = TtsStatus(
            totalSentences = 10,
            currentSentenceIndex = 5
        )
        
        // Calculate progress percentage
        val progress = (status.currentSentenceIndex.toFloat() / status.totalSentences * 100).toInt()
        assertEquals(50, progress)
    }
    
    @Test
    fun testStateTransitions() {
        // Test valid state transitions
        var status = TtsStatus(state = TtsPlaybackState.IDLE)
        assertEquals(TtsPlaybackState.IDLE, status.state)
        
        status = status.copy(state = TtsPlaybackState.PLAYING)
        assertEquals(TtsPlaybackState.PLAYING, status.state)
        
        status = status.copy(state = TtsPlaybackState.PAUSED)
        assertEquals(TtsPlaybackState.PAUSED, status.state)
        
        status = status.copy(state = TtsPlaybackState.PLAYING)
        assertEquals(TtsPlaybackState.PLAYING, status.state)
        
        status = status.copy(state = TtsPlaybackState.IDLE)
        assertEquals(TtsPlaybackState.IDLE, status.state)
    }
}
