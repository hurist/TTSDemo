package com.qq.wx.offlinevoice.synthesizer

/**
 * TTS引擎的播放状态
 */
enum class TtsPlaybackState {
    /** TTS引擎空闲（未初始化或已停止） */
    IDLE,
    
    /** TTS正在合成和播放 */
    PLAYING,
    
    /** TTS播放已暂停 */
    PAUSED
}
