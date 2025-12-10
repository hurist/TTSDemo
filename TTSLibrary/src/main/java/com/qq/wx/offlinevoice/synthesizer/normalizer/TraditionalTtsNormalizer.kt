package com.qq.wx.offlinevoice.synthesizer.normalizer

import java.text.BreakIterator
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * TTS 繁體文本標準化處理器 (Ultimate Edition, Token Merge)
 *
 * - 使用 BreakIterator 做基礎分詞
 * - 滑動窗口「最長優先」合併相鄰 token 命中：
 *   1) RAW_WORD_REPLACEMENTS（命中即替換，長度恆定）
 *   2) ZHU_ALLOW_LIST（命中則保留原樣，避免 fallback 將「著」->「着」）
 * - 不跨越空白/標點/符號等「合併屏障」
 * - 未命中時對單個 token 做「著」兜底與單字異體清洗（長度恆定）
 *
 * 注意：isDebug=true 僅用於開發調試，會在每個輸出單元外加 [ ]，從而打破「長度恆定」原則，請勿用於生產朗讀。
 */
object TraditionalTtsNormalizer {

    // 1) 「著」字讀音保護白名單（讀 zhù）
    private val ZHU_ALLOW_LIST = listOf(
        "著名", "著作", "顯著", "土著", "原著", "名著",
        "專著", "拙著", "遺著", "譯著", "編著", "論著",
        "昭著", "卓著", "著述", "著稱", "著錄", "著書",
        "巨著", "撰著", "較著", "著者", "合著",
        "見微知著", "視微知著", "識微知著", "以微知著", "睹微知著",
        "積微成著", "積微致著", "日新月著", "威望素著",
        "臭名昭著", "劣跡昭著", "惡跡昭著", "昭然著聞", "遐邇著聞",
        "彰明昭著", "彰明較著", "欲蓋彌著", "恩威並著", "深切著明",
        "著作等身", "著述等身", "等身著作",
        "著書立說", "著書立言", "仰屋著書",
        "成效卓著", "功勳卓著", "信譽卓著", "著有成效",
        "超超玄著", "著之竹帛", "著乎竹帛", "著於竹帛"
    )

    // (白名單)|(其他所有的著)
    private val ZHU_PATTERN: Pattern by lazy {
        val sorted = ZHU_ALLOW_LIST.sortedByDescending { it.length }
        Pattern.compile("(${sorted.joinToString("|")})|(著)")
    }

    // 2) 精準詞彙替換表（鍵值長度需相等）
    private val RAW_WORD_REPLACEMENTS = mapOf(
        // 「著」 Zhuó/Zháo
        "執著" to "執卓", "膠著" to "膠卓", "著陸" to "卓陸",
        "著想" to "卓想", "著手" to "卓手", "著眼" to "卓眼",
        "著力" to "卓力", "著重" to "卓重", "著落" to "卓落",
        "著裝" to "卓裝", "著緊" to "卓緊", "著墨" to "卓墨",
        "著色" to "卓色", "著筆" to "卓筆", "沉著" to "沉卓",
        "沈著" to "沉卓", "沈著冷靜" to "沉卓冷静",
        "著實" to "卓实", "看著" to "看着",
        "佛頭著糞" to "佛头卓粪", "畫蛇著足" to "画蛇卓足",
        "棋輸先著" to "棋输先卓", "一鞭先著" to "一鞭先卓",
        "先吾著鞭" to "先吾卓鞭", "瀉水著地" to "泻水卓地",
        "粘皮著骨" to "粘皮卓骨", "枝附葉著" to "枝附叶卓",
        "魂不著體" to "魂不卓体", "水中著鹽" to "水中卓盐",
        "吃衣著飯" to "吃衣卓饭", "眼不著砂" to "眼不卓砂",
        "上不著天" to "上不卓天",
        // Zháo -> 招
        "著急" to "招急", "著火" to "招火", "著涼" to "招涼",
        "著魔" to "招魔", "著迷" to "招迷", "著慌" to "招慌",
        "乾著急" to "干着急", "一度著蛇咬" to "一度招蛇咬",
        "干著急" to "干招急",

        // 「乾」 Gān/Qián -> Gān 用「干」
        "乾燥" to "干燥", "乾杯" to "干杯", "乾洗" to "干洗",
        "乾冰" to "干冰", "乾果" to "干果", "乾糧" to "干粮",
        "乾旱" to "干旱", "乾淨" to "干净", "乾脆" to "干脆",
        "乾活" to "干活", "乾枯" to "干枯", "乾癟" to "干瘪",
        "餅乾" to "饼干", "肉乾" to "肉干", "魚乾" to "鱼干",
        "乾貨" to "干货", "乾麵" to "干面", "乾笑" to "干笑",
        "乾瞪眼" to "干瞪眼", "乾乾淨淨" to "干干净净",
        "相干" to "相干", "不相干" to "不相干", "乾涉" to "干涉", "乾擾" to "干扰",
        "乾爹" to "干爹", "乾媽" to "干妈", "乾兒子" to "干儿子",
        "乾女兒" to "干女儿", "乾妹" to "干妹", "乾親" to "干亲",

        // 「沈」 -> 「沉」
        "沈默" to "沉默", "沈重" to "沉重", "沈澱" to "沉淀",
        "沈迷" to "沉迷", "沈沒" to "沉磨", "深沈" to "深沉",
        "沈思" to "沉思", "沈睡" to "沉睡", "沈積" to "沉积",
        "沈醉" to "沉醉", "陰沈" to "阴沉", "低沈" to "低沉",
        "下沈" to "下沉", "沈浮" to "沉浮", "沈悶" to "沉闷",
        "沈寂" to "沉寂", "沈痛" to "沉痛", "沈吟" to "沉吟",
        "沈浸" to "沉浸", "沈穩" to "沉稳", "消沈" to "消沉",
        "沈甸甸" to "沉甸甸",

        // 「惡」 Wù/È -> Wù 用「误」
        "可惡" to "可误", "厭惡" to "厌误",
        "好逸惡勞" to "好逸恶劳", "深惡痛絕" to "深误痛绝",
        "交惡" to "交误", "憎惡" to "憎误", "羞惡" to "羞误",

        // 高頻多音字
        "校對" to "叫对", "校正" to "叫正", "校樣" to "叫样",
        "校訂" to "叫订", "校驗" to "叫验", "校勘" to "叫勘", "校準" to "叫准",
        "給予" to "挤予", "給養" to "挤养", "供給" to "供挤",
        "埋怨" to "蛮怨",
        "稱職" to "趁职", "稱心" to "趁心", "對稱" to "对趁", "相稱" to "相趁",
        "創傷" to "窗伤", "重創" to "重创", "創口" to "创口",
        "脖頸" to "脖梗",
        "乳臭" to "乳秀", "銅臭" to "铜秀",
        "不遂" to "不随", "半身不遂" to "半身不随",
        "自艾" to "自意",
        "脈脈" to "莫莫",
        "炮製" to "袍制", "炮烙" to "袍烙",
        "拓本" to "踏本", "拓片" to "踏片",
        "遊說" to "游税", "說客" to "税客",
        "粘貼" to "年贴", "黏貼" to "年贴",
        "設身處地" to "设身楚地",
        "寧可" to "佞可", "寧願" to "佞愿",

        // 常用多音
        "憑藉" to "凭借", "慰藉" to "慰借",
        "瞭解" to "了解", "明瞭" to "明了", "瞭若指掌" to "了若指掌",
        "銀行" to "银行", "行長" to "行长", "行業" to "行业",
        "擅長" to "擅常", "長處" to "常处", "長短" to "常短",
        "長久" to "常久", "長期" to "常期", "長遠" to "常远",
        "長度" to "常度", "長篇" to "常篇", "遊行" to "游行",
        "音樂" to "音乐", "樂隊" to "乐队", "樂章" to "乐章",
        "重疊" to "崇叠", "重逢" to "崇逢", "重申" to "崇申",
        "重寫" to "崇写", "重來" to "崇来",
        "重整" to "崇整", "重塑" to "崇塑",
        "省親" to "醒亲",
        "省思" to "醒思",
        "欽差" to "钦拆",
        "還錢" to "环钱", "歸還" to "归环", "償還" to "偿环",
        "還手" to "环手", "還原" to "还原", "生還" to "生环",
        "盛飯" to "成饭", "盛湯" to "成汤", "盛滿" to "成满",
        "協調" to "协调", "強調" to "强调",
        "人參" to "人参",
        "佔卜" to "占卜", "櫃台" to "柜台",

        // 異體/標準化
        "災難" to "灾难", "責難" to "责難", "難民" to "难民",
        "空難" to "空难", "遇難" to "遇难", "發難" to "发难",
        "落難" to "落难", "磨難" to "磨难", "劫難" to "劫难",
        "患難" to "患难", "避難" to "避难",
        "覆蓋" to "覆盖", "計畫" to "计划",
        "儘管" to "尽管", "儘快" to "尽快",

        // 宗教/古文
        "南無" to "拿摩", "般若" to "拨惹",
        "伽藍" to "茄蓝", "楞伽" to "楞茄",
        "舍利" to "设利",
        "祗園" to "只园", "酈食其" to "郦饲其",
        "阿堵物" to "婀堵物", "阿房宮" to "婀房宫", "阿彌陀佛" to "婀弥陀佛", "阿膠" to "婀胶",
        "委蛇" to "威移", "馮河" to "平河", "扛鼎" to "刚鼎",
        "便宜行事" to "变宜行事", "余勇可賈" to "余勇可古",
        "拾級" to "射级", "女紅" to "女工", "扁舟" to "篇舟",
        "裨益" to "必益", "裨將" to "皮将", "斐然" to "匪然",
        "間不容髮" to "见不容发",
        "一暴十寒" to "一铺十寒", "咋舌" to "责舌",

        // 地名/姓氏
        "六安" to "陆安", "六合" to "陆合", "鉛山" to "盐山",
        "番禺" to "潘禺", "廈門" to "下门", "亳州" to "伯州",
        "閔行" to "闵杭", "涪陵" to "扶陵", "國子監" to "国子见",
        "萬俟" to "莫奇", "令狐" to "零狐", "尉遲" to "玉迟",
        "單于" to "缠于", "單縣" to "善县", "麗水" to "利水",

        // 雜項
        "一石" to "一蛋", "萬石" to "万蛋",
        "會計" to "快计", "財會" to "财快",
        "華山" to "画山", "華髮" to "花发",
        "地殼" to "地壳", "脫殼" to "脱俏", "甲殼" to "甲俏",
        "軀殼" to "躯俏",
        "談吐" to "谈土", "吐露" to "土露",
        "嘔吐" to "偶兔", "上吐下瀉" to "上兔下泻",
        "勉強" to "免抢", "強迫" to "抢迫", "強詞奪理" to "抢词夺理",
        "倔強" to "倔匠", "累贅" to "雷赘",
        "混水" to "浑水", "剝削" to "剥薛", "瘦削" to "瘦薛",
        "流血" to "流写", "吐血" to "吐写", "血淋淋" to "写淋淋",
        "貧血" to "贫雪", "心血" to "心雪", "狗血" to "狗雪",
        "殷紅" to "嫣红", "秘魯" to "必鲁",
        "復辟" to "复避", "執拗" to "执牛",
        "鑽石" to "攥石", "鑽頭" to "攥头", "沉澱" to "沉淀",
        "剛正不阿" to "刚正不阿", "組長" to "组长", "處長" to "处掌",
        "乳臭未乾" to "乳臭未干"
    )

    // 3) 單字級異體清洗（1:1）
    private val CHAR_REPLACEMENTS = mapOf(
        '祕' to '秘', '爲' to '為', '恆' to '恒', '峯' to '峰',
        '廄' to '厩', '牀' to '床', '線' to '线', '慾' to '欲',
        '汙' to '污', '溼' to '湿', '裏' to '里', '裡' to '里',
        '纔' to '才', '麵' to '面', '餘' to '余', '蹟' to '迹',
        '跡' to '迹', '衝' to '冲', '摺' to '折', '訊' to '讯',
        '彙' to '汇', '牠' to '它', '鹹' to '咸', '鬆' to '松',
        '傢' to '家', '麼' to '么', '麽' to '么', '強' to '强',
        '髮' to '发', '藝' to '艺', '鑒' to '鉴', '鑑' to '鉴',
        '繫' to '系', '嚮' to '向', '廣' to '广', '賬' to '账',
        '雲' to '云', '裝' to '装', '喫' to '吃', '軟' to '软',
        '穀' to '谷', '臟' to '脏', '惡' to '恶', '從' to '从',
        '學' to '学', '衞' to '卫', '衛' to '卫', '藥' to '药',
        '醫' to '医', '處' to '处', '勢' to '势', '藝' to '艺',
        '長' to '长', '櫃' to '柜', '許' to '许', '續' to '续',
        '鬥' to '斗', '鬧' to '闹', '際' to '际', '舊' to '旧',
        '遊' to '游', '悶' to '闷', '彌' to '弥', '廳' to '厅',
        '紅' to '红', '鋪' to '铺',

    )

    // 根據詞典自動計算最大掃描窗口
    private val MAX_SCAN_LENGTH: Int by lazy {
        val mapMax = RAW_WORD_REPLACEMENTS.keys.maxOfOrNull { it.length } ?: 1
        val allowMax = ZHU_ALLOW_LIST.maxOfOrNull { it.length } ?: 1
        kotlin.math.max(mapMax, allowMax)
    }

    // ————————————————————
    // 主處理邏輯
    // ————————————————————
    fun process(text: String, isDebug: Boolean = false): String {
        if (text.isEmpty()) return text

        val out = StringBuilder(text.length)

        // 1. BreakIterator 分詞
        val iterator = BreakIterator.getWordInstance(Locale.TRADITIONAL_CHINESE)
        iterator.setText(text)

        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)

            // 標點符號和空白通常不是分詞邏輯的重點，不加花括號，保持閱讀流暢
            if (token.isBlank() || isPunctuationOnly(token)) {
                out.append(token)
            } else {
                // 【核心修改】
                // 如果是 debug 模式，用 { } 包裹整個 Token，
                // 這樣你就能看到 "長篇小說" 是被當作一個詞 {長篇小說} 還是兩個詞 {長篇}{小說}
                if (isDebug) out.append("{")

                processInsideToken(token, out, isDebug)

                if (isDebug) out.append("}")
            }

            start = end
            end = iterator.next()
        }
        return out.toString()
    }

    /**
     * Token 內部深度處理函數
     */
    private fun processInsideToken(token: String, out: StringBuilder, isDebug: Boolean) {
        val len = token.length
        var i = 0

        while (i < len) {
            var matched = false
            val maxCurrentScan = min(MAX_SCAN_LENGTH, len - i)

            // FMM 掃描
            for (k in maxCurrentScan downTo 2) {
                val candidate = token.substring(i, i + k)

                // A. 命中替換表
                RAW_WORD_REPLACEMENTS[candidate]?.let { replacement ->
                    // 命中規則：加 [ ]
                    if (isDebug) out.append('[').append(replacement).append(']')
                    else out.append(replacement)

                    i += k
                    matched = true
                    return@let
                }
                if (matched) break

                // B. 命中白名單
                if (ZHU_ALLOW_LIST.contains(candidate)) {
                    // 命中白名單：也加 [ ] (或可改用 < > 區分)
                    if (isDebug) out.append('[').append(candidate).append(']')
                    else out.append(candidate)

                    i += k
                    matched = true
                    break
                }
            }

            // C. 單字兜底處理
            if (!matched) {
                val c = token[i]
                if (CHAR_REPLACEMENTS.containsKey(c)) {
                    // 單字替換通常是異體字修正，Debug 時也可以標記一下，這裡選擇不標記以免太亂
                    // 如果你想看單字替換，可以改成: if(isDebug) out.append('(').append(CHAR_REPLACEMENTS[c]).append(')')
                    out.append(CHAR_REPLACEMENTS[c])
                } else if (c == '著') {
                    // 孤立的「著」轉「着」
                    // 【建議修改】這是重要邏輯，Debug 時最好標出來
                    if (isDebug) out.append("[着]") else out.append("着")
                } else {
                    out.append(c)
                }
                i++
            }
        }
    }

    // 簡單判斷是否純標點（可根據需求擴展）
    private fun isPunctuationOnly(s: String): Boolean {
        return s.all { !Character.isLetterOrDigit(it) }
    }

}