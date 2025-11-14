package com.qq.wx.offlinevoice.synthesizer.online.token

import android.content.Context
import android.content.SharedPreferences
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.online.LogMask
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient

/**
 * TokenProvider
 *
 * 职责：
 * - 持有当前 token/uid 的“内存态”（与 SharedPreferences 持久化一致）
 * - 在检测到 TTS 返回 -13 时，进行“单飞刷新”（SingleFlight），避免并发重复刷新
 * - 提供预取能力（可选使用），以及对日志进行脱敏
 *
 * 设计要点：
 * - 线程安全：通过 in-flight Deferred 协调并发刷新，只有一次网络调用，其它并发请求 await 同一个结果
 * - 可观测性：详细日志，避免敏感信息泄露
 * - 与 WxReaderApi 保持兼容：使用相同的 SharedPreferences key（"token", "uid"）
 */
class TokenProvider(
    private val context: Context,
    private val client: OkHttpClient,
    private val sp: SharedPreferences
) {

    private val TAG = "TokenProvider"

    // 当前内存态（启动时从 SP 初始化）
    @Volatile private var currentToken: String =
        sp.getString(KEY_TOKEN, "") ?: ""
    @Volatile private var currentUid: Long =
        sp.getLong(KEY_UID, -1)
    // 最后一次刷新时间（内存缓存），0 表示未知或未刷新过，会在 refreshTokenIfNeed 中触发刷新
    @Volatile private var lastRefreshedTimeMillis: Long =
        sp.getLong(KEY_LAST_REFRESHED, 0L)


    // 单飞中的刷新任务；并发时共享此任务，避免“惊群”
    @Volatile private var inFlightRefresh: CompletableDeferred<TokenUid>? = null

    // 远端数据源（使用同一连接池，但缩短整体超时，避免拖慢 TTS 重试）
    private val remote by lazy {
        TokenRemoteDataSource(
            client = client.newBuilder()
                .callTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofSeconds(8))
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build(),
            baseUrl = "http://192.168.1.212:8866"
        )
    }

    /**
     * 返回当前内存态 token/uid（不触发网络）
     */
    fun current(): TokenUid = TokenUid(currentToken, currentUid)

    /**
     * 手动设置 token/uid（例如设置页或 WxReaderApi.setToken 调用）
     * - 立即更新内存态与 SP，事件最终一致
     */
    fun setManualToken(token: String, uid: Long) {
        currentToken = token
        currentUid = uid
        val now = System.currentTimeMillis()
        lastRefreshedTimeMillis = now
        sp.edit().apply {
            putString(KEY_TOKEN, token)
            putLong(KEY_UID, uid)
            putLong(KEY_LAST_REFRESHED, now)
            apply()
        }
        AppLogger.i(TAG, "setManualToken: 已更新内存与本地存储，token=${LogMask.maskToken(token)}, uid=${LogMask.maskUid(uid)}, lastRefreshed=${now}")
    }

    /**
     * 单飞刷新：并发情况下，只有一次网络请求，其它协程等待相同结果。
     * - 成功：更新内存态 + SP，返回新的 TokenUid
     * - 失败：抛出异常，等待者同样感知失败
     */
    suspend fun refreshTokensSingleFlight(): TokenUid {
        // 如果已有刷新在进行，直接等待其结果
        inFlightRefresh?.let { existing ->
            if (!existing.isCompleted) {
                AppLogger.d(TAG, "已有刷新任务在进行，等待其完成。")
                return existing.await()
            }
        }

        // 创建新的刷新任务（注意：这里不使用 GlobalScope，函数本身是 suspend，在调用栈内执行联网）
        val deferred = CompletableDeferred<TokenUid>()
        inFlightRefresh = deferred
        try {
            AppLogger.i(TAG, "开始从令牌服务刷新 token（单飞）。")
            val remoteToken = remote.fetchLatestToken()
            // 更新内存态与本地持久化
            currentToken = remoteToken.token
            currentUid = remoteToken.uid
            val now = System.currentTimeMillis()
            lastRefreshedTimeMillis = now
            sp.edit().apply {
                putString(KEY_TOKEN, currentToken)
                putLong(KEY_UID, currentUid)
                putLong(KEY_LAST_REFRESHED, now)
                apply()
            }
            AppLogger.i(
                TAG,
                "刷新成功：token=${LogMask.maskToken(currentToken)}, uid=${LogMask.maskUid(currentUid)}, lastRefreshed=$now（已写入本地）"
            )
            deferred.complete(TokenUid(currentToken, currentUid))
            return deferred.await()
        } catch (e: Exception) {
            AppLogger.e(TAG, "刷新失败：${e.message}", e)
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlightRefresh = null
        }
    }

    /**
     * 预取（可选使用）：如果本地 token 为空或上次校验时间过久，可调用此方法提前更新。
     * - 该方法不在 WxReaderApi 中强制调用，避免在构造期间触发网络。
     */
    suspend fun prefetchIfStale(): Boolean {
        return try {
            if (currentToken.isBlank() || currentUid <= 0) {
                AppLogger.w(TAG, "检测到本地 token/uid 可能为空，尝试预取。")
                refreshTokensSingleFlight()
                true
            } else {
                AppLogger.d(TAG, "本地 token/uid 非空，跳过预取。")
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "预取失败：${e.message}", e)
            false
        }
    }


    /**
     * 按需刷新：
     * - 条件 1：token 为空或 uid 非法（<=0）
     * - 条件 2：距离 lastRefreshedTimeMillis 超过 maxAgeMillis
     * - 条件 3：系统时间回拨（当前时间 < lastRefreshedTimeMillis）也视为需要刷新
     *
     * 若不满足上述条件，则直接返回当前内存态，不触发网络。
     * 返回：最终的 TokenUid（可能是原值，也可能是刷新后的）
     */
    suspend fun refreshTokenIfNeed(maxAgeMillis: Long): TokenUid {
        val now = System.currentTimeMillis()
        val tokenBlank = currentToken.isBlank()
        val uidInvalid = currentUid <= 0
        val age = now - lastRefreshedTimeMillis
        val timeWentBack = now < lastRefreshedTimeMillis

        AppLogger.d(
            TAG,
            "refreshTokenIfNeed: 检查条件 tokenBlank=$tokenBlank, uidInvalid=$uidInvalid, age=$age, maxAge=$maxAgeMillis, timeWentBack=$timeWentBack, lastRefreshed=$lastRefreshedTimeMillis"
        )

        if (tokenBlank || uidInvalid) {
            AppLogger.w(TAG, "refreshTokenIfNeed: 本地 token/uid 不可用，触发刷新。tokenBlank=$tokenBlank, uidInvalid=$uidInvalid")
            return refreshTokensSingleFlight()
        }

        if (timeWentBack) {
            AppLogger.w(TAG, "refreshTokenIfNeed: 检测到系统时间回拨（now < lastRefreshed），触发刷新。")
            return refreshTokensSingleFlight()
        }

        if (lastRefreshedTimeMillis == 0L) {
            AppLogger.w(TAG, "refreshTokenIfNeed: lastRefreshed 为 0（尚未刷新过），触发刷新。")
            return refreshTokensSingleFlight()
        }

        if (age > maxAgeMillis) {
            AppLogger.i(TAG, "refreshTokenIfNeed: token 已超过最大年龄（age=$age > maxAgeMillis=$maxAgeMillis），触发刷新。")
            return refreshTokensSingleFlight()
        }

        AppLogger.d(
            TAG,
            "refreshTokenIfNeed: 不需要刷新，直接返回当前 token。token=${LogMask.maskToken(currentToken)}, uid=${LogMask.maskUid(currentUid)}"
        )
        return TokenUid(currentToken, currentUid)
    }
}

/**
 * 便于跨模块传递的简单模型
 */
data class TokenUid(
    val token: String,
    val uid: Long
)

internal const val WX_SP_NAME = "wx_tts_prefs"
internal const val KEY_TOKEN = "token"
internal const val KEY_UID = "uid"
internal const val KEY_LAST_REFRESHED = "token_last_refreshed"