package com.qq.wx.offlinevoice.synthesizer

import java.io.File

fun File.clearDirectoryFunctional(): Boolean {
    if (!isDirectory) return false

    // 从下到上遍历所有子孙节点（但不包括自己）
    // 通过 filter 过滤掉根目录本身
    // 然后对每一个节点执行删除
    return this.walkBottomUp()
        .filter { it != this }
        .all { it.delete() } // This part might not work as expected because deletion changes the sequence
}