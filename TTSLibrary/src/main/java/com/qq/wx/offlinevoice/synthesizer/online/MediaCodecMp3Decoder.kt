package com.qq.wx.offlinevoice.synthesizer.online

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.DecodedPcm
import java.io.File
import java.io.IOException

class MediaCodecMp3Decoder(private val context: Context) : Mp3Decoder {

    companion object {
        private const val TAG = "MP3解码器_兼容版"
        private const val TIMEOUT_US = 10000L
    }

    override fun decode(mp3Data: ByteArray): DecodedPcm {
        var decoder: MediaCodec? = null
        var extractor: MediaExtractor? = null
        var tempMp3File: File? = null

        val decodedChunks = mutableListOf<ShortArray>()
        var totalDecodedSize = 0
        var actualSampleRate = -1 // 用于存储真实的采样率

        try {
            tempMp3File = File.createTempFile("temp_tts_audio", ".mp3", context.cacheDir)
            tempMp3File.writeBytes(mp3Data)
            AppLogger.d(TAG, "MP3数据已写入临时文件: ${tempMp3File.absolutePath}")

            extractor = MediaExtractor()
            extractor.setDataSource(tempMp3File.absolutePath)

            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackFormat = format
                    extractor.selectTrack(i)
                    break
                }
            }

            if (trackFormat == null) throw IOException("在MP3数据中未找到音轨。")

            // 从格式中提取真实的采样率
            actualSampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val mimeType = trackFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("MIME类型为空。")
            AppLogger.d(TAG, "从数据中解析出的格式: $trackFormat")
            AppLogger.i(TAG, "音频真实采样率: $actualSampleRate Hz")

            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            var outputEos = false

            while (!outputEos) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputBufferIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            decoder.getOutputBuffer(outputBufferIndex)?.let { outputBuffer ->
                                val pcmChunk = ShortArray(bufferInfo.size / 2)
                                outputBuffer.asShortBuffer().get(pcmChunk)
                                decodedChunks.add(pcmChunk)
                                totalDecodedSize += pcmChunk.size
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                            AppLogger.d(TAG, "已到达输出流末尾(EOS)。")
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // MediaExtractor 方式通常不会在这里改变格式，但保留日志以防万一
                        val newFormat = decoder.outputFormat
                        actualSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        AppLogger.i(TAG, "输出格式已更改为: $newFormat, 新采样率: $actualSampleRate Hz")
                    }
                }
            }

            val finalPcmData = ShortArray(totalDecodedSize)
            var currentPosition = 0
            decodedChunks.forEach { chunk ->
                System.arraycopy(chunk, 0, finalPcmData, currentPosition, chunk.size)
                currentPosition += chunk.size
            }
            AppLogger.i(TAG, "解码成功完成。总PCM采样点数: $totalDecodedSize")

            if (actualSampleRate == -1) throw IOException("无法确定解码后的音频采样率。")

            // 返回包含PCM数据和采样率的对象
            return DecodedPcm(finalPcmData, actualSampleRate)

        } catch (e: Exception) {
            AppLogger.e(TAG, "解码过程中发生错误。", e)
            throw IOException("解码失败。", e)
        } finally {
            AppLogger.d(TAG, "正在释放资源。")
            try {
                decoder?.stop()
                decoder?.release()
                extractor?.release()
            } catch (e: Exception) {
                AppLogger.e(TAG, "释放解码器或提取器时出错。", e)
            }
            tempMp3File?.delete()
        }
    }
}