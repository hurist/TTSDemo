package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

class SynthesisStrategyManager(context: Context) {
    private val networkMonitor = NetworkMonitor(context.applicationContext)
    var currentStrategy: TtsStrategy = TtsStrategy.ONLINE_ONLY
        private set

    val isNetworkGood: StateFlow<Boolean> = networkMonitor.isNetworkGood

    fun setStrategy(strategy: TtsStrategy) {
        this.currentStrategy = strategy
    }

    fun getDesiredMode(): SynthesisMode {
        return when (currentStrategy) {
            TtsStrategy.OFFLINE_ONLY -> SynthesisMode.OFFLINE
            TtsStrategy.ONLINE_ONLY -> SynthesisMode.ONLINE
            TtsStrategy.ONLINE_PREFERRED -> {
                if (isNetworkGood.value) SynthesisMode.ONLINE else SynthesisMode.OFFLINE
            }
        }
    }

    fun release() {
        networkMonitor.release()
    }
}

// 定义一个简单的枚举来代表单次会话的合成模式
enum class SynthesisMode {
    ONLINE, OFFLINE
}