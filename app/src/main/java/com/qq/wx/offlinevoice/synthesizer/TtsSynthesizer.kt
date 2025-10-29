package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.util.Log
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 文本转语音合成器 - 支持高级播放控制
 * 
 * 主要功能：
 * - 自动分句和顺序播放
 * - 暂停/恢复/停止控制，支持精确位置跟踪
 * - 基于回调的播放完成机制（无Thread.sleep循环）
 * - 预合成下一句以优化性能，消除低性能设备上的停顿
 * - 支持动态修改语速和发音人
 * - 简化的状态管理（IDLE、PLAYING、PAUSED）
 * 
 * 使用示例：
 * ```
 * val tts = TtsSynthesizer(context, "pb")
 * tts.initialize()
 * tts.setSpeed(1.5f)  // 设置1.5倍速
 * tts.speak("这是要朗读的文本")
 * tts.pause()  // 暂停
 * tts.resume()  // 从暂停位置继续
 * ```
 */
class TtsSynthesizer(
    context: Context,
    private val voiceName: String
) {
    
    private val voiceCode: String = voiceName
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)
    
    // 状态管理 - 简化为IDLE、PLAYING、PAUSED三种状态
    @Volatile
    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE
    
    // 播放队列和位置
    private val sentences = mutableListOf<String>()
    private var currentSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f  // 改为倍数格式: 1.0 = 正常速度
    private var currentVolume: Float = 1.0f
    private var currentVoice: String = voiceName
    private var currentCallback: TtsCallback? = null
    
    // 存储当前句子的PCM数据用于播放
    private var currentSentencePcm: ShortArray? = null
    
    // 预合成下一句的PCM数据以优化性能(需求2)
    private var nextSentencePcm: ShortArray? = null
    
    // 线程管理
    private var synthesisThread: Thread? = null
    private val stateLock = ReentrantLock()
    
    // 序列化原生引擎访问以防止并发崩溃
    private val synthesisLock = ReentrantLock()
    
    @Volatile
    private var shouldStop = false
    
    // 组件
    private val audioPlayer: AudioPlayer = AudioPlayer(TtsConstants.DEFAULT_SAMPLE_RATE)
    private val pcmProcessor: PcmProcessor = PcmProcessor()
    
    companion object {
        private const val TAG = "TtsSynthesizer"
        
        private val instanceCount = AtomicInteger(0)
        
        @Volatile
        private var nativeEngine: SynthesizerNative? = null
        
        @Volatile
        private var currentVoiceCode: String? = null
        
        init {
            // 加载原生库
            try {
                System.loadLibrary("hwTTS")
                System.loadLibrary("weread-tts")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "加载原生库失败", e)
            }
        }
    }
    
    init {
        val pathBuilder = StringBuilder()
        PathUtils.appendExternalVoicePath(
            byteArrayOf(68, 111, 42, 100, -19),
            byteArrayOf(50, 0, 67, 7, -120, 65, 34, 26),
            context,
            pathBuilder
        )
        voiceDataPath = PathUtils.appendDecodedString(
            byteArrayOf(-105, 16, 22, -80, -70, 86, 114),
            byteArrayOf(-72, 103, 115, -62, -33, 55, 22, -27),
            pathBuilder
        )
    }
    
    /**
     * 初始化TTS引擎
     */
    fun initialize() {
        stateLock.withLock {
            try {
                if (instanceCount.incrementAndGet() == 1) {
                    nativeEngine = SynthesizerNative()
                    nativeEngine?.init(voiceDataPath.toByteArray())
                    Log.d(TAG, "原生TTS引擎初始化完成，路径: $voiceDataPath")
                }
                
                currentState = TtsPlaybackState.IDLE
                currentCallback?.onInitialized(true)
                Log.d(TAG, "TTS引擎初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "TTS引擎初始化失败", e)
                currentCallback?.onInitialized(false)
                currentCallback?.onError("初始化失败: ${e.message}")
            }
        }
    }

    /**
     * 设置事件回调
     * @param callback 回调接口实现
     */
    fun setCallback(callback: TtsCallback?) {
        currentCallback = callback
    }

    /**
     * 设置语速(倍数格式)
     * @param speed 语速倍数，1.0 = 正常速度，0.5 = 半速，3.0 = 3倍速(最大值)
     * 如果在播放过程中修改，会从当前句从头开始继续读
     */
    fun setSpeed(speed: Float) {
        val newSpeed = speed.coerceIn(0.5f, 3.0f)  // 限制在0.5到3倍之间
        
        stateLock.withLock {
            if (currentSpeed != newSpeed) {
                currentSpeed = newSpeed
                Log.d(TAG, "语速设置为: ${newSpeed}x")
                
                // 如果正在播放，重新合成并播放当前句
                if (currentState == TtsPlaybackState.PLAYING) {
                    Log.d(TAG, "播放中修改语速，将从当前句重新开始")
                    restartCurrentSentence()
                }
            }
        }
    }
    
    /**
     * 设置音量
     * @param volume 音量级别 (0.0 到 1.0)
     */
    fun setVolume(volume: Float) {
        val newVolume = volume.coerceIn(0.0f, 1.0f)
        
        stateLock.withLock {
            if (currentVolume != newVolume) {
                currentVolume = newVolume
                Log.d(TAG, "音量设置为: $newVolume")
                
                // 音量可以动态调整，不需要重启当前句
            }
        }
    }
    
    /**
     * 设置发音人
     * @param voiceName 发音人名称
     * 如果在播放过程中修改，会从当前句从头开始继续读
     */
    fun setVoice(voiceName: String) {
        stateLock.withLock {
            if (currentVoice != voiceName) {
                currentVoice = voiceName
                Log.d(TAG, "发音人设置为: $voiceName")
                
                // 如果正在播放，重新合成并播放当前句
                if (currentState == TtsPlaybackState.PLAYING) {
                    Log.d(TAG, "播放中修改发音人，将从当前句重新开始")
                    restartCurrentSentence()
                }
            }
        }
    }
    
    /**
     * 重新开始播放当前句（用于动态修改参数后）
     */
    private fun restartCurrentSentence() {
        if (currentSentenceIndex < sentences.size) {
            // 停止当前播放
            audioPlayer.stopAndRelease()
            
            // 清空预合成的下一句
            nextSentencePcm = null
            
            // 在新线程中重新合成并播放当前句
            Thread {
                val sentence = sentences[currentSentenceIndex]
                currentCallback?.onSentenceStart(
                    currentSentenceIndex,
                    sentence,
                    sentences.size
                )
                val pcm = synthesizeSentence(sentence)
                if (pcm != null) {
                    currentSentencePcm = pcm
                    playCurrentSentence()
                }
            }.start()
        }
    }
    
    /**
     * 播放文本
     * @param text 要播放的文本，会自动分句
     */
    fun speak(text: String) {
        stateLock.withLock {
            // 立即停止当前播放并清除之前的数据，然后播放新数据
            if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
                Log.d(TAG, "停止当前播放，准备播放新内容")
                stopInternal()
            }
            
            // 将文本分句
            sentences.clear()
            sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
            
            if (sentences.isEmpty()) {
                Log.w(TAG, "没有可播放的句子")
                currentCallback?.onError("文本中没有有效的句子")
                return
            }
            
            Log.d(TAG, "文本分为 ${sentences.size} 句")
            
            // 初始化播放状态
            currentSentenceIndex = 0
            currentSentencePcm = null
            nextSentencePcm = null
            shouldStop = false
            
            // 启动合成线程
            synthesisThread = Thread({
                executeSpeech()
            }, "TtsSynthesisThread")
            synthesisThread?.start()
        }
    }
    
    /**
     * 暂停播放
     * 会保存当前播放位置，恢复时从此位置继续
     */
    fun pause() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PLAYING) {
                Log.w(TAG, "无法暂停: 当前未在播放")
                return
            }
            
            // 暂停音频播放器，不清除数据，AudioPlayer会记住位置
            audioPlayer.pause()
            updateState(TtsPlaybackState.PAUSED)
            currentCallback?.onPaused()
            Log.d(TAG, "播放已暂停，句子 $currentSentenceIndex")
        }
    }
    
    /**
     * 恢复播放
     * 从暂停的精确位置继续播放
     */
    fun resume() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PAUSED) {
                Log.w(TAG, "无法恢复: 当前未暂停")
                return
            }
            
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onResumed()
            Log.d(TAG, "播放已恢复，句子 $currentSentenceIndex")
            
            // 恢复音频播放器，AudioPlayer会从暂停位置继续
            audioPlayer.resume()
        }
    }
    
    /**
     * 停止播放
     * 停止当前播放，清空队列
     */
    fun stop() {
        stateLock.withLock {
            stopInternal()
        }
    }
    
    /**
     * 内部停止方法（必须在stateLock内调用）
     */
    private fun stopInternal() {
        if (currentState == TtsPlaybackState.IDLE) {
            return
        }
        
        Log.d(TAG, "停止播放")
        shouldStop = true
        audioPlayer.stopAndRelease()
        
        // 重置原生引擎
        if (voiceCode == currentVoiceCode) {
            nativeEngine?.reset()
        }
        
        // 等待合成线程结束
        synthesisThread?.let { thread ->
            if (thread.isAlive && Thread.currentThread() != thread) {
                try {
                    thread.join(2000) // 最多等待2秒
                } catch (e: InterruptedException) {
                    Log.w(TAG, "等待合成线程时被中断")
                }
            }
        }
        synthesisThread = null
        
        sentences.clear()
        currentSentenceIndex = 0
        currentSentencePcm = null
        nextSentencePcm = null
        updateState(TtsPlaybackState.IDLE)
    }
    
    /**
     * 获取当前播放状态
     * @return TTS状态对象，包含当前状态、句子索引等信息
     */
    fun getStatus(): TtsStatus {
        stateLock.withLock {
            val currentSentence = if (currentSentenceIndex < sentences.size) {
                sentences[currentSentenceIndex]
            } else {
                ""
            }
            
            return TtsStatus(
                state = currentState,
                totalSentences = sentences.size,
                currentSentenceIndex = currentSentenceIndex,
                currentSentence = currentSentence
            )
        }
    }
    
    /**
     * 检查是否正在播放
     * @return 如果正在播放返回true
     */
    fun isSpeaking(): Boolean {
        return currentState == TtsPlaybackState.PLAYING
    }
    
    /**
     * 主要语音执行方法 - 处理队列中的所有句子
     * 使用基于回调的方法而不是Thread.sleep循环
     */
    private fun executeSpeech() {
        try {
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onSynthesisStart()
            
            // 处理每个句子
            processNextSentence()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during speech execution", e)
            currentCallback?.onError("Speech execution error: ${e.message}")
            stateLock.withLock {
                updateState(TtsPlaybackState.IDLE)
            }
        }
    }
    
    /**
     * 递归处理下一句，使用回调方式
     */
    private fun processNextSentence() {
        stateLock.withLock {
            if (shouldStop || currentSentenceIndex >= sentences.size) {
                // 全部完成
                if (currentSentenceIndex >= sentences.size && !shouldStop) {
                    Log.d(TAG, "所有句子播放完成")
                    currentCallback?.onSynthesisComplete()
                }
                updateState(TtsPlaybackState.IDLE)
                return
            }
            
            if (currentState == TtsPlaybackState.PAUSED) {
                // 已暂停，不继续
                return
            }
        }
        
        val sentence = sentences[currentSentenceIndex]
        Log.d(TAG, "处理句子 $currentSentenceIndex: $sentence")
        
        // 通知句子开始
        currentCallback?.onSentenceStart(
            currentSentenceIndex,
            sentence,
            sentences.size
        )
        
        // 优化：检查是否已有预合成的PCM数据 (需求2 - 性能优化)
        val pcmToPlay = if (nextSentencePcm != null) {
            Log.d(TAG, "使用预合成的PCM数据，无需等待合成")
            val cached = nextSentencePcm
            nextSentencePcm = null
            cached
        } else {
            // 合成当前句子
            synthesizeSentence(sentence)
        }
        
        if (pcmToPlay == null || shouldStop) {
            stateLock.withLock {
                updateState(TtsPlaybackState.IDLE)
            }
            return
        }
        
        // 保存当前句子的PCM数据
        currentSentencePcm = pcmToPlay
        
        // 同步预合成下一句以提升性能，但避免并发访问原生引擎 (需求2)
        // 在播放当前句子之前就合成下一句，这样播放时下一句已经准备好
        if (currentSentenceIndex + 1 < sentences.size) {
            preSynthesizeNextSentence()
        }
        
        // 播放当前句子
        playCurrentSentence()
    }
    
    /**
     * 预合成下一句以优化性能 (需求2 - 解决低性能手机上的停顿)
     * 使用同步方式避免并发访问原生引擎导致的崩溃
     */
    private fun preSynthesizeNextSentence() {
        val nextIndex = currentSentenceIndex + 1
        if (nextIndex >= sentences.size) {
            return
        }
        
        val nextSentence = sentences[nextIndex]
        try {
            Log.d(TAG, "开始预合成下一句: $nextSentence")
            // 同步合成下一句，使用synthesisLock确保串行访问
            nextSentencePcm = synthesizeSentence(nextSentence)
            Log.d(TAG, "下一句预合成完成")
        } catch (e: Exception) {
            Log.w(TAG, "预合成失败: ${e.message}")
            nextSentencePcm = null
        }
    }
    
    /**
     * 播放当前句子的PCM数据
     */
    private fun playCurrentSentence() {
        val pcm = currentSentencePcm ?: return
        
        // 计算音量 (0.0 到 1.0 范围)
        val normalizedVolume = currentVolume.coerceIn(0.0f, 1.0f)
        
        // 播放，使用完成回调
        audioPlayer.play(pcm, normalizedVolume) {
            // 播放完成回调
            stateLock.withLock {
                if (shouldStop || currentState != TtsPlaybackState.PLAYING) {
                    return@withLock
                }
                
                // 移动到下一句
                moveToNextSentence()
            }
        }
    }
    
    /**
     * 移动到下一句并开始处理
     */
    private fun moveToNextSentence() {
        val completedSentence = sentences[currentSentenceIndex]
        currentCallback?.onSentenceComplete(currentSentenceIndex, completedSentence)
        
        currentSentenceIndex++
        currentSentencePcm = null
        
        // 处理下一句（基于回调的递归）
        processNextSentence()
    }
    
    /**
     * Synthesize a sentence and play all its PCM chunks
     */
    /**
     * 合成一个句子并返回PCM数据
     * @param sentence 要合成的句子
     * @return 合成的PCM数据，失败时返回null
     */
    private fun synthesizeSentence(sentence: String): ShortArray? {
        // 使用锁确保对原生引擎的串行访问，防止并发崩溃
        return synthesisLock.withLock {
            try {
                // 准备合成
                val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
                if (prepareResult != 0) {
                    Log.e(TAG, "准备合成失败，错误码: $prepareResult")
                    currentCallback?.onError("准备句子失败: $sentence")
                    return null
                }
                
                // Initialize PcmProcessor with current speed
                pcmProcessor.initialize(speed = currentSpeed)
                val pcmChunks = mutableListOf<ShortArray>()
            
            val synthResult = IntArray(1)
            val pcmArray = pcmBuffer.array()
            
            // 合成循环 - 收集此句子的所有PCM块
            while (!shouldStop) {
                if (currentState == TtsPlaybackState.PAUSED) {
                    // 暂停时停止合成但保留已合成的数据
                    break
                }
                
                val synthesisStatus = nativeEngine?.synthesize(
                    pcmArray,
                    TtsConstants.PCM_BUFFER_SIZE,
                    synthResult,
                    1
                ) ?: -1
                
                if (synthesisStatus == -1) {
                    Log.e(TAG, "合成失败")
                    nativeEngine?.reset()
                    return null
                }
                
                val numSamples = synthResult[0]
                if (numSamples <= 0) {
                    break // 句子合成完成
                }
                
                // 提取有效的PCM数据
                val validSamples = minOf(pcmArray.size, numSamples)
                if (validSamples <= 0) {
                    Log.w(TAG, "无效的PCM样本数: $validSamples")
                    break
                }
                
                val validPcm = pcmArray.copyOf(validSamples)
                
                // 处理PCM（音高变换、速度变化）
                val processedPcm = pcmProcessor.process(validPcm)
                
                // Only add if processing was successful
                if (processedPcm.isNotEmpty()) {
                    pcmChunks.add(processedPcm)
                }
            }
            
            // 刷新剩余数据
            val flushedPcm = pcmProcessor.flush()
            if (flushedPcm.isNotEmpty()) {
                pcmChunks.add(flushedPcm)
            }
            
            if (shouldStop || pcmChunks.isEmpty()) {
                return null
            }
            
            // 将所有PCM块合并为单个连续缓冲区以避免播放卡顿
            val mergedPcm = mergePcmChunks(pcmChunks)
            Log.v(TAG, "句子合成完成，PCM大小: ${mergedPcm.size}")
            return mergedPcm
            
            } catch (e: Exception) {
                Log.e(TAG, "合成句子时出错", e)
                currentCallback?.onError("合成错误: ${e.message}")
                return null
            } finally {
                nativeEngine?.reset()
            }
        }
    }
    
    /**
     * 准备原生引擎进行合成
     */
    private fun prepareForSynthesis(text: String, speed: Float, volume: Float): Int {
        synchronized(this) {
            // 如果发音人与当前不同，则设置新的发音人
            if (currentVoice != currentVoiceCode) {
                currentVoiceCode = currentVoice
                nativeEngine?.setVoiceName(currentVoice)
            }
            
            // 设置合成参数
            nativeEngine?.setSpeed(speed)
            nativeEngine?.setVolume(volume)
            
            // 准备文本，带重试逻辑
            var prepareResult = -1
            for (attempt in 0 until TtsConstants.MAX_PREPARE_RETRIES) {
                prepareResult = nativeEngine?.prepareUTF8(text.toByteArray()) ?: -1
                if (prepareResult == 0) {
                    break
                }
                nativeEngine?.setVoiceName(currentVoice)
            }
            
            return prepareResult
        }
    }
    
    /**
     * 将多个PCM块合并为单个连续缓冲区
     * 这可以防止块之间的卡顿
     */
    private fun mergePcmChunks(chunks: List<ShortArray>): ShortArray {
        if (chunks.isEmpty()) {
            return ShortArray(0)
        }
        
        if (chunks.size == 1) {
            return chunks[0]
        }
        
        // 计算总大小
        val totalSize = chunks.sumOf { it.size }
        val merged = ShortArray(totalSize)
        
        // 将所有块复制到合并缓冲区
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }
        
        // 使用详细日志以避免在生产环境中产生日志垃圾
        Log.v(TAG, "合并了 ${chunks.size} 个PCM块，总大小: $totalSize")
        return merged
    }
    
    /**
     * 更新状态并通知回调
     */
    private fun updateState(newState: TtsPlaybackState) {
        if (currentState != newState) {
            currentState = newState
            currentCallback?.onStateChanged(newState)
            Log.d(TAG, "状态变更为: $newState")
        }
    }
    
    /**
     * 释放TTS引擎资源
     * 应在Activity的onDestroy中调用
     */
    fun release() {
        stateLock.withLock {
            Log.d(TAG, "释放TTS引擎")
            stopInternal()
            
            if (instanceCount.decrementAndGet() == 0) {
                nativeEngine?.destroy()
                nativeEngine = null
                currentVoiceCode = null
            }
            
            audioPlayer.stopAndRelease()
            pcmProcessor.release()
            currentState = TtsPlaybackState.IDLE
        }
    }

}
