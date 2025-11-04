package com.qq.wx.offlinevoice.synthesizer.online

import android.util.Log
import com.qq.wx.offlinevoice.synthesizer.DecodedPcm
import com.qq.wx.offlinevoice.synthesizer.Speaker
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.io.encoding.Base64

object WxReaderApi : OnlineTtsApi {

    const val TAG = "WxReaderApi"


    override suspend fun fetchTtsAudio(
        text: String,
        speaker: Speaker
    ) = suspendCancellableCoroutine<ByteArray> { ctx ->

        val json = JSONObject()
        json.put("key_prefix", "847310")
        json.put("chapter_id", 43)
        json.put("model_name", speaker.modelName)
        json.put("style", 1)
        json.put("text_utf8", text)
        json.put("token", "vMZfAzT75s42LQ9Uf7tdocykZ4SSR21G+WM08HvvC/IgnVILQ8Mz34WHE2Sx+xtN")
        json.put("uid", 155027727)
        json.put("version", "9.3.8.10166907")
        json.put("busi_type", 1)
        json.put("format", 0)

        val requestBody =
            json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())


        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url("https://ae.weixin.qq.com/aetts")
            .post(requestBody)
            .build()


        val call = client.newCall(request)
        ctx.invokeOnCancellation { call.cancel() }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "网络请求失败: ${e.message}")
                if (ctx.isActive) {
                    ctx.resumeWith(Result.failure(IOException("网络请求失败", e)))
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    val result = parseAudioData(responseBody)
                    result.onFailure {
                        when (it) {
                            is SessionExpiredException -> Log.e(TAG, "会话已过期，请重新获取token。")
                            is WxApiException -> Log.e(TAG, "微信API错误，代码: ${it.errorCode}, 信息: ${it.message}")
                            else -> Log.e(TAG, "解析音频数据失败: ${it.message}, 响应内容: $responseBody")
                        }
                        ctx.resumeWith(Result.failure(it))
                    }.onSuccess {
                        Log.d(TAG, "成功获取音频数据，长度: ${it.size} 字节")
                        ctx.resumeWith(Result.success(it))
                    }
                } else {
                    Log.e(TAG, "网络请求失败，状态码: ${response.code}")
                    ctx.resumeWith(Result.failure(IOException("网络请求失败，状态码: ${response.code}")))
                }
            }
        })
    }

    private fun parseAudioData(responseBody: String): Result<ByteArray> {
        val json = JSONObject(responseBody)
        val data = json.optString("audio_data", "")
        if (data.isNotEmpty()) {
            // 解base64, 结果为mp3格式的byte数组
            return runCatching { Base64.decode(data) }
        } else {
            val baseResponse = json.optJSONObject("baseResponse")
            val errMsg = baseResponse?.optString("msg", "未知错误") ?: "未知错误"
            val errCode = baseResponse?.optInt("ret", -1) ?: -1
            return if (errCode == -13) {
                Result.failure(SessionExpiredException(errMsg))
            } else {
                Result.failure(WxApiException(errCode, errMsg))
            }
        }
    }
}