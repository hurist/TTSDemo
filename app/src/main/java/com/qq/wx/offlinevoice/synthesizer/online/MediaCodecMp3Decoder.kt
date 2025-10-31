import android.media.MediaCodec
import android.media.MediaFormat
import com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * 一个使用 Android MediaCodec API 将 MP3 数据解码为 PCM 数据的解码器。
 *
 * @param sampleRate 期望的输出 PCM 数据的采样率。解码器会尝试输出此采样率，
 *                   但这取决于设备硬件能力。实际输出采样率可能在解码开始后改变。
 * @param channelCount 期望的输出声道数，通常为 1（单声道）或 2（立体声）。
 */
class MediaCodecMp3Decoder(
    private val sampleRate: Int,
    private val channelCount: Int = 1 // 默认为单声道
) : Mp3Decoder {

    companion object {
        // 解码器的 MIME 类型
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_MPEG
        // 等待输入/输出缓冲区的超时时间（微秒）
        private const val TIMEOUT_US = 10000L
    }

    /**
     * 将给定的 MP3 字节数组解码为 16-bit PCM ShortArray。
     *
     * @param mp3Data 包含完整 MP3 音频的字节数组。
     * @return 解码后的 PCM 数据，以 ShortArray 形式表示。
     * @throws IOException 如果找不到合适的解码器或解码过程中发生错误。
     * @throws IllegalStateException 如果 MediaCodec 状态不正确。
     */
    override fun decode(mp3Data: ByteArray): ShortArray {
        var decoder: MediaCodec? = null
        val decodedChunks = mutableListOf<ShortArray>()
        var totalDecodedSize = 0

        try {
            // 1. 创建解码器
            decoder = MediaCodec.createDecoderByType(MIME_TYPE)

            // 2. 配置解码器
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount)
            decoder.configure(format, null, null, 0)

            // 3. 启动解码器
            decoder.start()

            var inputEos = false // 输入流是否结束 (End of Stream)
            var outputEos = false // 输出流是否结束 (End of Stream)
            var mp3DataOffset = 0 // 当前读取 mp3Data 的位置

            // 4. 解码循环
            while (!outputEos) {
                // --- 输入阶段 ---
                if (!inputEos) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer: ByteBuffer? = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val chunkSize = min(mp3Data.size - mp3DataOffset, inputBuffer.remaining())

                            if (chunkSize > 0) {
                                inputBuffer.put(mp3Data, mp3DataOffset, chunkSize)
                                mp3DataOffset += chunkSize
                            }

                            val isEndOfStream = mp3DataOffset >= mp3Data.size
                            if (isEndOfStream) {
                                inputEos = true
                            }

                            // 将缓冲区提交给解码器
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                if (chunkSize < 0) 0 else chunkSize, // size
                                0, // presentation time (not critical for this simple case)
                                if (isEndOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                        }
                    }
                }

                // --- 输出阶段 ---
                val bufferInfo = MediaCodec.BufferInfo()
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                when {
                    outputBufferIndex >= 0 -> {
                        val outputBuffer: ByteBuffer? = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // MediaCodec 输出的是 16-bit PCM，每个采样点占 2 个字节
                            val pcmChunk = ShortArray(bufferInfo.size / 2)
                            // 将 ByteBuffer 转换为 ShortBuffer 来读取数据
                            outputBuffer.asShortBuffer().get(pcmChunk)

                            decodedChunks.add(pcmChunk)
                            totalDecodedSize += pcmChunk.size
                        }

                        // 释放输出缓冲区
                        decoder.releaseOutputBuffer(outputBufferIndex, false)

                        // 检查是否已到达流的末尾
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 输出格式改变，通常在第一帧数据后发生
                        // 在此可以获取实际的输出采样率和声道数
                        val outputFormat = decoder.outputFormat
                        // Log.d("MediaCodecMp3Decoder", "Output format has changed to $outputFormat")
                    }
                    // 其他情况 (如 INFO_TRY_AGAIN_LATER) 则继续循环
                }
            }

            // 5. 拼接所有解码后的数据块
            val finalPcmData = ShortArray(totalDecodedSize)
            var currentPosition = 0
            for (chunk in decodedChunks) {
                System.arraycopy(chunk, 0, finalPcmData, currentPosition, chunk.size)
                currentPosition += chunk.size
            }
            return finalPcmData

        } catch (e: Exception) {
            // 捕获所有可能的异常，如 IOException, IllegalStateException等
            e.printStackTrace()
            // 抛出更具体的异常或返回空数组，取决于你的错误处理策略
            throw IOException("Failed to decode MP3 data.", e)
        } finally {
            // 6. 确保资源被释放
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}