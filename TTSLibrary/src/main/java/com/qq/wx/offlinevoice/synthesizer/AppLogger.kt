package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AppLogger：应用级日志工具，用于替换 android.util.Log 的直接调用。
 *
 * 功能概述
 * - 可控输出：
 *   - 可全局开关日志打印（enabled）
 *   - 可分别开关控制台(Logcat)打印与文件输出
 *   - 可配置最小日志级别（低于该级别的日志将被过滤）
 * - 文件输出：
 *   - 支持将日志异步写入文件（单独后台线程），避免阻塞主线程
 *   - 每次冷启动生成一个全新的日志文件（文件名包含日期与进程ID）
 *   - 支持程序退出时安全 flush/关闭（可在 Application.onTerminate/进程结束时调用 shutdown）
 * - 易迁移：
 *   - 提供与 Log 相同语义的方法：v/d/i/w/e/wtf(tag, msg[, tr])
 *   - 同时提供懒加载版本 v/d/i/w/e/wtf(tag) { msg }，避免在日志被过滤时仍计算字符串的开销
 *
 * 使用方式（建议在 Application.onCreate 初始化）
 *   AppLogger.initialize(
 *     context = appContext,
 *     config = AppLogger.Config(
 *       enableConsole = true,
 *       enableFile = true,
 *       minLevel = AppLogger.Level.DEBUG,
 *       logDir = null, // 默认使用 context.filesDir/logs
 *       tagPrefix = "APP", // 统一前缀，可选
 *       includeThread = true,
 *       includeProcessId = true
 *     )
 *   )
 *
 *   // 之后可直接调用
 *   AppLogger.d("TtsSynthesizer", "启动合成")
 *   AppLogger.e("AudioPlayer", "AudioTrack 写入错误", throwable)
 *
 *   // 懒加载版本，只有在满足级别/开关时才执行 lambda 构建字符串
 *   AppLogger.d("TtsSynthesizer") { "处理合成位置 index=$index sentence=${sentence.take(32)}..." }
 *
 * 运行时动态调整（可选）
 *   AppLogger.setEnabled(true/false)
 *   AppLogger.setConsoleEnabled(true/false)
 *   AppLogger.setFileEnabled(true/false)
 *   AppLogger.setMinLevel(AppLogger.Level.INFO)
 *
 * 获取日志文件（例如用于分享/上报）
 *   val file = AppLogger.getCurrentLogFile()
 *
 * 进程退出时（可选但推荐）
 *   AppLogger.shutdown() // flush 并释放 writer
 */
object AppLogger {

    interface Callback {
        fun onLogWritten(level: Level, tag: String, msg: String)
    }

    // ---------------- 配置与级别 ----------------
    enum class Level(val priority: Int) {
        VERBOSE(2),
        DEBUG(3),
        INFO(4),
        WARN(5),
        ERROR(6),
        WTF(7),
        NONE(99); // NONE 表示完全不输出
    }

    data class Config(
        val enableConsole: Boolean = true,
        val enableFile: Boolean = true,
        val minLevel: Level = Level.DEBUG,
        val logDir: File? = null, // 默认 context.filesDir/logs
        val tagPrefix: String = "TTSLib",
        val includeThread: Boolean = true,
        val includeProcessId: Boolean = false,
        val timeFormat: String = "yyyy-MM-dd HH:mm:ss.SSS",
        val fileChannelCapacity: Int = 2048, // 文件写入通道容量，过大可能占用内存，过小可能丢日志
        val autoHookUncaughtException: Boolean = true // 可选：自动捕获未处理异常并落盘
    )


    @Volatile
    private var initialized = false
    @Volatile
    private var enabled = true
    @Volatile
    private var consoleEnabled = true
    @Volatile
    private var fileEnabled = false
    @Volatile
    private var minLevel: Level = Level.DEBUG
    @Volatile
    private var tagPrefix: String = ""
    @Volatile
    private var includeThread: Boolean = true
    @Volatile
    private var includeProcessId: Boolean = false

    private var callback: Callback? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private fun formatNow(fmt: String? = null): String {
        if (fmt == null) synchronized(dateFormat) { return dateFormat.format(Date()) }
        val df = SimpleDateFormat(fmt, Locale.US)
        return df.format(Date())
    }

    // ---------------- 文件与后台写入 ----------------

    private var logDir: File? = null
    private var logFile: File? = null
    private var writer: BufferedWriter? = null
    private var writerScope: CoroutineScope? = null
    private var writerJob: Job? = null
    private var lineChannel: Channel<String>? = null
    private val shuttingDown = AtomicBoolean(false)
    private var channelCapacity: Int = 2048
    private var timeFormatPattern: String = "yyyy-MM-dd HH:mm:ss.SSS"

    // 丢弃计数（通道满时）
    @Volatile
    private var droppedCount: Long = 0

    // ---------------- 初始化 / 销毁 ----------------

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    @Synchronized
    fun initialize(context: Context, config: Config = Config()) {
        if (initialized) return

        enabled = true
        consoleEnabled = config.enableConsole
        fileEnabled = config.enableFile
        minLevel = config.minLevel
        tagPrefix = config.tagPrefix
        includeThread = config.includeThread
        includeProcessId = config.includeProcessId
        timeFormatPattern = config.timeFormat
        channelCapacity = config.fileChannelCapacity

        // 日志目录：默认 /data/data/<pkg>/files/logs
        val baseDir = config.logDir ?: File(context.externalCacheDir, "logs")
        if (!baseDir.exists()) baseDir.mkdirs()
        logDir = baseDir

        // 每次冷启动生成一个全新文件：app-YYYYMMDD-HHMMSS-p<process>.log
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val pid = Process.myPid()
        val fileName = "app-$timestamp-p$pid.log"
        logFile = File(baseDir, fileName)

        try {
            writer = BufferedWriter(FileWriter(logFile, /* append = */ false))
        } catch (e: Exception) {
            // 如果文件创建失败，降级为仅控制台
            fileEnabled = false
            Log.e("AppLogger", "无法创建日志文件，已降级为仅控制台: ${e.message}")
        }

        // 启动单线程后台协程用于写文件
        if (fileEnabled && writer != null) {
            writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            lineChannel = Channel(capacity = channelCapacity)
            writerJob = writerScope?.launch {
                try {
                    val w = writer!!
                    var written = 0
                    for (line in lineChannel!!) {
                        w.write(line)
                        w.newLine()
                        written++
                        if (written % 20 == 0) {
                            // 周期性 flush，减少磁盘 IO 频率与丢数据风险的折中
                            w.flush()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("AppLogger", "后台写日志异常: ${t.message}", t)
                } finally {
                    try {
                        writer?.flush()
                        writer?.close()
                    } catch (_: Exception) {
                    }
                    writer = null
                }
            }
        }

        // 可选：自动捕获未处理异常，落盘并再抛出
        if (config.autoHookUncaughtException) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    e("Uncaught", "未捕获异常于线程：${t.name}", e)
                    flushNow() // 进程将退出，尽量把日志写完
                } catch (_: Throwable) {
                } finally {
                    defaultHandler?.uncaughtException(t, e)
                }
            }
        }

        initialized = true

        // 启动记录
        i(
            "AppLogger",
            "AppLogger 初始化完成：console=$consoleEnabled, file=$fileEnabled, minLevel=$minLevel, dir=${logDir?.absolutePath}, file=${logFile?.name}"
        )
    }

    /**
     * 主动关闭日志系统（可选但推荐在 Application.onTerminate 或显式退出时调用）。
     * 将尝试 flush 剩余日志并关闭资源。
     */
    @Synchronized
    fun shutdown() {
        if (!initialized || shuttingDown.getAndSet(true)) return
        i("AppLogger", "AppLogger 正在关闭，开始 flush/关闭资源。")
        runCatching { flushNow() }
        lineChannel?.close()
        runCatching { writerJob?.cancel() }
        //runCatching { writerJob?.join() }
        writerJob = null
        writerScope = null
        writer = null
        initialized = false
        shuttingDown.set(false)
        i("AppLogger", "AppLogger 已关闭。")
    }

    // ---------------- 动态配置开关 ----------------

    fun setEnabled(enable: Boolean) {
        enabled = enable
    }

    fun setConsoleEnabled(enable: Boolean) {
        consoleEnabled = enable
    }

    fun setFileEnabled(enable: Boolean) {
        fileEnabled = enable
    }

    fun setMinLevel(level: Level) {
        minLevel = level
    }

    // ---------------- 对外获取信息 ----------------

    fun getLogDirectory(): File? = logDir
    fun getCurrentLogFile(): File? = logFile

    // ---------------- 便捷 API（与 Log.* 对齐） ----------------

    fun v(tag: String, msg: String, tr: Throwable? = null, important: Boolean = false) =
        log(Level.VERBOSE, tag, msg, tr, important)

    fun d(tag: String, msg: String, tr: Throwable? = null, important: Boolean = false) =
        log(Level.DEBUG, tag, msg, tr, important)

    fun i(tag: String, msg: String, tr: Throwable? = null, important: Boolean = false) =
        log(Level.INFO, tag, msg, tr, important)

    fun w(tag: String, msg: String, tr: Throwable? = null, important: Boolean = false) =
        log(Level.WARN, tag, msg, tr, important)

    fun e(tag: String, msg: String, tr: Throwable? = null, important: Boolean = false) =
        log(Level.ERROR, tag, msg, tr, important)

    fun wtf(tag: String, msg: String, tr: Throwable? = null, important: Boolean = false) =
        log(Level.WTF, tag, msg, tr, important)

    // 懒加载消息版本（避免在被过滤时计算字符串开销）
    inline fun v(tag: String, important: Boolean = false, msg: () -> String) {
        if (shouldLog(Level.VERBOSE)) v(tag, msg(), important = important)
    }

    inline fun d(tag: String, important: Boolean = false, msg: () -> String) {
        if (shouldLog(Level.DEBUG)) d(tag, msg(), important = important)
    }

    inline fun i(tag: String, important: Boolean = false, msg: () -> String) {
        if (shouldLog(Level.INFO)) i(tag, msg(), important = important)
    }

    inline fun w(tag: String, important: Boolean = false, msg: () -> String) {
        if (shouldLog(Level.WARN)) w(tag, msg(), important = important)
    }

    inline fun e(tag: String, important: Boolean = false, msg: () -> String) {
        if (shouldLog(Level.ERROR)) e(tag, msg(), important = important)
    }

    inline fun wtf(tag: String, important: Boolean = false, msg: () -> String) {
        if (shouldLog(Level.WTF)) wtf(tag, msg(), important = important)
    }

    // ---------------- 核心实现 ----------------

    fun shouldLog(level: Level): Boolean {
        if (!enabled) return false
        if (level.priority < minLevel.priority) return false
        if (!consoleEnabled && !fileEnabled) return false
        return true
    }

    private fun log(
        level: Level,
        rawTag: String,
        msg: String,
        tr: Throwable? = null,
        important: Boolean = false
    ) {
        if (!shouldLog(level)) return

        val tag = if (tagPrefix.isNotEmpty()) "$tagPrefix-$rawTag" else rawTag
        val threadInfo = if (includeThread) " [${Thread.currentThread().name}]" else ""
        val pidInfo = if (includeProcessId) " (pid=${Process.myPid()})" else ""
        val timeStr = try {
            formatNow(timeFormatPattern)
        } catch (_: Throwable) {
            formatNow()
        }

        val base = "$timeStr | ${level.name.first()} | $tag$threadInfo$pidInfo | $msg"
        val throwableStr = tr?.let { "\n${getStackTraceString(it)}" } ?: ""

        // 控制台输出（按级别调用原生 Log，便于过滤）
        if (consoleEnabled) {
            when (level) {
                Level.VERBOSE -> Log.v(tag, msg, tr)
                Level.DEBUG -> Log.d(tag, msg, tr)
                Level.INFO -> Log.i(tag, msg, tr)
                Level.WARN -> Log.w(tag, msg, tr)
                Level.ERROR -> Log.e(tag, msg, tr)
                Level.WTF -> Log.wtf(tag, msg, tr)
                Level.NONE -> { /* no-op */
                }
            }
        }

        if (important) {
            // 重要日志通过回调通知（可用于上报等）
            try {
                callback?.onLogWritten(level, tag, msg)
            } catch (_: Throwable) {
            }
        }

        // 文件输出（异步）
        if (fileEnabled && writer != null && lineChannel != null) {
            val line = base + throwableStr
            val sent = lineChannel!!.trySend(line)
            if (!sent.isSuccess) {
                // 通道满时丢弃，并周期性提示
                val dropped = ++droppedCount
                if (dropped % 100L == 1L) {
                    Log.w(
                        "AppLogger",
                        "写日志通道已满，已丢弃 $dropped 条日志。可增大 fileChannelCapacity 或减少日志量。"
                    )
                }
            }
        }
    }

    private fun flushNow() {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    private fun getStackTraceString(tr: Throwable): String {
        return try {
            val sw = StringWriter()
            tr.printStackTrace(PrintWriter(sw))
            sw.toString()
        } catch (e: Exception) {
            "获取异常栈失败: ${e.message}"
        }
    }
}