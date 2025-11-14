package com.hurist.ttsdemo

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qq.wx.offlinevoice.synthesizer.Level

/**
 * 一个高性能的日志显示控件，基于 RecyclerView 实现。
 *
 * 功能特性：
 * 1. 高效显示大量日志，不会因日志增多而卡顿或内存溢出。
 * 2. 新日志出现时，如果视图已在底部，则自动滚动。
 * 3. 如果用户手动向上滚动，则停止自动滚动。
 * 4. 当用户再次手动滚动回底部时，恢复自动滚动。
 */
class LogRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.recyclerview.R.attr.recyclerViewStyle
) : RecyclerView(context, attrs, defStyleAttr) {

    private val logAdapter: LogAdapter
    // 1. 设置布局管理器
    private val linearLayoutManager: LinearLayoutManager = LinearLayoutManager(context)

    // 状态标志位，用于判断是否应该启用自动滚动
    private var isAutoScrollEnabled = true

    init {
        this.layoutManager = linearLayoutManager

        // 2. 设置适配器
        logAdapter = LogAdapter()
        this.adapter = logAdapter
        
        // 3. 添加滚动监听，实现条件自动滚动的核心逻辑
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                // 当滚动停止时，再次检查是否在底部
                if (newState == SCROLL_STATE_IDLE) {
                    // canScrollVertically(1) 表示是否能向上滚动内容
                    // 如果不能，说明已经到达了最底部
                    isAutoScrollEnabled = !recyclerView.canScrollVertically(1)
                }
            }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // dy < 0 表示用户正在向上滑动（内容向下滚动），此时应禁用自动滚动
                if (dy < 0) {
                    isAutoScrollEnabled = false
                }
            }
        })
    }

    /**
     * 公开方法：向日志视图添加一条新日志。
     */
    fun addLog(level: Level, message: String) {
        post {
            logAdapter.addLog(Log(level, message))
            if (isAutoScrollEnabled) {
                scrollToBottom()
            }
        }
    }

    /**
     * 公开方法：清空所有日志。
     */
    fun clearLogs() {
        post {
            logAdapter.clearLogs()
        }
    }

    /**
     * 强制滚动到底部。
     */
    fun scrollToBottom() {
        post {
            val itemCount = logAdapter.itemCount
            if (itemCount > 0) {
                smoothScrollToPosition(itemCount - 1)
            }
        }
    }

    // ===================================================================================
    //  内部适配器和 ViewHolder，对外部完全隐藏
    // ===================================================================================

    private class LogAdapter : Adapter<LogAdapter.LogViewHolder>() {

        private val logs = mutableListOf<Log>()
        private val colorMap = mapOf(
            Level.VERBOSE to 0xFF888888.toInt(), // 灰 - 次要信息
            Level.DEBUG to 0xFF4CAF50.toInt(),   // 绿 - 调试信息
            Level.INFO to 0xFF2196F3.toInt(),    // 蓝 - 正常信息
            Level.WARN to 0xFFFFC107.toInt(),    // 黄 - 警告
            Level.ERROR to 0xFFF44336.toInt(),   // 红 - 错误
            Level.WTF to 0xFFE91E63.toInt(),     // 品红 - 致命错误
            Level.NONE to 0xFF000000.toInt()     // 黑 - 默认
        )

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false) // 使用我们创建的 item_log.xml
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(logs[position])
        }

        override fun getItemCount(): Int = logs.size

        fun addLog(log: Log) {
            logs.add(log)
            notifyItemInserted(logs.size - 1)
        }

        fun clearLogs() {
            val size = logs.size
            if (size > 0) {
                logs.clear()
                notifyItemRangeRemoved(0, size)
            }
        }

        inner class LogViewHolder(itemView: View) : ViewHolder(itemView) {
            private val logTextView: TextView = itemView.findViewById(R.id.log_text)
            fun bind(log: Log) {
                logTextView.text = log.message
                // 设置成同的颜色根据日志级别
                logTextView.setTextColor(
                    colorMap[log.level] ?: 0xFFFFFFFF.toInt()
                )
            }
        }
    }

    data class Log(
        val level: Level,
        val message: String
    )
}