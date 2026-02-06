package com.qq.wx.offlinevoice.synthesizer

object ErrCode {

    const val INVALID_TOKEN = -13

    /** 本地模型不存在  */
    const val MODEL_NOT_FOUND = 1

    /**
     * 文本为空
     */
    const val EMPTY_TEXT = 2

    /**
     * 在线合成其他错误
     */
    const val ONLINE_OTHER_ERR = 3

    /**
     * 离线合成被取消
     */
    const val OFFLINE_CANCELLED = 4

    /**
     * 离线合成其他错误
     */
    const val OFFLINE_OTHER_ERR = 5

    /**
     * 未知错误
     */
    const val UNKNOW_ERR = 6


    /**
     * 微信错误
     */
    const val WX_ERR = 100

}