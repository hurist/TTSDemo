package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.StateFlow

class SynthesisStrategyManager(context: Context) {
    private val networkMonitor = NetworkMonitor(context.applicationContext)

    // 默认策略可以根据您的产品需求设定
    var currentStrategy: TtsStrategy = TtsStrategy.ONLINE_PREFERRED
        private set

    val isNetworkGood: StateFlow<Boolean> = networkMonitor.isNetworkGood

    fun setStrategy(strategy: TtsStrategy) {
        Log.i("StrategyManager", "TTS 策略已变更为: $strategy")
        this.currentStrategy = strategy
    }

    /**
     * --- 修改：让 getDesiredMode 成为一个纯函数 ---
     * 根据给定的策略和当前的网络状态，决定理想的合成模式。
     * 这使得 TtsSynthesizer 中的逻辑更可预测。
     *
     * @param strategy 要评估的TTS策略。
     * @return 理想的合成模式 (ONLINE 或 OFFLINE)。
     */
    fun getDesiredMode(strategy: TtsStrategy): SynthesisMode {
        return when (strategy) {
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