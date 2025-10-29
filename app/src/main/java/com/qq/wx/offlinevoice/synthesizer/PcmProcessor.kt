package com.qq.wx.offlinevoice.synthesizer

import java.io.ByteArrayOutputStream

/**
 * PCM audio processor using Sonic library for pitch and speed manipulation
 */
class PcmProcessor(
    private val sampleRate: Int = TtsConstants.SONIC_SAMPLE_RATE,
    private val numChannels: Int = TtsConstants.NUM_CHANNELS
) {
    private var sonic: Sonic? = null
    
    companion object {
        private const val TAG = "PcmProcessor"
    }
    
    /**
     * Initialize the Sonic processor with custom settings
     * @param speed Speed factor (1.0 = normal speed)
     * @param pitch Pitch factor (1.0 = normal pitch, < 1.0 = lower pitch)
     * @param rate Playback rate factor
     */
    fun initialize(
        speed: Float = TtsConstants.SONIC_SPEED,
        pitch: Float = TtsConstants.PITCH_FACTOR,
        rate: Float = TtsConstants.SONIC_RATE
    ) {
        if (sonic == null) {
            sonic = Sonic(sampleRate, numChannels).apply {
                setSpeed(speed)
                setPitch(pitch)
                setRate(rate)
                quality = TtsConstants.SONIC_QUALITY
            }
        }
    }
    
    /**
     * Process PCM data through Sonic (pitch shift and speed change)
     * @param inputPcm Input PCM data as short array
     * @return Processed PCM data as short array
     */
    fun process(inputPcm: ShortArray): ShortArray {
        val sonicInstance = sonic ?: run {
            initialize()
            sonic!!
        }
        
        // Convert short[] to bytes (little-endian)
        val inputBytes = shortsToBytes(inputPcm)
        
        // Write to Sonic
        sonicInstance.writeBytesToStream(inputBytes, inputBytes.size)
        
        // Read processed data from Sonic
        val outputStream = ByteArrayOutputStream(inputBytes.size + 1024)
        val buffer = ByteArray(inputBytes.size + 1024)
        
        var numRead: Int
        do {
            numRead = sonicInstance.readBytesFromStream(buffer, buffer.size)
            if (numRead > 0) {
                outputStream.write(buffer, 0, numRead)
            }
        } while (numRead > 0)
        
        val processedBytes = outputStream.toByteArray()
        
        // Convert processed bytes back to shorts
        return bytesToShorts(processedBytes)
    }
    
    /**
     * Flush any remaining data in Sonic's buffer
     * @return Flushed PCM data as short array
     */
    fun flush(): ShortArray {
        val sonicInstance = sonic ?: return ShortArray(0)
        
        sonicInstance.flushStream()
        
        // Read any remaining processed data from Sonic
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        
        var numRead: Int
        do {
            numRead = sonicInstance.readBytesFromStream(buffer, buffer.size)
            if (numRead > 0) {
                outputStream.write(buffer, 0, numRead)
            }
        } while (numRead > 0)
        
        val processedBytes = outputStream.toByteArray()
        
        // Convert processed bytes back to shorts
        return bytesToShorts(processedBytes)
    }
    
    /**
     * Release resources
     */
    fun release() {
        sonic = null
    }
    
    /**
     * Convert short array to byte array (little-endian)
     */
    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        var byteIndex = 0
        
        for (shortValue in shorts) {
            bytes[byteIndex++] = (shortValue.toInt() and 0xFF).toByte()
            bytes[byteIndex++] = ((shortValue.toInt() shr 8) and 0xFF).toByte()
        }
        
        return bytes
    }
    
    /**
     * Convert byte array to short array (little-endian)
     */
    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val numShorts = bytes.size / 2
        val shorts = ShortArray(numShorts)
        
        for (i in 0 until numShorts) {
            val byteIndex = i * 2
            shorts[i] = ((bytes[byteIndex].toInt() and 0xFF) or 
                        ((bytes[byteIndex + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        
        return shorts
    }
}
