package com.qq.wx.offlinevoice.synthesizer

fun String.isOnlyPunctuationOrEmpty(): Boolean {
    if (this.trim().isEmpty()) return true

    // 只要包含至少一个字母或数字，就不是纯符号
    return this.none { it.isLetterOrDigit() }
}
private val charMap = mapOf(
    "「" to "“",
    "」" to "”",
    "『" to "“",
    "』" to "”",
)

private val specialRegex = Regex(charMap.keys.joinToString(prefix = "[", postfix = "]") { Regex.escape(it) })

fun String.replaceSpecialChar(): String {
    val content = replace(specialRegex) { matchResult ->
        charMap[matchResult.value] ?: matchResult.value
    }
    return replaceSpecialCharInternal(content)
}

/**
 * 这个是从微信里面反编译出来的代码，作用是替换掉一些特殊字符
 */
private fun replaceSpecialCharInternal(text: String): String {
    // 对应原代码中的 StringsKt.f0(text) -> trim()
    // StringPool.RETURN -> "\r"
    // 逻辑：trim -> 把 "\r" 换成空格 -> 把 "\n" 删掉
    val temp = text.trim { it <= ' ' }.replace("\r", " ").replace("\n", "")

    val sb = StringBuilder(temp)

    for (i in 0..<sb.length) {
        val c = sb.get(i)
        // 范围 57344 (0xE000) 到 63490 (0xF802) 属于 Unicode 私人使用区 (Private Use Area)
        // 12288 (0x3000) 是全角空格 (Ideographic Space)
        // 将这些特殊字符统一替换为普通空格
        if ((c.code in 57344..<63490) || c.code == 12288) {
            sb.setCharAt(i, ' ')
        }
    }

    return sb.toString()
}

/**
 * 将多个连续重复的标点符号合并为一个。
 * 重点涵盖了中文标点（全角/半角）、CJK符号以及常规标点。
 *
 * 例：
 * "真的吗？？？！！！" -> "真的吗？！"
 * "你好。。。。" -> "你好。"
 * "太棒了～～～" -> "太棒了～"
 * "……" -> "…" (注意：中文省略号通常是两个字符，这会合并成一个，如需保留六个点请慎用)
 */
fun String.mergeRepeatedPunctuation(): String {
    if (this.isEmpty()) return this

    // 预估容量，避免扩容
    val sb = StringBuilder(this.length)
    var lastChar: Char? = null

    for (c in this) {
        // 核心逻辑：
        // 1. 当前字符等于上一个字符
        // 2. 且当前字符被判定为“中文或常用标点”
        // -> 则跳过（即合并）
        if (c == lastChar && isChineseOrCommonPunctuation(c)) {
            continue
        }

        sb.append(c)
        lastChar = c
    }

    return sb.toString()
}

/**
 * 判断是否为需要合并的中文标点或常用标点
 */
private fun isChineseOrCommonPunctuation(c: Char): Boolean {
    val ub = Character.UnicodeBlock.of(c)

    // 1. 判断 Unicode 区块 (覆盖大部分中文标点)
    if (ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || // 包含 。 、 【 】 《 》
        ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || // 包含 ！ ？ ， ： ； （ ）
        ub == Character.UnicodeBlock.GENERAL_PUNCTUATION ||           // 包含 — … ‘ ’ “ ”
        ub == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS ||       // 包含 ︔ ︩ ︪ (竖排标点等)
        ub == Character.UnicodeBlock.VERTICAL_FORMS) {                // 竖排符号
        return true
    }

    /*// 2. 补充判断：常规 ASCII 标点和特殊符号
    // Character.getType 可以兜底一些没有被 Block 覆盖的情况，或者英文标点
    val type = Character.getType(c)
    return type == Character.CONNECTOR_PUNCTUATION.toInt() ||  // _
            type == Character.DASH_PUNCTUATION.toInt() ||       // -
            type == Character.START_PUNCTUATION.toInt() ||      // ( [ {
            type == Character.END_PUNCTUATION.toInt() ||        // ) ] }
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || // “
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||   // ”
            type == Character.OTHER_PUNCTUATION.toInt() ||      // ! . ?
            c == '~' || c == '=' || c == '+' // 特殊符号，有时会被重复输入*/

    return false
}


/**
 * TTS 文本预处理核心函数。
 * 将原始文本清洗为适合 TTS 引擎朗读的格式。
 *
 * 1. **单次遍历 (One-Pass)**：将字符映射、清洗、去重合并在一个循环中完成，避免多次扫描。
 * 2. **零中间对象**：避免了 split/replace/regex 等产生的中间 String 对象，减轻 GC 压力。
 *
 * 处理逻辑包含：
 * 1. **Trim**：去除首尾空白。
 * 2. **符号替换**：
 *    - 引号统一：将「『 替换为 “，将 」』 替换为 ”。
 *    - 控制符处理：\r 替换为空格，\n 直接丢弃。
 *    - 特殊字符：全角空格(12288) 和 Unicode 私用区字符 替换为普通空格。
 * 3. **标点去重**：连续重复的中文/常用标点（如 "。。。"）合并为一个。
 *
 * @return 清洗后的字符串
 */
fun String.processForTts(): String {
    if (this.isEmpty()) return this

    // 1. 预处理 Trim：去除首尾的空白字符（包括空格、制表符、换行等）
    // 对于短文本，先 trim 开销很小，且能简化后续首尾字符的处理逻辑
    val trimmed = this.trim { it <= ' ' }
    if (trimmed.isEmpty()) return ""

    // 使用 StringBuilder 避免字符串拼接的内存开销，初始容量设为原长度
    val sb = StringBuilder(trimmed.length)
    var lastChar: Char? = null

    // 2. 核心循环：遍历处理每一个字符
    for (i in trimmed.indices) {
        // --- 步骤 A: 字符映射与清洗 (替代原 replaceSpecialChar 逻辑) ---
        val c = when (val originalChar = trimmed[i]) {
            '「', '『' -> '“'      // 统一左引号
            '」', '』' -> '”'      // 统一右引号
            '\r' -> ' '            // 回车符 -> 空格
            '\n' -> continue       // 换行符 -> 直接丢弃 (相当于删除)
            // \u3000 是全角空格 (Ideographic Space)
            // \uE000 - \uF802 是 Unicode 私用区 (Private Use Area)，通常包含系统未定义的图标或乱码
            in '\uE000'..'\uF802', '\u3000' -> ' '
            else -> originalChar   // 其他字符保持不变
        }

        // --- 步骤 B: 标点合并 (替代原 mergeRepeatedPunctuation 逻辑) ---
        // 逻辑：如果当前(处理后的)字符 与 上一个字符相同，且被判定为标点符号，则跳过本次追加
        // 效果： "。。" -> "。"
        if (c == lastChar && isChineseOrCommonPunctuation(c)) {
            continue
        }

        sb.append(c)
        lastChar = c
    }

    return sb.toString()
}