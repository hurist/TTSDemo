package com.qq.wx.offlinevoice.synthesizer.online

open class WxApiException(
    val errorCode: Int,
    override val message: String?,
) : Exception(message)

class SessionExpiredException(
    override val message: String? = "Session has expired."
) : Exception(message)