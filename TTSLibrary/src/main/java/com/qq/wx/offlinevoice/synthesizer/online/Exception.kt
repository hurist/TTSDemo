package com.qq.wx.offlinevoice.synthesizer.online

import com.qq.wx.offlinevoice.synthesizer.ErrCode

open class WxApiException(
    val errorCode: Int,
    override val message: String?,
) : Exception(message) {
    val isTokenInvalid: Boolean
        get() = errorCode == ErrCode.INVALID_TOKEN
}