package com.qq.wx.offlinevoice.synthesizer

/**
 * TTS合成事件回调接口
 */
interface TtsCallback {
    /**
     * TTS引擎初始化完成时调用
     * @param success 如果初始化成功则为true
     */
    fun onInitialized(success: Boolean) {}
    
    /**
     * 整体TTS合成开始时调用（针对所有句子）
     */
    fun onSynthesisStart() {}
    
    /**
     * 特定句子开始朗读时调用
     * @param sentenceIndex 句子的索引（从0开始）
     * @param sentence 句子的文本
     * @param totalSentences 句子总数
     */
    fun onSentenceStart(sentenceIndex: Int, sentence: String, totalSentences: Int) {}
    
    /**
     * 特定句子完成朗读时调用
     * @param sentenceIndex 句子的索引（从0开始）
     * @param sentence 句子的文本
     */
    fun onSentenceComplete(sentenceIndex: Int, sentence: String) {}
    
    /**
     * 播放状态变化时调用
     * @param newState 新的播放状态
     */
    fun onStateChanged(newState: TtsPlaybackState) {}
    
    /**
     * PCM数据可用于播放时调用
     * @param pcmData 合成的PCM音频数据
     * @param length 有效PCM数据的长度
     */
    fun onPcmDataAvailable(pcmData: ByteArray, length: Int) {}
    
    /**
     * 所有句子都已成功朗读时调用
     */
    fun onSynthesisComplete() {}
    
    /**
     * 播放暂停时调用
     */
    fun onPaused() {}
    
    /**
     * 播放恢复时调用
     */
    fun onResumed() {}
    
    /**
     * 合成过程中发生错误时调用
     * @param errorMessage 错误描述
     */
    fun onError(errorMessage: String) {}
}
