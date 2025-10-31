// In a new file, e.g., TtsStrategy.kt
package com.qq.wx.offlinevoice.synthesizer

enum class TtsStrategy {
    OFFLINE_ONLY,     // 只使用离线
    ONLINE_PREFERRED, // 网络良好时优先使用在线，否则回退到离线
    ONLINE_ONLY       // 只使用在线（可能会因网络问题失败）
}