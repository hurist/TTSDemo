package com.qq.wx.offlinevoice.synthesizer.online

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.DecodedPcm
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.util.UUID

class MediaCodecMp3Decoder(private val context: Context) : Mp3Decoder {

    companion object {
        private const val TAG = "MP3解码器_兼容版"
        private const val TIMEOUT_US = 20_000L
        private const val KEY_PCM_ENCODING = "pcm-encoding" // MediaFormat.KEY_PCM_ENCODING (API 24) 的字面值
        private const val ENCODING_PCM_16BIT = 2 // AudioFormat.ENCODING_PCM_16BIT 的数值
    }

    override suspend fun decode(mp3Data: ByteArray): DecodedPcm {
        var decoder: MediaCodec? = null
        var extractor: MediaExtractor? = null
        var tempMp3File: File? = null
        var mediaDataSource: MediaDataSource? = null

        var actualSampleRate = -1
        var actualChannelCount = -1
        var pcmEncoding = ENCODING_PCM_16BIT // 假定 16-bit（API<24 无法可靠读取）
        var inputEos = false
        var outputEos = false

        // 动态累加器，减少临时对象与拷贝
        class ShortAccumulator(initialCapacity: Int = 8192) {
            var array = ShortArray(initialCapacity)
                private set
            var size = 0
                private set

            fun ensureCapacity(minCapacity: Int) {
                if (minCapacity <= array.size) return
                var newCap = array.size.coerceAtLeast(1)
                while (newCap < minCapacity) {
                    newCap = newCap shl 1
                }
                array = array.copyOf(newCap)
            }

            fun advanceBy(count: Int) {
                size += count
            }

            fun toArray(): ShortArray = array.copyOf(size)
        }

        val acc = ShortAccumulator()

        try {
            val startTime = System.currentTimeMillis()

            extractor = MediaExtractor()

            // 数据源：API 23+ 用内存数据源；否则写入临时文件
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mediaDataSource = object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (position >= mp3Data.size) return -1
                        val remaining = mp3Data.size - position.toInt()
                        val toCopy = remaining.coerceAtMost(size)
                        System.arraycopy(mp3Data, position.toInt(), buffer, offset, toCopy)
                        return toCopy
                    }
                    override fun getSize(): Long = mp3Data.size.toLong()
                    override fun close() {}
                }
                extractor.setDataSource(mediaDataSource)
                AppLogger.d(TAG, "使用内存数据源(MediaDataSource)加载 MP3 数据。")
            } else {
                val random = UUID.randomUUID().toString()
                val randomFilename = "temp_tts_${random}"
                tempMp3File = File.createTempFile(randomFilename, ".mp3", context.cacheDir)
                tempMp3File.writeBytes(mp3Data)
                extractor.setDataSource(tempMp3File.absolutePath)
                AppLogger.d(TAG, "MP3数据已写入临时文件: ${tempMp3File.absolutePath}")
            }

            // 选择音轨
            var trackFormat: MediaFormat? = null
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackFormat = format
                    trackIndex = i
                    break
                }
            }
            if (trackFormat == null || trackIndex == -1) throw IOException("在 MP3 数据中未找到音轨。")

            extractor.selectTrack(trackIndex)

            val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("MIME 类型为空。")

            actualSampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            actualChannelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            else 1

            AppLogger.d(TAG, "初始音轨格式: $trackFormat")
            AppLogger.i(TAG, "初始采样率: $actualSampleRate Hz, 声道数: $actualChannelCount")

            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputEos) {
                // 填充输入
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            val sampleFlags = extractor.sampleFlags // 兼容性标记
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                // 取出输出
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val ended = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val sizeBytes = bufferInfo.size
                        if (sizeBytes > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outIndex)!!
                            // 必须设置 position/limit 并指定 little-endian
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + sizeBytes)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                            val shortCount = sizeBytes / 2
                            if (shortCount > 0) {
                                acc.ensureCapacity(acc.size + shortCount)
                                // 直接写入累加器底层数组，避免临时 ShortArray
                                outputBuffer.asShortBuffer().get(acc.array, acc.size, shortCount)
                                acc.advanceBy(shortCount)
                            } else {
                                // sizeBytes 不是 2 的倍数，忽略最后一个残留字节
                            }
                        }

                        decoder.releaseOutputBuffer(outIndex, false)

                        if (ended) {
                            outputEos = true
                            AppLogger.d(TAG, "已到达输出流末尾(EOS)。")
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        AppLogger.i(TAG, "输出格式已更改为: $newFormat")

                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            actualSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            actualChannelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        // API 24+ 才有 KEY_PCM_ENCODING；低版本默认当 16-bit 处理
                        if (newFormat.containsKey(KEY_PCM_ENCODING)) {
                            pcmEncoding = newFormat.getInteger(KEY_PCM_ENCODING)
                        }
                        if (pcmEncoding != ENCODING_PCM_16BIT) {
                            // 当前实现只返回 ShortArray，如遇到 32-bit float 需要额外转换
                            AppLogger.w(TAG, "检测到非 16-bit PCM 编码($pcmEncoding)，当前实现仅支持 16-bit。")
                            throw IOException("仅支持 16-bit PCM 输出。")
                        }
                        AppLogger.i(TAG, "更新后采样率: $actualSampleRate Hz, 声道数: $actualChannelCount")
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 暂时无可用输出，继续循环等待
                    }
                }
            }

            val finalPcmData = acc.toArray()
            val endTime = System.currentTimeMillis()
            AppLogger.i(
                TAG,
                "解码完成，耗时 ${endTime - startTime} ms, 总PCM采样点数: ${finalPcmData.size}，采样率: $actualSampleRate Hz，声道: $actualChannelCount"
            )

            if (actualSampleRate <= 0) throw IOException("无法确定解码后的音频采样率。")

            // 注意：如果下游只接受单声道，而 MP3 为双声道，需要在此处做下混（可选）
            val mono = downmixToMono(finalPcmData, actualChannelCount)

            return DecodedPcm(mono, actualSampleRate)

        } catch (e: Exception) {
            AppLogger.e(TAG, "解码过程中发生错误。", e)
            throw IOException("解码失败。", e)
        } finally {
            AppLogger.d(TAG, "正在释放资源。")
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                AppLogger.e(TAG, "释放解码器时出错。", e)
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                AppLogger.e(TAG, "释放提取器时出错。", e)
            }
            try {
                mediaDataSource?.close()
            } catch (e: Exception) {
                AppLogger.e(TAG, "关闭 MediaDataSource 时出错。", e)
            }
            try {
                tempMp3File?.delete()
            } catch (_: Exception) {
            }
        }
    }

    // 如需将多声道下混为单声道，可启用此函数并在返回前调用
    @Suppress("unused")
    private fun downmixToMono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val frames = pcm.size / channels
        val out = ShortArray(frames)
        var src = 0
        for (i in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += pcm[src + ch].toInt()
            }
            // 简单平均并裁剪
            val v = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = v.toShort()
            src += channels
        }
        return out
    }
}