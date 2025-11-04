package com.qq.wx.offlinevoice.synthesizer

import android.util.Log

/**
 * 使用我们项目内的 Sonic.java 类对 PCM 音频流进行变速处理。
 * 这个类不是线程安全的，需要外部同步。
 */
class AudioSpeedProcessor(
    val sampleRate: Int,
    private val numChannels: Int = 1
) {
    private var sonic: Sonic? = null
    private var currentSpeed: Float = 1.0f

    init {
        try {
            sonic = Sonic(sampleRate, numChannels)
            Log.d("AudioSpeedProcessor", "Sonic instance created for sample rate $sampleRate")
        } catch (e: Exception) {
            Log.e("AudioSpeedProcessor", "Failed to create Sonic instance", e)
        }
    }

    fun setSpeed(speed: Float) {
        if (sonic == null || this.currentSpeed == speed) return
        this.currentSpeed = speed
        sonic?.speed = speed
        Log.d("AudioSpeedProcessor", "Sonic speed set to $speed")
    }

    /**
     * 将 PCM 数据块送入处理器，并获取处理后的数据。
     * @param pcmData 输入的 ShortArray PCM 数据。
     * @return 处理后的 ShortArray PCM 数据，长度可能不同。
     */
    fun process(pcmData: ShortArray): ShortArray {
        val currentSonic = sonic ?: return pcmData // 如果初始化失败，返回原始数据

        // 将数据写入 Sonic 的内部输入缓冲区
        currentSonic.writeShortToStream(pcmData, pcmData.size)

        // 从 Sonic 的内部输出缓冲区读取所有可用的变速后数据
        val availableSamples = currentSonic.samplesAvailable()
        if (availableSamples <= 0) {
            return ShortArray(0)
        }

        // 创建一个刚好大小的数组来存放结果
        val processedPcm = ShortArray(availableSamples)
        currentSonic.readShortFromStream(processedPcm, availableSamples)

        return processedPcm
    }

    /**
     * 标记输入流结束，并获取 Sonic 内部缓冲的最后数据。
     * @return 最后的 PCM 数据块。
     */
    fun flush(): ShortArray {
        val currentSonic = sonic ?: return ShortArray(0)

        // flush 会处理内部所有剩余的输入，并将其放入输出缓冲区
        currentSonic.flushStream()
        
        val availableSamples = currentSonic.samplesAvailable()
        if (availableSamples <= 0) {
            return ShortArray(0)
        }

        val finalPcm = ShortArray(availableSamples)
        currentSonic.readShortFromStream(finalPcm, availableSamples)
        return finalPcm
    }

    fun release() {
        // Sonic.java 没有显式的 release/close 方法，
        // 将其设为 null 以便垃圾回收即可。
        sonic = null
        Log.d("AudioSpeedProcessor", "Sonic instance released.")
    }
}