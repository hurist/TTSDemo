package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.util.Log
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Text-to-Speech synthesizer with advanced playback control.
 * 
 * Features:
 * - Automatic sentence splitting and sequential playback
 * - Pause/resume/stop controls with proper position tracking
 * - Callback-based playback completion (no Thread.sleep loops)
 * - Simplified state management
 */
class TtsSynthesizer(
    context: Context,
    private val voiceName: String
) {
    
    private val voiceCode: String = voiceName
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)
    
    // State management - simplified to IDLE, PLAYING, PAUSED
    @Volatile
    private var currentState: TtsPlaybackState = TtsPlaybackState.IDLE
    
    // Playback queue and position
    private val sentences = mutableListOf<String>()
    private var currentSentenceIndex: Int = 0
    private var currentSpeed: Float = 1f
    private var currentVolume: Float = 1f
    private var currentCallback: TtsCallback? = null
    
    // Store current sentence PCM data for pause/resume at same position
    private var currentSentencePcm: MutableList<ShortArray> = mutableListOf()
    private var currentPcmChunkIndex: Int = 0
    
    // Thread management
    private var synthesisThread: Thread? = null
    private val stateLock = ReentrantLock()
    
    @Volatile
    private var shouldStop = false
    
    // Components
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
            // Load native libraries
            try {
                System.loadLibrary("hwTTS")
                System.loadLibrary("weread-tts")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native libraries", e)
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
    
    fun initialize() {
        stateLock.withLock {
            try {
                if (instanceCount.incrementAndGet() == 1) {
                    nativeEngine = SynthesizerNative()
                    nativeEngine?.init(voiceDataPath.toByteArray())
                    Log.d(TAG, "Native TTS engine initialized with path: $voiceDataPath")
                }
                
                currentState = TtsPlaybackState.IDLE
                currentCallback?.onInitialized(true)
                Log.d(TAG, "TTS engine initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TTS engine", e)
                currentCallback?.onInitialized(false)
                currentCallback?.onError("Initialization failed: ${e.message}")
            }
        }
    }

    fun setCallback(callback: TtsCallback?) {
        currentCallback = callback
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        Log.d(TAG, "Speech speed set to $speed")
    }

    fun setVolume(volume: Float) {
        currentVolume = volume
        Log.d(TAG, "Speech volume set to $volume")
    }
    
    fun speak(text: String) {
        stateLock.withLock {
            // Requirement 4: Immediately stop playback and clear previous data before playing new data
            if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
                Log.d(TAG, "Stopping current playback before starting new")
                stopInternal()
            }
            
            // Split text into sentences
            sentences.clear()
            sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
            
            if (sentences.isEmpty()) {
                Log.w(TAG, "No sentences to speak")
                currentCallback?.onError("No valid sentences in text")
                return
            }
            
            Log.d(TAG, "Split text into ${sentences.size} sentences")
            
            // Initialize playback state
            currentSentenceIndex = 0
            currentSentencePcm.clear()
            currentPcmChunkIndex = 0
            shouldStop = false
            
            // Start synthesis thread
            synthesisThread = Thread({
                executeSpeech()
            }, "TtsSynthesisThread")
            synthesisThread?.start()
        }
    }
    
    fun pause() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PLAYING) {
                Log.w(TAG, "Cannot pause: not playing")
                return
            }
            
            // Pause audio player without clearing data
            audioPlayer.pause()
            updateState(TtsPlaybackState.PAUSED)
            currentCallback?.onPaused()
            Log.d(TAG, "Playback paused at sentence $currentSentenceIndex, chunk $currentPcmChunkIndex")
        }
    }
    
    fun resume() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PAUSED) {
                Log.w(TAG, "Cannot resume: not paused")
                return
            }
            
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onResumed()
            Log.d(TAG, "Playback resumed from sentence $currentSentenceIndex, chunk $currentPcmChunkIndex")
            
            // Resume audio player or continue from paused position
            audioPlayer.resume()
            
            // If audio player was stopped, restart from current chunk
            Thread {
                continuePlayback()
            }.start()
        }
    }
    
    fun stop() {
        stateLock.withLock {
            stopInternal()
        }
    }
    
    /**
     * Internal stop method (must be called within stateLock)
     */
    private fun stopInternal() {
        if (currentState == TtsPlaybackState.IDLE) {
            return
        }
        
        Log.d(TAG, "Stopping playback")
        shouldStop = true
        audioPlayer.stopAndRelease()
        
        // Reset native engine
        if (voiceCode == currentVoiceCode) {
            nativeEngine?.reset()
        }
        
        // Wait for synthesis thread to finish
        synthesisThread?.let { thread ->
            if (thread.isAlive && Thread.currentThread() != thread) {
                try {
                    thread.join(2000) // Wait up to 2 seconds
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Interrupted while waiting for synthesis thread")
                }
            }
        }
        synthesisThread = null
        
        sentences.clear()
        currentSentenceIndex = 0
        currentSentencePcm.clear()
        currentPcmChunkIndex = 0
        updateState(TtsPlaybackState.IDLE)
    }
    
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
    
    fun isSpeaking(): Boolean {
        return currentState == TtsPlaybackState.PLAYING
    }
    
    /**
     * Main speech execution method - processes all sentences in queue
     * Uses callback-based approach instead of Thread.sleep loops
     */
    private fun executeSpeech() {
        try {
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onSynthesisStart()
            
            // Process each sentence
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
     * Process next sentence recursively using callbacks (Requirement 1)
     */
    private fun processNextSentence() {
        stateLock.withLock {
            if (shouldStop || currentSentenceIndex >= sentences.size) {
                // All done
                if (currentSentenceIndex >= sentences.size && !shouldStop) {
                    Log.d(TAG, "All sentences completed")
                    currentCallback?.onSynthesisComplete()
                }
                updateState(TtsPlaybackState.IDLE)
                return
            }
            
            if (currentState == TtsPlaybackState.PAUSED) {
                // Paused, don't continue
                return
            }
        }
        
        val sentence = sentences[currentSentenceIndex]
        Log.d(TAG, "Processing sentence $currentSentenceIndex: $sentence")
        
        // Notify sentence start
        currentCallback?.onSentenceStart(
            currentSentenceIndex,
            sentence,
            sentences.size
        )
        
        // Synthesize this sentence and collect all PCM chunks
        val success = synthesizeSentenceAndPlay(sentence)
        
        if (!success || shouldStop) {
            stateLock.withLock {
                updateState(TtsPlaybackState.IDLE)
            }
            return
        }
        
        // Sentence complete callback will be called after playback finishes
    }
    
    /**
     * Continue playback from paused position (Requirement 2)
     */
    private fun continuePlayback() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PLAYING) {
                return
            }
            
            // Resume from current chunk in current sentence
            if (currentPcmChunkIndex < currentSentencePcm.size) {
                playPcmChunksFromIndex(currentPcmChunkIndex)
            } else {
                // Current sentence done, move to next
                moveToNextSentence()
            }
        }
    }
    
    /**
     * Play PCM chunks starting from a specific index (for resume)
     */
    private fun playPcmChunksFromIndex(startIndex: Int) {
        if (startIndex >= currentSentencePcm.size) {
            moveToNextSentence()
            return
        }
        
        val pcmData = currentSentencePcm[startIndex]
        currentPcmChunkIndex = startIndex
        
        // Calculate volume (0.0 to 1.0 range for AudioTrack)
        val normalizedVolume = (currentVolume).coerceIn(0.0f, 1.0f)
        
        // Play with completion callback (Requirement 1: callback-based instead of Thread.sleep)
        // Issue 1 fix: Pass volume parameter to audioPlayer
        audioPlayer.play(pcmData, normalizedVolume) {
            // Playback completed callback
            stateLock.withLock {
                if (shouldStop || currentState != TtsPlaybackState.PLAYING) {
                    return@withLock
                }
                
                currentPcmChunkIndex++
                
                if (currentPcmChunkIndex < currentSentencePcm.size) {
                    // More chunks in this sentence
                    //Thread.sleep(1000)
                    playPcmChunksFromIndex(currentPcmChunkIndex)
                    Log.d(TAG, "Playing next PCM chunk $currentPcmChunkIndex of sentence $currentSentenceIndex")
                } else {
                    // Sentence complete
                    moveToNextSentence()
                }
            }
        }
    }
    
    /**
     * Move to next sentence and start processing
     */
    private fun moveToNextSentence() {
        val completedSentence = sentences[currentSentenceIndex]
        currentCallback?.onSentenceComplete(currentSentenceIndex, completedSentence)
        
        currentSentenceIndex++
        currentSentencePcm.clear()
        currentPcmChunkIndex = 0
        
        // Process next sentence (callback-based recursion)
        processNextSentence()
    }
    
    /**
     * Synthesize a sentence and play all its PCM chunks
     */
    private fun synthesizeSentenceAndPlay(sentence: String): Boolean {
        try {
            // Prepare synthesis
            val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
            if (prepareResult != 0) {
                Log.e(TAG, "Failed to prepare synthesis, error code: $prepareResult")
                currentCallback?.onError("Failed to prepare sentence: $sentence")
                return false
            }
            
            pcmProcessor.initialize()
            currentSentencePcm.clear()
            currentPcmChunkIndex = 0
            
            val synthResult = IntArray(1)
            val pcmArray = pcmBuffer.array()
            
            // Synthesis loop - collect all PCM chunks for this sentence
            while (!shouldStop) {
                if (currentState == TtsPlaybackState.PAUSED) {
                    // Paused during synthesis, stop synthesizing but keep what we have
                    return true
                }
                
                val synthesisStatus = nativeEngine?.synthesize(
                    pcmArray,
                    TtsConstants.PCM_BUFFER_SIZE,
                    synthResult,
                    1
                ) ?: -1
                
                if (synthesisStatus == -1) {
                    Log.e(TAG, "Synthesis failed")
                    nativeEngine?.reset()
                    return false
                }
                
                val numSamples = synthResult[0]
                if (numSamples <= 0) {
                    break // Sentence synthesis complete
                }
                
                // Extract valid PCM data
                val validSamples = minOf(pcmArray.size, numSamples)
                val validPcm = pcmArray.copyOf(validSamples)
                
                // Process PCM (pitch shift, speed change)
                val processedPcm = pcmProcessor.process(validPcm)
                
                // Store PCM chunk for this sentence (for pause/resume support)
                currentSentencePcm.add(processedPcm)
            }
            
            // Flush remaining data
            val flushedPcm = pcmProcessor.flush()
            if (flushedPcm.isNotEmpty()) {
                currentSentencePcm.add(flushedPcm)
            }
            
            if (shouldStop) {
                return false
            }
            
            // Issue 2 fix: Merge all PCM chunks into a single continuous buffer
            // to avoid stuttering between chunks
            if (currentSentencePcm.isNotEmpty()) {
                val mergedPcm = mergePcmChunks(currentSentencePcm)
                currentSentencePcm.clear()
                currentSentencePcm.add(mergedPcm)
                playPcmChunksFromIndex(0)
            } else {
                // No PCM data, move to next sentence
                moveToNextSentence()
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error synthesizing sentence", e)
            currentCallback?.onError("Synthesis error: ${e.message}")
            return false
        } finally {
            nativeEngine?.reset()
        }
    }
    
    /**
     * Prepare the native engine for synthesis
     */
    private fun prepareForSynthesis(text: String, speed: Float, volume: Float): Int {
        synchronized(this) {
            // Set voice if different from current
            if (voiceCode != currentVoiceCode) {
                currentVoiceCode = voiceCode
                nativeEngine?.setVoiceName(voiceCode)
            }
            
            // Set synthesis parameters
            nativeEngine?.setSpeed(speed)
            nativeEngine?.setVolume(volume)
            
            // Prepare text with retry logic
            var prepareResult = -1
            for (attempt in 0 until TtsConstants.MAX_PREPARE_RETRIES) {
                prepareResult = nativeEngine?.prepareUTF8(text.toByteArray()) ?: -1
                if (prepareResult == 0) {
                    break
                }
                nativeEngine?.setVoiceName(voiceCode)
            }
            
            return prepareResult
        }
    }
    
    /**
     * Merge multiple PCM chunks into a single continuous buffer
     * This prevents stuttering between chunks (Issue 2 fix)
     */
    private fun mergePcmChunks(chunks: List<ShortArray>): ShortArray {
        if (chunks.isEmpty()) {
            return ShortArray(0)
        }
        
        if (chunks.size == 1) {
            return chunks[0]
        }
        
        // Calculate total size
        val totalSize = chunks.sumOf { it.size }
        val merged = ShortArray(totalSize)
        
        // Copy all chunks into merged buffer
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }
        
        // Use verbose logging to avoid log spam in production
        Log.v(TAG, "Merged ${chunks.size} PCM chunks into single buffer of size $totalSize")
        return merged
    }
    
    /**
     * Update state and notify callback
     */
    private fun updateState(newState: TtsPlaybackState) {
        if (currentState != newState) {
            currentState = newState
            currentCallback?.onStateChanged(newState)
            Log.d(TAG, "State changed to: $newState")
        }
    }
    
    fun release() {
        stateLock.withLock {
            Log.d(TAG, "Releasing TTS engine")
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
