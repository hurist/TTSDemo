package com.qq.wx.offlinevoice.synthesizer.online

import android.content.Context
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
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.online.token.KEY_TOKEN
import com.qq.wx.offlinevoice.synthesizer.online.token.TokenProvider
import com.qq.wx.offlinevoice.synthesizer.online.token.KEY_UID
import com.qq.wx.offlinevoice.synthesizer.online.token.WX_SP_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WxReaderApi(private val context: Context) : OnlineTtsApi {

    private val sp by lazy {
        context.applicationContext.getSharedPreferences(WX_SP_NAME, Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(SupervisorJob())

    private val TAG = "WxReaderApi"
    private val TTS_API_URL = "https://ae.weixin.qq.com/aetts"
    private var token = ""
    private var uid = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS) // 单次读超时
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS) // 关键：整个调用的总超时
        .build()


    private val tokenProvider: TokenProvider by lazy {
        WxTokenManager.getOrInitProvider(
            context = context,
            client = client,
            sp = sp
        )
    }

    init {
        scope.launch {
            if (tokenProvider.prefetchIfStale()) {
                // 从 SharedPreferences 加载 token
                token = sp.getString(KEY_TOKEN, token) ?: token
                uid = sp.getLong(KEY_UID, uid)
                AppLogger.d(TAG, "初始化 WxReaderApi，加载 token: ${LogMask.maskToken(token)}")
            }
        }
    }


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

    override fun setToken(token: String, uid: Long) {
        this.token = token
        this.uid = uid
        // 同步更新 TokenProvider 的内存态，保持一致性（不改变你的接口语义）
        tokenProvider.setManualToken(token, uid)
        AppLogger.i(TAG, "setToken 手动设置 token/uid 完成，token=${LogMask.maskToken(token)}, uid=${LogMask.maskUid(uid)}")
    }

    override suspend fun fetchTtsAudio(text: String, speaker: Speaker): ByteArray {
        // 为避免死循环，最多重试 1 次（在 -13 刷新成功后）
        var attempt = 0
        while (true) {
            try {
                // 在每次请求前，从 TokenProvider 读取当前内存态，确保与刷新结果一致
                val current = tokenProvider.current()
                token = current.token
                uid = current.uid

                // --- 步骤 1: 请求 TTS 接口 ---
                val requestBody = buildRequestBody(text, speaker)
                val request = Request.Builder()
                    .url(TTS_API_URL)
                    .post(requestBody)
                    .build()

                AppLogger.d(TAG, "请求 TTS API: 文本长度=${text.length}, 模型=${speaker.modelName}, 使用 token=${LogMask.maskToken(token)}, uid=${LogMask.maskUid(uid)}")
                val response = client.newCall(request).await()

                // --- 步骤 2: 解析响应 ---
                // .use 会自动关闭 response body
                val responseBodyString = response.body?.string() ?: throw IOException("响应体为空")
                if (!response.isSuccessful) {
                    throw IOException("TTS API 请求失败，状态码: ${response.code}, 响应: $responseBodyString")
                }

                // --- 步骤 3: 根据解析结果执行后续操作 ---
                return when (val apiResponse = parseApiResponse(responseBodyString)) {
                    is ApiResponse.DirectAudio -> {
                        AppLogger.d(TAG, "成功获取 Base64 音频数据，长度: ${apiResponse.data.size} 字节")
                        apiResponse.data
                    }
                    is ApiResponse.AudioUrl -> {
                        AppLogger.d(TAG, "获取到音频 URL，开始下载: ${apiResponse.url}")
                        downloadAudioFromUrl(apiResponse.url)
                    }
                }
            } catch (e: WxApiException) {
                // 专门处理 API 异常，例如 Session 过期
                val code = e.errorCode
                if (code == -13 /* Session 过期 */) {
                    AppLogger.w(TAG, "检测到 token 过期（-13），开始自动刷新。attempt=$attempt, token=${LogMask.maskToken(token)}, uid=${LogMask.maskUid(uid)}", e)
                    if (attempt >= 1) {
                        AppLogger.e(TAG, "刷新后仍出现 -13 或已重试过，停止重试。", e)
                        throw e
                    }
                    try {
                        // 单飞刷新：并发场景下仅一次网络刷新，其它请求等待
                        val newPair = tokenProvider.refreshTokensSingleFlight()
                        this.token = newPair.token
                        this.uid = newPair.uid
                        AppLogger.i(TAG, "自动刷新 token 成功，准备重试 TTS。新 token=${LogMask.maskToken(newPair.token)}, 新 uid=${LogMask.maskUid(newPair.uid)}")
                        attempt++
                        continue // 回到 while，重试一次
                    } catch (refreshEx: Exception) {
                        AppLogger.e(TAG, "自动刷新 token 失败，无法恢复。", refreshEx)
                        throw e // 维持原始业务异常语义
                    }
                } else {
                    AppLogger.e(TAG, "WxApiException: code: $code, message: ${e.message}", e)
                    throw e
                }
            } catch (e: Exception) {
                // 统一捕获所有异常，简化错误处理
                AppLogger.e(TAG, "获取 TTS 音频失败: ${e.message}", e)
                // 将所有异常统一包装或重新抛出为 IOException，方便上层处理
                throw IOException("获取 TTS 音频失败: ${e.message}", e)
            }
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
                AppLogger.w(
                    TAG,
                    "API 返回错误，代码: $errCode, 信息: $errMsg, 响应: $responseBody, token: ${LogMask.maskToken(token)}, uid: ${LogMask.maskUid(uid)}"
                )
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
        AppLogger.d(TAG, "开始下载音频: $url")
        val response = client.newCall(request).await()
        val endTime = System.currentTimeMillis()
        AppLogger.d(TAG, "音频下载完成，耗时 ${endTime - startTime} ms")

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

/**
 * 日志脱敏工具，确保敏感信息不被明文输出。
 */
internal object LogMask {
    fun maskToken(token: String?): String {
        if (token.isNullOrEmpty()) return "null"
        if (token.length <= 6) return "***"
        return token.take(3) + "..." + token.takeLast(3)
    }

    fun maskUid(uid: Long?): String {
        return uid?.toString()?.let { s ->
            if (s.length <= 2) "***" else s.take(1) + "..." + s.takeLast(1)
        } ?: "null"
    }
}