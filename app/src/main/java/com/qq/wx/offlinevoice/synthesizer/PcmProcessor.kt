package com.qq.wx.offlinevoice.synthesizer

import java.io.ByteArrayOutputStream

/**
 * PCM音频处理器 - 使用Sonic库进行音高和速度调整
 */
class PcmProcessor(
    private val sampleRate: Int = TtsConstants.SONIC_SAMPLE_RATE,
    private val numChannels: Int = TtsConstants.NUM_CHANNELS
) {
    private var sonic: Sonic? = null
    private var currentSpeed: Float = TtsConstants.SONIC_SPEED
    private var currentPitch: Float = TtsConstants.PITCH_FACTOR
    private var currentRate: Float = TtsConstants.SONIC_RATE
    
    companion object {
        private const val TAG = "PcmProcessor"
        private const val EPSILON = 0.0001f  // Tolerance for float comparison
    }
    
    /**
     * 使用自定义设置初始化Sonic处理器
     * @param speed 速度因子 (1.0 = 正常速度)
     * @param pitch 音高因子 (1.0 = 正常音高, < 1.0 = 降低音高)
     * @param rate 播放速率因子
     */
    fun initialize(
        speed: Float = TtsConstants.SONIC_SPEED,
        pitch: Float = TtsConstants.PITCH_FACTOR,
        rate: Float = TtsConstants.SONIC_RATE
    ) {
        // Check if parameters changed using tolerance-based comparison
        val speedChanged = kotlin.math.abs(speed - currentSpeed) > EPSILON
        val pitchChanged = kotlin.math.abs(pitch - currentPitch) > EPSILON
        val rateChanged = kotlin.math.abs(rate - currentRate) > EPSILON
        
        // If parameters changed or sonic is null, reinitialize
        if (sonic == null || speedChanged || pitchChanged || rateChanged) {
            // Sonic doesn't have explicit cleanup, just release reference
            sonic = null
            
            // Create new instance with updated parameters
            currentSpeed = speed
            currentPitch = pitch
            currentRate = rate
            
            sonic = Sonic(sampleRate, numChannels).apply {
                setSpeed(speed)
                setPitch(pitch)
                setRate(rate)
                quality = TtsConstants.SONIC_QUALITY
            }
        }
    }
    
    /**
     * 通过Sonic处理PCM数据 (音高变换和速度变化)
     * @param inputPcm 输入的PCM数据（短整型数组）
     * @return 处理后的PCM数据（短整型数组）
     */
    fun process(inputPcm: ShortArray): ShortArray {
        // Validate input
        if (inputPcm.isEmpty()) {
            return ShortArray(0)
        }
        
        val sonicInstance = sonic ?: run {
            initialize()
            sonic ?: return inputPcm  // Return original if initialization fails
        }
        
        try {
            // 将short[]转换为字节（小端序）
            val inputBytes = shortsToBytes(inputPcm)
            
            // 写入Sonic
            sonicInstance.writeBytesToStream(inputBytes, inputBytes.size)
            
            // 从Sonic读取处理后的数据
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
            
            // 将处理后的字节转换回short数组
            return bytesToShorts(processedBytes)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error processing PCM data", e)
            // On error, return original input
            return inputPcm
        }
    }
    
    /**
     * 刷新Sonic缓冲区中的剩余数据
     * @return 刷新的PCM数据（短整型数组）
     */
    fun flush(): ShortArray {
        val sonicInstance = sonic ?: return ShortArray(0)
        
        try {
            sonicInstance.flushStream()
            
            // 从Sonic读取任何剩余的处理数据
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
            
            // 将处理后的字节转换回short数组
            return bytesToShorts(processedBytes)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error flushing PCM data", e)
            return ShortArray(0)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        sonic = null
    }
    
    /**
     * 将short数组转换为byte数组（小端序）
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
     * 将byte数组转换为short数组（小端序）
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
