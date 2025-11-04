package com.qq.wx.offlinevoice.synthesizer

enum class SentenceSplitterStrategy {
    // 简单按换行符分割
    NEWLINE,
    // 基于常见标点符号的分割
    PUNCTUATION,
}