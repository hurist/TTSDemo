package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.util.Log
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-Speech synthesizer that handles speech synthesis and playback.
 * This class replaces the obfuscated 'a' class with a cleaner, more maintainable design.
 * 
 * The synthesis process:
 * 1. Prepare text for synthesis using native TTS library
 * 2. Synthesize text to PCM audio data
 * 3. Process PCM data (optional pitch/speed adjustment)
 * 4. Play processed audio
 */
class TtsSynthesizer(
    context: Context,
    private val speaker: Speaker
) : TtsEngine {
    
    private val voiceCode: String = speaker.code
    private val voiceDataPath: String
    private val pcmBuffer: ShortBuffer = ShortBuffer.allocate(TtsConstants.PCM_BUFFER_SIZE)
    
    @Volatile
    private var isCancelled: Boolean = false
    
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
        synchronized(this) {
            if (instanceCount.incrementAndGet() == 1) {
                nativeEngine = SynthesizerNative
                nativeEngine?.init(voiceDataPath.toByteArray())
                Log.d(TAG, "Native TTS engine initialized with path: $voiceDataPath")
            }
        }
    }
    
    override fun cancel() {
        synchronized(this) {
            isCancelled = true
            if (voiceCode == currentVoiceCode) {
                nativeEngine?.reset()
            }
            audioPlayer.stopAndRelease()
        }
    }
    
    override fun synthesize(speed: Float, volume: Float, text: String, callback: g?) {
        Thread {
            executeSynthesis(speed, volume, text)
        }.start()
    }
    
    /**
     * Main synthesis execution method
     */
    private fun executeSynthesis(speed: Float, volume: Float, text: String) {
        try {
            // Prepare synthesis
            val prepareResult = prepareForSynthesis(text, speed, volume)
            if (prepareResult != 0) {
                Log.e(TAG, "Failed to prepare synthesis, error code: $prepareResult")
                return
            }
            
            isCancelled = false
            pcmProcessor.initialize()
            
            val synthResult = IntArray(1)
            val pcmArray = pcmBuffer.array()
            
            // Synthesis loop
            while (!isCancelled) {
                val synthesisStatus = nativeEngine?.synthesize(
                    pcmArray,
                    TtsConstants.PCM_BUFFER_SIZE,
                    synthResult,
                    1
                ) ?: -1
                
                if (synthesisStatus == -1) {
                    Log.e(TAG, "Synthesis failed")
                    nativeEngine?.reset()
                    return
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
                if (!isCancelled) {
                    audioPlayer.play(processedPcm)
                    
                    // Wait for playback (this is a simple approach; could be improved with callbacks)
                    Thread.sleep(TtsConstants.PLAYBACK_SLEEP_MS)
                }
            }
            
            // Flush remaining data
            pcmProcessor.flush()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during synthesis", e)
        } finally {
            nativeEngine?.reset()
        }
    }
    
    /**
     * Prepare the native engine for synthesis
     * @return 0 on success, error code otherwise
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
    
    override fun release() {
        synchronized(this) {
            if (instanceCount.decrementAndGet() == 0) {
                nativeEngine?.destroy()
                nativeEngine = null
                currentVoiceCode = null
            }
        }
        audioPlayer.stopAndRelease()
        pcmProcessor.release()
    }
}
