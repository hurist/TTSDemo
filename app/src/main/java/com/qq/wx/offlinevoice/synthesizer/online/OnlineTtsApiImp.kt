package com.qq.wx.offlinevoice.synthesizer.online

import android.util.Log
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
        json.put("token", "aXFhoMC5m8TT/RSpCQVYFrALGyh9TUVXTXkc3sydj9Qivwe4h5l+cR3NA7YI9QAq")
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
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val audioData = parseAudioData(responseBody)
                        if (audioData != null) {
                            ctx.resumeWith(Result.success(audioData))
                        } else {
                            Log.e(TAG, "音频数据解析失败:$responseBody")
                            ctx.resumeWith(Result.failure(IOException("音频数据解析失败")))
                        }
                    } else {
                        Log.e(TAG, "响应体为空:$response")
                        ctx.resumeWith(Result.failure(IOException("响应体为空")))
                    }
                } else {
                    Log.e(TAG, "网络请求失败，状态码: ${response.code}")
                    ctx.resumeWith(Result.failure(IOException("网络请求失败，状态码: ${response.code}")))
                }
            }
        })
    }

    private fun parseAudioData(responseBody: String): ByteArray? {
        val json = JSONObject(responseBody)
        val data = json.optString("audio_data", "")
        return if (data.isNotEmpty()) {
            // 解base64, 结果为mp3格式的byte数组
            Base64.decode(data)
        } else {
            null
        }
    }
}