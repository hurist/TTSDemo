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
 * - Pause/resume/stop controls
 * - Comprehensive callback system
 * - State management
 */
class TtsSynthesizer(
    context: Context,
    private val speaker: Speaker
) : TtsEngine {
    
    private val voiceCode: String = speaker.code
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)
    
    // State management
    @Volatile
    private var currentState: TtsPlaybackState = TtsPlaybackState.UNINITIALIZED
    
    @Volatile
    private var isPausedFlag: Boolean = false
    
    @Volatile
    private var isStoppedFlag: Boolean = false
    
    // Playback queue
    private val sentences = mutableListOf<String>()
    private var currentSentenceIndex: Int = 0
    private var currentSpeed: Float = 50f
    private var currentVolume: Float = 50f
    private var currentCallback: TtsCallback? = null
    
    // Thread management
    private var synthesisThread: Thread? = null
    private val stateLock = ReentrantLock()
    private val pauseLock = Object()
    
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
    
    override fun initialize() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.UNINITIALIZED) {
                Log.w(TAG, "Already initialized")
                return
            }
            
            try {
                if (instanceCount.incrementAndGet() == 1) {
                    nativeEngine = SynthesizerNative
                    nativeEngine?.init(voiceDataPath.toByteArray())
                    Log.d(TAG, "Native TTS engine initialized with path: $voiceDataPath")
                }
                
                currentState = TtsPlaybackState.IDLE
                currentCallback?.onInitialized(true)
                Log.d(TAG, "TTS engine initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TTS engine", e)
                currentState = TtsPlaybackState.ERROR
                currentCallback?.onInitialized(false)
                currentCallback?.onError("Initialization failed: ${e.message}")
            }
        }
    }
    
    override fun speak(text: String, speed: Float, volume: Float, callback: TtsCallback?) {
        stateLock.withLock {
            if (currentState == TtsPlaybackState.UNINITIALIZED) {
                Log.e(TAG, "Cannot speak: TTS engine not initialized")
                callback?.onError("TTS engine not initialized")
                return
            }
            
            if (currentState == TtsPlaybackState.PLAYING || currentState == TtsPlaybackState.PAUSED) {
                Log.w(TAG, "Already playing/paused, stopping current playback")
                stop()
            }
            
            // Split text into sentences
            sentences.clear()
            sentences.addAll(SentenceSplitter.splitWithDelimiters(text))
            
            if (sentences.isEmpty()) {
                Log.w(TAG, "No sentences to speak")
                callback?.onError("No valid sentences in text")
                return
            }
            
            Log.d(TAG, "Split text into ${sentences.size} sentences")
            
            currentSentenceIndex = 0
            currentSpeed = speed
            currentVolume = volume
            currentCallback = callback
            isPausedFlag = false
            isStoppedFlag = false
            
            // Start synthesis thread
            synthesisThread = Thread({
                executeSpeech()
            }, "TtsSynthesisThread")
            synthesisThread?.start()
        }
    }
    
    override fun pause() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PLAYING) {
                Log.w(TAG, "Cannot pause: not playing")
                return
            }
            
            isPausedFlag = true
            audioPlayer.stopAndRelease()
            updateState(TtsPlaybackState.PAUSED)
            currentCallback?.onPaused()
            Log.d(TAG, "Playback paused")
        }
    }
    
    override fun resume() {
        stateLock.withLock {
            if (currentState != TtsPlaybackState.PAUSED) {
                Log.w(TAG, "Cannot resume: not paused")
                return
            }
            
            isPausedFlag = false
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onResumed()
            Log.d(TAG, "Playback resumed")
            
            // Notify waiting thread
            synchronized(pauseLock) {
                pauseLock.notifyAll()
            }
        }
    }
    
    override fun stop() {
        stateLock.withLock {
            if (currentState == TtsPlaybackState.IDLE || currentState == TtsPlaybackState.STOPPING) {
                return
            }
            
            Log.d(TAG, "Stopping playback")
            isStoppedFlag = true
            isPausedFlag = false
            
            // Wake up paused thread
            synchronized(pauseLock) {
                pauseLock.notifyAll()
            }
            
            updateState(TtsPlaybackState.STOPPING)
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
            updateState(TtsPlaybackState.IDLE)
        }
    }
    
    override fun getStatus(): TtsStatus {
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
    
    override fun isSpeaking(): Boolean {
        return currentState == TtsPlaybackState.PLAYING
    }
    
    /**
     * Main speech execution method - processes all sentences in queue
     */
    private fun executeSpeech() {
        try {
            updateState(TtsPlaybackState.PLAYING)
            currentCallback?.onSynthesisStart()
            
            // Process each sentence
            while (currentSentenceIndex < sentences.size && !isStoppedFlag) {
                // Check if paused
                checkPauseState()
                
                if (isStoppedFlag) break
                
                val sentence = sentences[currentSentenceIndex]
                Log.d(TAG, "Speaking sentence $currentSentenceIndex: $sentence")
                
                // Notify sentence start
                currentCallback?.onSentenceStart(
                    currentSentenceIndex,
                    sentence,
                    sentences.size
                )
                
                // Synthesize and play this sentence
                val success = synthesizeSentence(sentence)
                
                if (!success || isStoppedFlag) {
                    break
                }
                
                // Notify sentence complete
                currentCallback?.onSentenceComplete(currentSentenceIndex, sentence)
                
                currentSentenceIndex++
            }
            
            // Check if we completed all sentences
            if (currentSentenceIndex >= sentences.size && !isStoppedFlag) {
                Log.d(TAG, "All sentences completed")
                currentCallback?.onSynthesisComplete()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during speech execution", e)
            updateState(TtsPlaybackState.ERROR)
            currentCallback?.onError("Speech execution error: ${e.message}")
        } finally {
            if (!isPausedFlag && currentState != TtsPlaybackState.PAUSED) {
                stateLock.withLock {
                    if (currentState != TtsPlaybackState.IDLE) {
                        updateState(TtsPlaybackState.IDLE)
                    }
                }
            }
        }
    }
    
    /**
     * Synthesize and play a single sentence
     */
    private fun synthesizeSentence(sentence: String): Boolean {
        try {
            // Prepare synthesis
            val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
            if (prepareResult != 0) {
                Log.e(TAG, "Failed to prepare synthesis, error code: $prepareResult")
                currentCallback?.onError("Failed to prepare sentence: $sentence")
                return false
            }
            
            pcmProcessor.initialize()
            
            val synthResult = IntArray(1)
            val pcmArray = pcmBuffer.array()
            
            // Synthesis loop for this sentence
            while (!isStoppedFlag && !isPausedFlag) {
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
                    break
                }
                
                // Extract valid PCM data
                val validSamples = minOf(pcmArray.size, numSamples)
                val validPcm = pcmArray.copyOf(validSamples)
                
                // Process PCM (pitch shift, speed change)
                val processedPcm = pcmProcessor.process(validPcm)
                
                // Play processed audio
                if (!isStoppedFlag && !isPausedFlag) {
                    audioPlayer.play(processedPcm)
                    
                    // Wait for playback
                    Thread.sleep(TtsConstants.PLAYBACK_SLEEP_MS)
                }
                
                // Check pause state
                checkPauseState()
            }
            
            // Flush remaining data
            pcmProcessor.flush()
            
            return !isStoppedFlag
            
        } catch (e: Exception) {
            Log.e(TAG, "Error synthesizing sentence", e)
            currentCallback?.onError("Synthesis error: ${e.message}")
            return false
        } finally {
            nativeEngine?.reset()
        }
    }
    
    /**
     * Check if paused and wait until resumed
     */
    private fun checkPauseState() {
        while (isPausedFlag && !isStoppedFlag) {
            synchronized(pauseLock) {
                try {
                    pauseLock.wait(500)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Interrupted while paused")
                    Thread.currentThread().interrupt()
                    break
                }
            }
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
            nativeEngine?.setSpeed(speed / TtsConstants.SPEED_VOLUME_SCALE)
            nativeEngine?.setVolume(volume / TtsConstants.SPEED_VOLUME_SCALE)
            
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
     * Update state and notify callback
     */
    private fun updateState(newState: TtsPlaybackState) {
        if (currentState != newState) {
            currentState = newState
            currentCallback?.onStateChanged(newState)
            Log.d(TAG, "State changed to: $newState")
        }
    }
    
    override fun release() {
        stateLock.withLock {
            Log.d(TAG, "Releasing TTS engine")
            stop()
            
            if (instanceCount.decrementAndGet() == 0) {
                nativeEngine?.destroy()
                nativeEngine = null
                currentVoiceCode = null
            }
            
            audioPlayer.stopAndRelease()
            pcmProcessor.release()
            currentState = TtsPlaybackState.UNINITIALIZED
        }
    }
    
    // Legacy methods for backward compatibility
    @Deprecated("Use stop() instead", ReplaceWith("stop()"))
    override fun cancel() {
        stop()
    }
    
    @Deprecated("Use speak() instead", ReplaceWith("speak(text, speed, volume, null)"))
    override fun synthesize(speed: Float, volume: Float, text: String, callback: g?) {
        speak(text, speed, volume, null)
    }
}
