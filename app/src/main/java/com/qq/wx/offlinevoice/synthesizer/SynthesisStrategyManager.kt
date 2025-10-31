package com.qq.wx.offlinevoice.synthesizer

import android.content.Context

class SynthesisStrategyManager(context: Context) {
    private val networkMonitor = NetworkMonitor(context)
    var currentStrategy: TtsStrategy = TtsStrategy.ONLINE_PREFERRED
        private set

    fun setStrategy(strategy: TtsStrategy) {
        this.currentStrategy = strategy
    }

    /**
     * 根据当前策略和网络状态，决定本次 speak() 会话的主要合成模式
     */
    fun selectSessionSource(): SynthesisMode {
        return when (currentStrategy) {
            TtsStrategy.OFFLINE_ONLY -> SynthesisMode.OFFLINE
            TtsStrategy.ONLINE_ONLY -> SynthesisMode.ONLINE
            TtsStrategy.ONLINE_PREFERRED -> {
                if (networkMonitor.isNetworkGood.value) {
                    SynthesisMode.ONLINE
                } else {
                    SynthesisMode.OFFLINE
                }
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