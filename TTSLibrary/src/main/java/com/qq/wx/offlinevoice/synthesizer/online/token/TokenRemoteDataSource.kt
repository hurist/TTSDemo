package com.qq.wx.offlinevoice.synthesizer.online.token

import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.online.LogMask
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * TokenRemoteDataSource
 *
 * 与令牌服务交互的最小实现：
 * - GET /api/external/tokens
 * - 解析 JSON，选择最佳 token（valid==true 优先，按 last_validated 倒序）
 * - 返回 tts_token + vid（映射为 token/uid）
 *
 * 注意：
 * - 该接口为明文 HTTP（内网），Android 9+ 需在 networkSecurityConfig 中允许明文访问指定域/IP
 * - 不输出明文 token 到日志，统一使用 LogMask 脱敏
 */
class TokenRemoteDataSource(
    private val client: OkHttpClient,
    private var url: String
) {
    private val TAG = "TokenRemoteDataSource"

    fun setUrl(newUrl: String) {
        url = newUrl
    }

    suspend fun fetchLatestToken(): TokenUid {
        AppLogger.d(TAG, "请求令牌服务: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val response = client.newCall(request).execute()

        response.use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw TokenServiceException("令牌服务请求失败，HTTP ${res.code}，响应: $body")
            }
            val json = JSONObject(body)
            val success = json.optBoolean("success", false)
            val count = json.optInt("count", 0)
            val dataArr = json.optJSONArray("data")

            if (!success || dataArr == null || dataArr.length() == 0 || count == 0) {
                throw TokenServiceException("令牌服务返回空数据或失败。success=$success, count=$count, body=$body")
            }

            // 解析 entries
            val entries = mutableListOf<TokenEntry>()
            for (i in 0 until dataArr.length()) {
                val o = dataArr.getJSONObject(i)
                entries.add(
                    TokenEntry(
                        deviceId = o.optString("device_id", ""),
                        lastValidated = o.optString("last_validated", ""),
                        token = o.optString("tts_token", ""),
                        valid = o.optBoolean("valid", false),
                        vid = o.optString("vid", "")
                    )
                )
            }

            // 选择策略：valid==true 优先；再按 last_validated 倒序；否则兜底 data[0]
            val chosen = selectBestToken(entries)
                ?: entries.firstOrNull()
                ?: throw TokenServiceException("令牌服务 data 为空，无法选择 token。raw=$body")

            if (chosen.token.isBlank() || chosen.vid.isBlank()) {
                throw TokenServiceException("选中条目缺少必要字段（tts_token/vid）。entry=$chosen")
            }


            val uid = chosen.vid.toLongOrNull()
                ?: throw TokenServiceException("vid 不是整数：${chosen.vid}")

            AppLogger.i(
                TAG,
                "选择 token：device=${chosen.deviceId.ifBlank { "-" }}, last_validated=${chosen.lastValidated.ifBlank { "-" }}, valid=${chosen.valid}, uid=${LogMask.maskUid(uid)}，token=${LogMask.maskToken(chosen.token)}"
            )
            return TokenUid(token = chosen.token, uid = uid)
        }
    }

    private fun selectBestToken(entries: List<TokenEntry>): TokenEntry? {
        // 1) 过滤 valid==true
        val validList = entries.filter { it.valid }
        val base = validList.ifEmpty { entries }
        // 2) last_validated 为 ISO-like 字符串（例如 2025-11-14T02:18:25），直接按字典序倒序基本等同时间降序
        return base.maxByOrNull { it.lastValidated }
    }
}

/**
 * 令牌条目模型（直接映射接口字段）
 */
data class TokenEntry(
    val deviceId: String,
    val lastValidated: String,
    val token: String,
    val valid: Boolean,
    val vid: String
)

/**
 * 令牌服务异常，便于在上层区分失败来源
 */
class TokenServiceException(message: String) : RuntimeException(message)