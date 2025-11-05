package com.qq.wx.offlinevoice.synthesizer.online

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.qq.wx.offlinevoice.synthesizer.Speaker
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.core.content.edit

class WxReaderApi(private val context: Context) : OnlineTtsApi {

    private val sp by lazy {
        context.getSharedPreferences("wx_offline_tts_prefs", Context.MODE_PRIVATE)
    }

    private val TAG = "WxReaderApi"
    private val TTS_API_URL = "https://ae.weixin.qq.com/aetts"
    private var token = "iQn+rpIPR979fyiwMe18aHeFc7NZv2Fpjmy7QDTRI+sdpy/whyVwpSqc7BWgSVDd"
    private var uid = 176267434

    init {
        // 从 SharedPreferences 加载 token
        token = sp.getString("token", token) ?: token
        uid = sp.getInt("uid", uid)
        Log.d(TAG, "初始化 WxReaderApi，加载 token: $token")
    }

    // --- 优化 1: 创建一个共享的、配置合理的 OkHttpClient 实例 ---
    // 这对于性能至关重要，可以复用连接池和线程。
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS) // 单次读超时
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS) // 关键：整个调用的总超时
        .build()

    // --- 优化 2: 使用扩展函数，将 OkHttp 的回调风格转换为协程的 suspend/resume 风格 ---
    // 这使得网络调用代码像同步代码一样直观。
    private suspend fun Call.await(): Response {
        return suspendCancellableCoroutine { continuation ->
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
            continuation.invokeOnCancellation {
                cancel()
            }
        }
    }

    override fun setToken(token: String, uid: Int) {
        this.token = token
        this.uid = uid
        sp.edit {
            putString("token", token)
            putInt("uid", uid)
        }
    }

    override suspend fun fetchTtsAudio(text: String, speaker: Speaker): ByteArray {
        try {
            // --- 步骤 1: 请求 TTS 接口 ---
            val requestBody = buildRequestBody(text, speaker)
            val request = Request.Builder()
                .url(TTS_API_URL)
                .post(requestBody)
                .build()

            Log.d(TAG, "请求 TTS API: $text")
            val response = client.newCall(request).await()

            // --- 步骤 2: 解析响应 ---
            // .use 会自动关闭 response body
            val responseBodyString = response.body?.string() ?: throw IOException("响应体为空")
            if (!response.isSuccessful) {
                throw IOException("TTS API 请求失败，状态码: ${response.code}, 响应: $responseBodyString")
            }

            // --- 优化 3: 将解析和网络分离，parseApiResponse 只负责解析 ---
            val apiResponse = parseApiResponse(responseBodyString)

            // --- 步骤 3: 根据解析结果执行后续操作 ---
            return when (apiResponse) {
                is ApiResponse.DirectAudio -> {
                    Log.d(TAG, "成功获取 Base64 音频数据，长度: ${apiResponse.data.size} 字节")
                    apiResponse.data
                }
                is ApiResponse.AudioUrl -> {
                    Log.d(TAG, "获取到音频 URL，开始下载: ${apiResponse.url}")
                    downloadAudioFromUrl(apiResponse.url)
                }
            }
        } catch (e: WxApiException) {
            // 专门处理 API 异常，例如 Session 过期
            Log.e(TAG, "WxApiException: code: ${e.errorCode}, message: ${e.message}", e)
            throw e

        } catch (e: Exception) {
            // 统一捕获所有异常，简化错误处理
            Log.e(TAG, "获取 TTS 音频失败: ${e.message}", e)
            // 将所有异常统一包装或重新抛出为 IOException，方便上层处理
            throw IOException("获取 TTS 音频失败: ${e.message}", e)
        }
    }

    /**
     * 构建发送到 /aetts 接口的 JSON 请求体。
     */
    private fun buildRequestBody(text: String, speaker: Speaker): RequestBody {
        // 使用 apply 语法糖让代码更紧凑
        val json = JSONObject().apply {
            put("key_prefix", "847310")
            put("chapter_id", 43)
            put("model_name", speaker.modelName)
            put("style", 1)
            put("text_utf8", text)
            // 注意：硬编码的 token 可能会过期，实际应用中应该动态获取
            put("token", token)
            put("uid", uid)
            put("version", "9.3.8.10166907")
            put("busi_type", 1)
            put("format", 0)
        }
        return json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    /**
     * 仅负责解析 /aetts 接口的 JSON 响应，不执行任何 I/O 操作。
     * @return 返回 ApiResponse 模型或抛出异常。
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun parseApiResponse(responseBody: String): ApiResponse {
        val json = JSONObject(responseBody)
        val data = json.optString("audio_data", "")
        val audioUrl = json.optString("audio_url", "")

        return when {
            data.isNotEmpty() -> {
                val decodedBytes = Base64.decode(data)
                ApiResponse.DirectAudio(decodedBytes)
            }
            audioUrl.isNotEmpty() -> {
                ApiResponse.AudioUrl(audioUrl)
            }
            else -> {
                // 处理 API 错误
                val baseResponse = json.optJSONObject("baseResponse")
                val errMsg = baseResponse?.optString("msg", "未知错误") ?: "未知错误"
                val errCode = baseResponse?.optInt("ret", -1) ?: -1
                Log.w(TAG, "API 返回错误，代码: $errCode, 信息: $errMsg, 响应: $responseBody, token: $token, uid: $uid")
                if (errCode == -13) {
                    throw SessionExpiredException(errMsg)
                } else {
                    throw WxApiException(errCode, errMsg)
                }
            }
        }
    }

    /**
     * 从给定的 URL 下载音频数据。
     */
    private suspend fun downloadAudioFromUrl(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "开始下载音频: $url")
        val response = client.newCall(request).await()
        val endTime = System.currentTimeMillis()
        Log.d(TAG, "音频下载完成，耗时 ${endTime - startTime} ms")

        response.use { res ->
            if (!res.isSuccessful) {
                throw IOException("下载音频失败，状态码: ${res.code}")
            }
            return res.body?.bytes() ?: throw IOException("下载的音频响应体为空")
        }
    }
}


private sealed class ApiResponse {
    /** API 直接返回了 Base64 编码的音频数据 */
    data class DirectAudio(val data: ByteArray) : ApiResponse() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DirectAudio

            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    /** API 返回了一个需要再次下载的音频 URL */
    data class AudioUrl(val url: String) : ApiResponse()
}