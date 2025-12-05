package com.qq.wx.offlinevoice.synthesizer.preload

import android.content.Context
import androidx.annotation.Keep
import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.NetworkMonitor
import com.qq.wx.offlinevoice.synthesizer.SentenceSplitterStrategy
import com.qq.wx.offlinevoice.synthesizer.Speaker
import com.qq.wx.offlinevoice.synthesizer.TtsRepository
import com.qq.wx.offlinevoice.synthesizer.cache.TtsCache
import com.qq.wx.offlinevoice.synthesizer.online.MediaCodecMp3Decoder
import com.qq.wx.offlinevoice.synthesizer.online.WxReaderApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Collections

class PreloadManager private constructor(
    context: Context,
    private val config: Config // 配置仍然是构造函数的一部分
) {
    @Keep
    data class Config(
        val maxJobs: Int = 5,
        val defaultConcurrencyLimit: Int = 3
    )

    companion object {
        private const val TAG = "PreloadManager"

        @Volatile
        private var INSTANCE: PreloadManager? = null

        /**
         * 必须在 Application.onCreate() 中调用此方法来初始化 PreloadManager。
         * 此方法只能调用一次。
         * @param context Application context.
         * @param config 自定义配置。
         */
        @JvmStatic
        fun initialize(context: Context, config: Config = Config()) {
            synchronized(this) {
                if (INSTANCE == null) {
                    INSTANCE = PreloadManager(context.applicationContext, config)
                    AppLogger.i(TAG, "PreloadManager initialized with config: $config")
                } else {
                    AppLogger.w(TAG, "PreloadManager has already been initialized. Ignoring subsequent initialize call.")
                }
            }
        }

        /**
         * 获取 PreloadManager 的单例实例。
         * 必须先调用 initialize() 方法，否则将使用默认配置创建实例并打印警告。
         * @param context Context.
         * @return PreloadManager 实例。
         */
        @JvmStatic
        fun getInstance(context: Context): PreloadManager {
            // 双重检查锁定，保证性能和线程安全
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    // 如果代码执行到这里，说明 initialize() 从未被调用过
                    AppLogger.w(TAG, "PreloadManager.initialize() was not called. Initializing with default config. " +
                            "It is highly recommended to call initialize() in your Application class.")
                    // 使用默认配置进行回退初始化
                    val defaultConfig = Config()
                    PreloadManager(context.applicationContext, defaultConfig).also { INSTANCE = it }
                }
            }
        }
    }


    private val networkMonitor = NetworkMonitor(context)
    private val scope = CoroutineScope(SupervisorJob())
    private val ttsRepository = TtsRepository(
        WxReaderApi(context),
        MediaCodecMp3Decoder(context),
        TtsCache.getInstance(context),
        networkMonitor
    )

    // 优化点 1: 使用同步的 LinkedHashMap 来保证线程安全和插入顺序 (FIFO)
    private val jobs: MutableMap<String, PreloadJob> =
        Collections.synchronizedMap(LinkedHashMap<String, PreloadJob>())

    init {
        var lastNetworkStatus = networkMonitor.isNetworkGood.value
        networkMonitor.isNetworkGood.onEach { isGood ->
            AppLogger.d(TAG, "网络状态变化，当前网络良好: $isGood")
            // 只有当网络从差变好时才触发
            if (!lastNetworkStatus && isGood) {
                AppLogger.d(TAG, "网络恢复良好，尝试重启所有未完成的预加载任务。")
                // 创建一个快照以安全遍历
                val pendingJobs = synchronized(jobs) { jobs.values.toList() }
                pendingJobs.forEach { job ->
                    // job.start() 现在是可重入的，会处理之前失败的任务
                    job.start()
                }
            }
            lastNetworkStatus = isGood
        }.launchIn(scope)
    }

    @JvmOverloads
    fun preload(
        content: String,
        speaker: Speaker,
        isTtsTextTraditional: Boolean = false,
        sentenceSplitterStrategy: SentenceSplitterStrategy = SentenceSplitterStrategy.NEWLINE,
        onCompletion: ((Result<Unit>) -> Unit)? = null
    ) {
        val id = createCacheKey(content, speaker)
        if (jobs.containsKey(id)) {
            AppLogger.d(TAG, "预加载任务已存在，跳过重复预加载。key=$id")
            onCompletion?.invoke(Result.success(Unit))
            return
        }

        checkAndEvictOldestJob()

        val job = PreloadJob(
            ttsRepository = ttsRepository,
            networkMonitor = networkMonitor, // 将 networkMonitor 传入 Job
            content = content,
            speaker = speaker,
            splitterStrategy = sentenceSplitterStrategy,
            concurrencyLimit = config.defaultConcurrencyLimit,
            isTtsTextTraditional = isTtsTextTraditional,
            onCompletion = { result ->
                // 当任务完全成功或被取消时，从 map 中移除
                jobs.remove(id)
                AppLogger.d(TAG, "预加载任务最终完成，已移除。key=$id, result=$result")
                onCompletion?.invoke(result)
            }
        )

        jobs[id] = job
        job.start() // 首次启动
        AppLogger.d(TAG, "开始预加载。key=$id, text=${content.take(20)}")
    }

    fun cancel(content: String, speaker: Speaker) {
        val id = createCacheKey(content, speaker)
        val jobToCancel = synchronized(jobs) { jobs.remove(id) }
        jobToCancel?.cancel("手动取消")
        AppLogger.d(TAG, "手动取消预加载任务。key=$id")
    }

    fun cancelAll() {
        val jobsToCancel = synchronized(jobs) {
            val list = jobs.values.toList()
            jobs.clear()
            list
        }
        jobsToCancel.forEach { it.cancel("手动取消所有任务") }
        AppLogger.d(TAG, "已取消所有预加载任务。")
    }

    private fun checkAndEvictOldestJob() {
        // 必须在同步块内执行检查和移除操作，保证原子性
        synchronized(jobs) {
            if (jobs.size >= config.maxJobs) {
                // LinkedHashMap 的迭代器会按插入顺序返回元素
                val oldestKey = jobs.keys.iterator().next()
                val oldestJob = jobs.remove(oldestKey)
                oldestJob?.cancel("超出预加载任务限制，移除最老任务")
                AppLogger.d(TAG, "预加载任务超过限制，严格移除最老的任务。key=$oldestKey")
            }
        }
    }

    private fun createCacheKey(text: String, speaker: Speaker): String {
        return TtsRepository.createCacheKey(text, speaker)
    }
}