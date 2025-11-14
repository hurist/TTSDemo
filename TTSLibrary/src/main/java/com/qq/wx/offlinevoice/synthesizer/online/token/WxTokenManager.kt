package com.qq.wx.offlinevoice.synthesizer.online

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.online.token.TokenProvider
import com.qq.wx.offlinevoice.synthesizer.online.token.TokenUid
import com.qq.wx.offlinevoice.synthesizer.online.token.WX_SP_NAME
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * WxTokenManager
 *
 * 面向外部依赖者暴露的“静态方法”入口，用于：
 * - 主动刷新 token（协程版本与阻塞版本）
 * - 获取/设置当前 token/uid
 * - 预取（可选）
 *
 * 设计说明：
 * - 采用全局单例方式持有 TokenProvider，确保应用内只有一个 Provider 实例，避免并发“惊群”
 * - TokenProvider 与 WxReaderApi 共用同一实例（WxReaderApi 将通过 WxTokenManager 获取 Provider）
 * - 严格使用日志脱敏，避免泄漏 token/uid
 *
 * 注意：
 * - refreshTokensBlocking(...) 为阻塞方法，请勿在主线程调用（否则可能 ANR），建议优先使用 suspend 版本
 * - SharedPreferences 与 WxReaderApi 使用同一名称 "wx_offline_tts_prefs" 保证读写一致
 */
object WxTokenManager {

    private const val TAG = "WxTokenManager"

    @Volatile
    private var provider: TokenProvider? = null
    private var tokenFetchUrl: String = "http://192.168.1.212:8866/api/external/tokens"


    @JvmStatic
    fun setTokenFetchUrl(url: String) {
        tokenFetchUrl = url
        if (provider != null) {
            provider?.setTokenFetchUrl(url)
        }
    }

    /**
     * 初始化或获取全局 TokenProvider。
     * - 若已初始化则直接返回；否则使用传入 client/sp 或默认配置创建。
     * - 默认 client 为轻量网络配置，只用于调用令牌服务，避免拖慢 TTS 重试时延。
     */
    @JvmStatic
    fun getOrInitProvider(
        context: Context,
        client: OkHttpClient? = null,
        sp: SharedPreferences? = null,
        tokenUrl: String? = tokenFetchUrl
    ): TokenProvider {

        if (tokenUrl.isNullOrBlank().not()) {
            this.tokenFetchUrl = tokenUrl
        }

        provider?.let { return it }
        synchronized(this) {
            provider?.let { return it }
            val appCtx = context.applicationContext
            val finalSp = sp ?: appCtx.getSharedPreferences(WX_SP_NAME, Context.MODE_PRIVATE)
            val finalClient = client ?: OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()

            val p = TokenProvider(
                context = appCtx,
                client = finalClient,
                sp = finalSp,
                tokenFetchUrl = this.tokenFetchUrl
            )
            provider = p

            return p
        }
    }

    /**
     * 协程版本：刷新 token。
     * - 并发情况下全局只会进行一次网络刷新，其他调用会等待同一结果（SingleFlight）
     * - 返回刷新后的 token/uid
     */
    @JvmStatic
    suspend fun refreshTokens(context: Context): TokenUid {
        val p = getOrInitProvider(context)
        AppLogger.i(TAG, "外部请求：开始刷新 token（suspend）。")
        return p.refreshTokensSingleFlight().also {
            AppLogger.i(
                TAG,
                "外部请求：刷新 token 成功。token=${LogMask.maskToken(it.token)}, uid=${
                    LogMask.maskUid(it.uid)
                }"
            )
        }
    }

    /**
     * 阻塞版本：刷新 token。
     * - 警告：请勿在主线程调用，可能导致 ANR。
     * - 推荐优先使用协程版本 refreshTokens(context)。
     */
    @JvmStatic
    fun refreshTokensBlocking(context: Context): TokenUid {
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            AppLogger.w(
                TAG,
                "refreshTokensBlocking 在主线程调用，可能引发 ANR。建议使用协程版本 refreshTokens(context)。"
            )
        }
        return runBlocking {
            refreshTokens(context)
        }
    }

    /**
     * 获取当前内存态 token/uid（不触发网络）。
     * - 注意：若进程刚启动且尚未使用过 Provider，第一次调用会走初始化流程。
     */
    @JvmStatic
    fun current(context: Context): TokenUid {
        val p = getOrInitProvider(context)
        val cur = p.current()
        AppLogger.d(
            TAG,
            "查询当前 token/uid：token=${LogMask.maskToken(cur.token)}, uid=${LogMask.maskUid(cur.uid)}"
        )
        return cur
    }

    /**
     * 手动设置 token/uid（例如在设置页中调用）。
     * - 会同时更新内存与持久化（SharedPreferences）
     * - WxReaderApi 将自动使用该更新
     */
    @JvmStatic
    fun setManualToken(context: Context, token: String, uid: Long) {
        val p = getOrInitProvider(context)
        AppLogger.i(
            TAG,
            "外部请求：手动设置 token/uid。token=${LogMask.maskToken(token)}, uid=${
                LogMask.maskUid(uid)
            }"
        )
        p.setManualToken(token, uid)
    }

    /**
     * 预取（可选）：当本地 token/uid 为空或过旧时，可以主动调用预取以降低首次失败率。
     * - 不会抛异常，失败返回 false 并打印详细日志
     */
    @JvmStatic
    suspend fun prefetchIfStale(context: Context): Boolean {
        val p = getOrInitProvider(context)
        AppLogger.d(TAG, "外部请求：尝试预取 token（若判定为陈旧）。")
        return p.prefetchIfStale().also { ok ->
            AppLogger.d(TAG, "外部请求：预取结果=$ok。")
        }
    }


    /**
     * 新增：按需刷新。
     * - 若本地 token 不存在或 uid 非法，立即刷新
     * - 若距离上次刷新时间超过 maxAgeHours（默认 24 小时），刷新
     * - 否则不刷新直接返回当前 token/uid
     *
     * @param maxAgeHours 最大允许年龄（小时），默认 24
     */
    @JvmStatic
    suspend fun refreshTokenIfNeed(context: Context, maxAgeHours: Long = 24): TokenUid {
        val p = getOrInitProvider(context)
        val maxAgeMillis = maxAgeHours * 60 * 60 * 1000
        AppLogger.i(TAG, "外部请求：refreshTokenIfNeed 检查是否需要刷新（maxAgeHours=$maxAgeHours）。")
        val result = p.refreshTokenIfNeed(maxAgeMillis)
        AppLogger.i(TAG, "外部请求：refreshTokenIfNeed 完成。token=${LogMask.maskToken(result.token)}, uid=${LogMask.maskUid(result.uid)}")
        return result
    }

    /**
     * （可选）阻塞版本：按需刷新。避免在主线程调用。
     */
    @JvmStatic
    fun refreshTokenIfNeedBlocking(context: Context, maxAgeHours: Long = 24): TokenUid {
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            AppLogger.w(TAG, "refreshTokenIfNeedBlocking 在主线程调用，可能引发 ANR。建议使用协程版本 refreshTokenIfNeed(context)。")
        }
        return runBlocking {
            refreshTokenIfNeed(context, maxAgeHours)
        }
    }
}