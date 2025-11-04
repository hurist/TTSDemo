package com.qq.wx.offlinevoice.synthesizer

/**
 * 表示文本中的一个句子区间。
 */
data class BagRange(
    val start: Int,
    val end: Int,
    val type: Int,
    val flag: Boolean
)
