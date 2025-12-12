package com.hurist.ttsdemo

import android.content.Context
import android.graphics.Color
import android.icu.text.BreakIterator
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.qq.wx.offlinevoice.synthesizer.Speaker
import com.qq.wx.offlinevoice.synthesizer.SynthesisMode
import com.qq.wx.offlinevoice.synthesizer.TtsCallback
import com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState
import com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import com.hurist.ttsdemo.databinding.ActivityMainBinding
import com.qq.wx.offlinevoice.synthesizer.Level
import com.qq.wx.offlinevoice.synthesizer.PathUtils
import com.qq.wx.offlinevoice.synthesizer.normalizer.SimplifiedTtsNormalizer
import com.qq.wx.offlinevoice.synthesizer.normalizer.TraditionalTtsNormalizer
import com.qq.wx.offlinevoice.synthesizer.online.token.WxTokenManager
import java.util.Locale

/**
 * 主Activity - TTS演示应用
 *
 * 功能:
 * - 手动输入文本进行播放
 * - 动态调整语速（0.5x - 3.0x）
 * - 动态切换发音人
 * - 播放控制（播放/暂停/停止）
 * - 实时显示播放状态
 */
class MainActivity : AppCompatActivity() {

    private var tts: TtsSynthesizer? = null


    private lateinit var binding: ActivityMainBinding

    private val sp by lazy {
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }


    /*private val speakers = listOf(
        Speaker(modelName = "tts_valle", isMale = true),
        Speaker(modelName = "tts_valle_m468_19_0718", isMale = true),
        Speaker(modelName = "tts_valle_caiyu515", isMale = false),
        Speaker(modelName = "tts_valle_10373_f561_0619", isMale = false),
        *//*Speaker(modelName = "chensheng256_vitsb_cn", isMale = true),
        Speaker(modelName = "zhaoyun256_vitsb_cn", isMale = false),
        Speaker(modelName = "talkmale", isMale = true),
        Speaker(modelName = "female3", isMale = false),
        Speaker(modelName = "pdb", isMale = true),
        Speaker(modelName = "male3", isMale = true)*//*
    )*/

    private val speakers = Speaker.entries

    // 可用的发音人列表
    private val availableVoices = listOf(
        "F191",
        "F191_4",
        "dtn",
        "femaleen",
        "femaleen_4",
        "lsl",
        "lsl_4",
        "maleen",
        "maleen_4"
    )
    private var currentVoice: Speaker = speakers[0]

    /*private var token = "OMtRTNxo5Buk/PAl0ZjHde5Vg5TdIRYIAkVyPItydWaSFa6IRETIryshiZO8CS+n"
    private var uid = "925813821"*/
    private var gen = 4 // 每次要修改token, uid的硬编码时, 都要修改这个值

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            WxTokenManager.setTokenFetchUrl("https://api.yqxsapp.com/public/tts")
            WxTokenManager.setAppId("com.yqreader.app")
            WxTokenManager.refreshTokenIfNeed(this@MainActivity)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val lastGen = sp.getInt("gen", -1)
        /*if (gen != lastGen) {
            sp.edit { putInt("gen", gen) }
            sp.edit { putString("token", token) }
            sp.edit { putString("uid", uid) }
        }*/

        //token = sp.getString("token", token) ?: token
        //uid = sp.getString("uid", uid) ?: uid

        // 初始化UI组件
        initViews()

        // 异步加载语音数据
        lifecycleScope.launch(Dispatchers.IO) {
            // 从assets复制语音数据文件
            //copyAssetsToWeReadVoiceDir(this@MainActivity)
            AssetUnpacker.ensureResourcesAreReady(this@MainActivity)
            WxTokenManager.refreshTokenIfNeed(this@MainActivity, maxAgeHours = 24)

            withContext(Dispatchers.Main) {
                // 初始化TTS引擎
                initTts()
            }
        }
    }

    /**
     * 初始化UI组件
     */
    private fun initViews() {

        // 设置语速滑动条 (0.5x到3.0x，步进0.1，默认1.0x)
        // SeekBar范围: 0-25，映射到0.5-3.0
        binding.seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {

            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val speed = 0.5f + (seekBar.progress / 10f)  // 0.5 到 3.0
                binding.textViewSpeed.text = "语速: ${String.format("%.1f", speed)}x"

                // 动态修改语速
                tts?.setSpeed(speed)
            }
        })

        // 设置发音人下拉框
        val voiceAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, speakers.map { it.modelName })
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVoice.adapter = voiceAdapter
        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentVoice = speakers[position]
                Log.d(TAG, "选择发音人: $currentVoice")
                if (PathUtils.checkVoiceResourceExists(context = this@MainActivity, currentVoice.offlineModelName)) {
                    // 动态修改发音人
                    tts?.setVoice(currentVoice)
                } else {
                    tts?.stop()
                    updateStatus("语音数据文件缺失: ${currentVoice.offlineModelName}")
                    Toast.makeText(
                        this@MainActivity,
                        "语音数据文件缺失: ${currentVoice.offlineModelName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.buttonPause.setOnClickListener {
            if (tts?.isSpeaking() == true) {
                tts?.pause()
            } else {
                tts?.resume()
            }
        }

        binding.buttonStop.setOnClickListener {
            tts?.stop()
        }

        binding.buttonClear.setOnClickListener {
            tts?.clearCache()
            binding.logRecyclerView.clearLogs()
        }

        binding.progress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {

            }

            override fun onStopTrackingTouch(slider: Slider) {
                val sentenceIndex = slider.value.toInt() - 1
                tts?.seekToSentence(sentenceIndex)
            }

        })


        binding.buttonPlay.setOnClickListener {
            play(binding.transSwitch.isChecked)
        }

        /*binding.buttonFanTi.setOnClickListener {
            binding.editTextInput.setText(fanti)
            checkSegments(true)
            binding.buttonPlay.setOnClickListener {
                play(true)
            }
        }

        binding.buttonSegment.setOnClickListener {
            binding.editTextInput.setText(segmentTest)
            checkSegments(true)
            binding.buttonPlay.setOnClickListener {
                play(true)
            }
        }

        binding.buttonJian.setOnClickListener {
            binding.editTextInput.setText(sTest)
            //checkSegments(false)
            binding.buttonPlay.setOnClickListener {
                play(false)
            }
        }*/
    }


    private fun play(isTraditional: Boolean) {
        val text = binding.editTextInput.text.toString().trim()
        if (text.isNotEmpty()) {
            tts?.speak(text, 0, isTraditional)
        } else {
            updateStatus("请输入要播放的文本")
        }
    }

    private fun checkSegments(isTraditional: Boolean) {
        val text = binding.editTextInput.text.toString().trim()
        val locale = if (isTraditional) Locale.TRADITIONAL_CHINESE else Locale.SIMPLIFIED_CHINESE
        //val sb = if (isTraditional) TraditionalTtsNormalizer.process(text, isDebug = true) else text
        //binding.logRecyclerView.addLog(Level.INFO, sb)
    }

    /**
     * 初始化TTS引擎
     */
    private fun initTts() {


        // 设置回调以监听TTS事件
        val callback = object : TtsCallback {
            override fun onInitialized(error: Throwable?) {
                Log.d(TAG, "TTS初始化: $error")
                if (error != null) {
                    runOnUiThread {
                        updateStatus("TTS引擎已就绪")
                        enableControls(true)
                    }
                } else {
                    runOnUiThread {
                        updateStatus("TTS引擎初始化失败")
                    }
                }
            }

            override fun onSynthesisStart() {
                Log.d(TAG, "开始合成")
            }

            override fun onSentenceStart(
                sentenceIndex: Int,
                sentence: String,
                totalSentences: Int,
                mode: SynthesisMode,
                startPos: Int,
                endPos: Int,
                triggerReason: String?
            ) {
                Log.d(TAG, "开始播放第 $sentenceIndex 句，共 $totalSentences 句")
                runOnUiThread {
                    binding.progress.apply {
                        if (totalSentences > 1) {
                            valueFrom = 1f
                            valueTo = totalSentences.toFloat()
                            value = sentenceIndex.toFloat() + 1
                        } else {
                            valueFrom = 0f
                            valueTo = 1f
                            value = 1f
                        }
                    }
                    updateStatus("播放中: ${sentenceIndex + 1}/$totalSentences, 当前句[$mode]: ${sentence.trim()}")
                }
            }

            override fun onSentenceComplete(sentenceIndex: Int, sentence: String) {
                Log.d(TAG, "完成第 $sentenceIndex 句")
            }

            override fun onStateChanged(newState: TtsPlaybackState) {
                Log.d(TAG, "状态变更: $newState")
                runOnUiThread {
                    when (newState) {
                        TtsPlaybackState.IDLE -> {
                            updateStatus("空闲")
                            binding.buttonPause.text = "继续"
                        }

                        TtsPlaybackState.PLAYING -> {
                            //updateStatus("播放中")
                            binding.buttonPause.text = "暂停"
                        }

                        TtsPlaybackState.PAUSED -> {
                            //updateStatus("已暂停")
                            binding.buttonPause.text = "继续"
                        }

                        TtsPlaybackState.BUFFERING -> {
                            updateStatus("缓冲中")
                            binding.buttonPause.text = "加载中"
                        }
                    }
                }
            }

            override fun onSynthesisComplete() {
                Log.d(TAG, "全部播放完成")
                runOnUiThread {
                    updateStatus("播放完成")
                }
                /*tts?.speak(
                    text
                )*/
            }

            override fun onPaused() {
                Log.d(TAG, "已暂停")
            }

            override fun onResumed() {
                Log.d(TAG, "已恢复")
            }

            override fun onError(errorMessage: String) {
                Log.e(TAG, "TTS错误: $errorMessage")
                runOnUiThread {
                    updateStatus("错误: $errorMessage")
                }
            }

            override fun onLog(level: Level, logMessage: String) {
                runOnUiThread {
                    binding.logRecyclerView.addLog(level, logMessage)
                }
            }

            override fun onSentenceProgressChanged(
                sentenceIndex: Int,
                sentence: String,
                progress: Int,
                char: String,
                startPos: Int,
                endPos: Int
            ) {
                Log.d(TAG, "句子进度：sentenceIndex：$sentenceIndex Progress: $progress, 句子：$sentence")
                runOnUiThread {
                    try {
                        // 防止 progress 越界
                        val currentIndex = progress.coerceIn(0, sentence.length - 1)

                        // 创建 SpannableString 以支持局部样式
                        val spannable = SpannableString(sentence + "//" + TraditionalTtsNormalizer.process(sentence, isDebug = true))

                        // 设置高亮范围（可根据需要调整：当前字 or 当前字之后几字）
                        spannable.setSpan(
                            ForegroundColorSpan(Color.RED),  // 当前播放字符变红
                            currentIndex,
                            (currentIndex + 1).coerceAtMost(sentence.length),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        // 其余文字恢复默认颜色（例如黑色，可根据主题调整）
                        // 如果你的 TextView 已经有默认颜色，可以不额外处理。

                        // 更新 UI（假设你的 TextView 叫 textView）
                        binding.currentText.text = spannable

                    } catch (e: Exception) {
                        Log.e(TAG, "更新高亮文本失败", e)
                    }
                }
            }
        }

        tts = TtsSynthesizer(this, currentVoice, callback)
       /* tts!!.isPlaying.onEach {
            binding.buttonPause.text = if (it) "暂停" else "继续"
        }.launchIn(lifecycleScope)*/

    }

    /**
     * 更新状态显示
     */
    private fun updateStatus(status: String) {
        binding.textViewStatus.text = "状态: $status"
    }

    /**
     * 启用/禁用控制按钮
     */
    private fun enableControls(enabled: Boolean) {
        binding.buttonPlay.isEnabled = enabled
        binding.buttonPause.isEnabled = enabled
        binding.buttonStop.isEnabled = enabled
        binding.seekBarSpeed.isEnabled = enabled
        binding.spinnerVoice.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.release()
    }

    /**
     * 从assets复制语音数据文件到外部存储
     */
    private fun copyAssetsToWeReadVoiceDir(context: Context) {
        val destDir = PathUtils.getTtsResourcePath(context)
        copyAssetFolder(context, "", destDir)
    }

    /**
     * 递归复制asset文件夹到目标路径
     */
    private fun copyAssetFolder(context: Context, assetPath: String, destPath: String) {
        try {
            val assets = context.assets.list(assetPath) ?: return
            val destDir = File(destPath)
            if (!destDir.exists()) {
                destDir.mkdirs()
            } else {
                // 目录已存在，跳过复制
                return
            }

            for (fileName in assets) {
                val assetFilePath = if (assetPath.isEmpty()) fileName else "$assetPath/$fileName"
                val destFile = File(destDir, fileName)

                val subFiles = context.assets.list(assetFilePath)
                if (subFiles.isNullOrEmpty()) {
                    copyAssetFile(context, assetFilePath, destFile.absolutePath)
                } else {
                    copyAssetFolder(context, assetFilePath, destFile.absolutePath)
                }
            }
            Log.d(TAG, "Asset文件夹已复制: $assetPath -> $destPath")
        } catch (e: IOException) {
            Log.e(TAG, "复制asset文件夹时出错", e)
        }
    }

    /**
     * 复制单个asset文件到目标路径
     */
    private fun copyAssetFile(context: Context, assetFilePath: String, destFilePath: String) {
        context.assets.open(assetFilePath).use { input ->
            File(destFilePath).outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        val fanti = """
            初夏入台州，沿山路而上，天台山雲海翻湧，遠處麗水像一條銀練，山巒恆動，峯巒迭起。清晨在廈門碼頭遇一位髮白的老者，他笑言華髮不改初心，仍愛華山之險。午后抵達國子監舊址，與同儕研討計畫進度，會計與財會同仁核對賬本，覆蓋所有費用與票據；我負責統籌文稿，彙整各組資料，協調流程，強調時程與風險。傍晚里弄燈火初上，弄堂傳來少年吹奏的音樂，樂隊排練一首新樂章，頗有斐然之美。

            這趟考察最先安排的是儀器檢核。老師叮囑我們學校正在操場集合，並逐一完成校正、校對、校樣、校訂、校驗、校勘與校準，務必嚴謹。阿祺沈著冷靜，面對意外也不驚慌；他平時沉默少語、沈重持守，夜裡沈澱思緒便能沈浸於古籍，偶而沈思到沉醉，醒來仍沈穩。遇到瓶頸，他不埋怨，雖陰沈天色、低沈光線，也能在沈寂之中沉著安排，消沈之後更顯堅定。那天他搬來一叠沈甸甸的資料，笑說：「這些文獻真是裨益不小。」

            白天酷熱，物資多為乾糧、乾果、乾麵，冰箱裡還放著乾冰。天氣乾旱，衣物只能乾洗，帳篷要保持乾淨，否則布面易乾枯、乾癟；大家說笑幾聲，化解乾瞪眼的尷尬，最後只能乾笑。鄰隊偶有不相干的指點，甚至相干的事也難免乾涉、乾擾。阿明談起家事，提到他的乾爹、乾媽、乾兒子與乾女兒，還有乾妹與乾親，一時引來善意的揶揄。

            途中說到人事，是非紛雜。有人可惡粗暴，有人厭惡懶散，更有好逸惡勞者招人不齒；少數人交惡後仍不悔改，旁人憎惡、羞惡，但我對惡行深惡痛絕的同時，仍想慰藉受害者。阿湘憑藉過往經驗，讓新進同學瞭解流程、明瞭準則，很快便能瞭若指掌。銀行的行長來訪，談行業贊助；晚間音樂會上，樂隊演繹古曲，樂章起落有致。翌日整理行程，避免重疊的會議；重逢故人時，重申注意事項；需要時就重寫表格、重來程序、重整架構、重塑日程。有人返鄉省親，也有人留步省思；街上忽遇欽差巡視，眾人退避。財務部提醒：借款到期要還錢，物資按期歸還，補助需償還；緊急狀況允許還手自保，但所有報告必須還原真相，盡力保障生還者安置。

            我們午餐常盛飯、盛湯，碗盞盛滿，桌邊談人參藥理，旁人說佔卜之術。討論到災難與難民救助，近年空難頻仍，亦有遇難、發難、落難、磨難、劫難之譯報，患者患難相扶，須預備避難所。後續計畫報告要全面覆蓋，儘管工期緊迫，仍須儘快交付。

            傍晚，我們在祗園旁休憩，誦念南無阿彌陀佛，長者講般若智慧。寺中記碑有伽藍、楞伽之名，室中供奉舍利。史籍研讀到酈食其的故事；有人戲謔說阿堵物、阿房宮、阿膠的典故。談勇武，青年能扛鼎；論處事，需便宜行事；機會來時，余勇可賈。石級濕滑，只得拾級而上；女紅工藝在市集中頗受歡迎；河畔見一葉扁舟。議事中，大家提案裨益良多，裨將史事亦頻被引用。驚險時刻，真是間不容髮，旁人只剩咋舌；天氣反覆，一暴十寒，勸學者貴在恒。

            地理上，我們從台州至天台山，再往麗水；西行至六安與六合，考察鉛山礦脈；南下番禺，轉赴廈門；返程過亳州；在閔行小憩，然後北返涪陵。課堂上教師講述單于與邊塞，地方誌裡還載單縣傳聞。隊員中有萬俟姓者，文思敏捷；令狐同學談吐清雅；尉遲家後人武藝不凡。國子監的藏書資料豐富，令人嚮往。

            某日山道驟雨，一石落下驚動隊伍，萬石壓頂般的聲勢令人心驚。回到營地，會計與財會再次對賬。夜半老者講昔時老友，華髮雖白，卻仍滿懷熱忱。研究報告涵蓋地殼演化、甲殼生物習性與脫殼機制；討論間大家談吐溫雅，偶爾吐露真心。小李腸胃不適，嘔吐不止，竟至上吐下瀉。巷中老屋幽深，弄堂裡的里弄人家熱心端茶。新生面對壓力難免勉強，有人甚至被強迫趕工，也有人強詞奪理耍嘴皮；少數倔強者不願讓步。裝備龐雜，難免累贅；夜路多混水摸魚之徒，須防剝削弱者。瘦削志工扛著物資奔走，令人動容。急救時見流血不止，有人因吐血送醫，事故現場血淋淋；醫師關心貧血病例，評估心血循環；路邊狗血鬧劇引來圍觀。完成報告後，映著殷紅封面，我們約好下次遠赴秘魯考察。朝堂消息傳來，或有復辟之議；民間也時聞執拗之聲。展區陳列鑽石與鑽頭，光澤耀眼。

            關於「著」字的用法，古今皆多。阿祺執著於理想，遇僵局便膠著難進；飛機著陸需精準，工程著手要穩，研判著眼長遠，施策著力於關鍵，彙報著重風險，資源著落明確，士兵著裝整齊，賽程著緊秒表，文人著墨畫意，畫師著色細膩，書家著筆神游；此人沉著應對，沈著不慌，此番著實有效。古籍里有佛頭著糞的笑談、畫蛇著足之戒；棋局記載棋輸先著、先吾著鞭、一鞭先著的招法；行軍例有瀉水著地；匠坊俗語粘皮著骨；葉理譬喻枝附葉著；夜談鬼事，言魂不著體；鹽工記水中著鹽；市井語吃衣著飯；工法需眼不著砂；誓詞有上不著天。

            而臨急之際，人常著急；火警忽至，便著火；夜半受風，易著涼；少年著魔於遊戲，學者著迷於典籍；臨考時人人著慌。遠山傳來野談，有人自言曾一度著蛇咬，眾人哄笑不止；遇手忙腳亂，最怕乾著急。
        """.trimIndent()

        val segmentTest = """
            【第一组：核心“著”字家族 (Zhuó/Zháo/Zhù/Zhe)】

這位著名作家的著作非常顯著，但他穿著很隨意。（測試：著名/著作/顯著 vs 穿著-zhe）

他對藝術很執著，兩人為此爭執著不肯罷休，局面一度膠著。（測試：執著/膠著 vs 爭執著-zhe）

著火了別著急，乾著急沒用，要著手滅火，小心著魔。（測試：著火/著急/乾著急/著手/著魔-zháo）

飛機著陸後，大家懸著的心終於著落了，開始著眼未來。（測試：著陸/著落/著眼-zhuó）

沈著的人不會著涼，他著實是個土著，寫了一部巨著。（測試：沈著/著實/著涼 vs 土著/巨著-zhù）

見微知著，他的成效卓著，信譽卓著，真是臭名昭著的反義詞。（測試：成語白名單-zhù）

他一生著書立說，著作等身，現在正安靜地看著書。（測試：著書/著作等身 vs 看著書-zhe）

這是一部譯著，編著者很用心，著墨很多，著力於細節。（測試：譯著/編著-zhù vs 著墨/著力-zhuó）

【第二组：“乾”与“沈”的智能辨析】

天氣乾燥，他吃著餅乾和乾果，想著乾隆皇帝的乾坤大挪移。（測試：乾燥/餅乾/乾果 vs 乾隆/乾坤-qián）

乾爹認了個乾女兒，幫忙乾洗衣服，做事很乾脆。（測試：乾爹/乾女兒/乾洗/乾脆）

沈先生很沈默，心情沈重，看著老沈澱粉發呆。（測試：沈默/沈重 vs 沈先生/老沈澱粉）

他沈迷於遊戲，老沈迷惑不解地看著他，一聲沈吟。（測試：沈迷/沈吟 vs 老沈迷惑）

這位沈穩的會計，沈積了多年經驗，不再消沈。（測試：沈穩/沈積/消沈）

【第三组：“行”与“长”的边界连环套】

我去銀行存錢，物理老師說水銀行為流動狀。（測試：銀行 vs 水銀行為）

銀行的行長正在講話，這次遊行長達三小時。（測試：行長 vs 遊行長達）

他從事這個行業很久了，步行業務員很辛苦。（測試：行業 vs 步行業務）

他最擅長的技能是跑步，這棵樹已經擅自生長到了牆外。（測試：擅長 vs 擅自生長）

這部長篇小說很精彩，家長篇幅講得太多了。（測試：長篇 vs 家長篇幅）

這是長久之計，組長久久不能平靜，要有長遠眼光。（測試：長久/長遠 vs 組長久久）

這件衣服長短不一，處長短時間內無法解決問題。（測試：長短 vs 處長短時間）

【第四组：动作与状态 (重/创/强/称/校)】

編輯正在校正文稿，而學校正在舉行典禮。（測試：校正 vs 學校正在）

校對員正在校勘古籍，校訂每一個校樣。（測試：校對/校勘/校訂/校樣）

這篇文章需要重寫，請把體重寫在體檢單上。（測試：重寫 vs 體重寫）

災區遭受重創，這是起重創下的最高紀錄，心靈創傷難以癒合。（測試：重創/創傷 vs 起重創下）

他們久別重逢，重申了立場，決定重整旗鼓，重塑形象。（測試：重逢/重申/重整/重塑）

他勉強答應了，強詞奪理地說自己很倔強，強迫別人接受。（測試：強詞奪理/倔強/強迫）

他工作很稱職，但這個名稱職位並不適合他，也不稱心。（測試：稱職/稱心 vs 名稱職位）

兩者完全對稱，大小相稱，這是一次重疊的機遇。（測試：對稱/相稱/重疊）

【第五组：特殊职业与行为 (说/给/埋/恶)】

這位說客口才很好，但這本小說客觀地記錄了歷史。（測試：說客 vs 小說客觀）

他四處遊說，給予大家信心，並供給了大量給養。（測試：遊說/給予/供給/給養）

他埋怨大家把他埋在沙子裡，這種行為真可惡。（測試：埋怨/可惡 vs 埋）

我們不能認可惡勢力，雖然他好逸惡勞，令人厭惡。（測試：厭惡/好逸惡勞 vs 認可惡）

兩人交惡已久，互相憎惡，對此深惡痛絕。（測試：交惡/憎惡/深惡痛絕）

【第六组：医学、身体与生理 (血/壳/颈/脉)】

他因給予太多壓力而咯血，這張卡咯噔一下就壞了。（測試：咯血 vs 卡咯-不變）

傷口血淋淋的，他吐血了，醫生說貧血，心血不足。（測試：血淋淋/吐血/貧血/心血）

這具軀殼雖然看似金蟬脫殼，但實際上脈脈含情。（測試：脫殼/軀殼/甲殼 vs 脈脈）

他的脖頸受傷了，遇到發展瓶頸，臉色蒼白。（測試：脖頸 vs 瓶頸）

這個藥方含有薄荷，但他覺得人情單薄，乳臭未乾。（測試：薄荷/乳臭 vs 單薄）

【第七组：地名与地理 (Extreme Cases)】

他從六安出發，這有六安培的電流。（測試：六安 vs 六安培）

江西鉛山縣很有名，這座廢棄的鉛山脈汙染嚴重。（測試：鉛山 vs 鉛山脈）

他住在番禺，這是輪番禺弄對手的策略。（測試：番禺 vs 輪番禺弄）

台州的天台山風景秀麗，麗水的水很清，廈門的門很大。（測試：台州/天台山/麗水/廈門）

他去亳州買藥，去涪陵吃榨菜，在閔行區坐地鐵。（測試：亳州/涪陵/閔行）

國子監是古代大學，萬俟和令狐是複姓。（測試：國子監/萬俟/令狐）

單于驍勇善戰，這份名單于是被公佈了，單縣在山東。（測試：單于/單縣 vs 名單于是）

【第八组：宗教、玄幻与古文】

老僧口唸南無阿彌陀佛，河南無人機產業發展迅速。（測試：南無 vs 河南無）

般若波羅蜜多，伽藍殿前，供奉著舍利子。（測試：般若/伽藍/舍利）

阿堵物是錢，阿房宮在秦朝，阿彌陀佛保佑。（測試：阿堵物/阿房宮/阿彌陀佛）

他們虛與委蛇，不敢暴虎馮河，能否扛鼎還在兩說。（測試：委蛇/馮河/扛鼎）

便宜行事需要勇氣，余勇可賈，斐然成章。（測試：便宜行事/余勇可賈/斐然）

祗園精舍鐘聲響，楞伽經文難讀懂，酈食其是個人名。（測試：祗園/楞伽/酈食其）

【第九组：杂项多音字与异体字清洗】

古代女子擅長女紅，那位美女紅光滿面。（測試：女紅 vs 美女紅）

這是一支千年人參，此人參與了昨晚的行動。（測試：人參 vs 此人參與）

這是省思的好機會，省長來了，省親回家。（測試：省思/省親 vs 省長）

會計算錯了賬，財會部門很忙，還沒學會計算。（測試：會計/財會 vs 學會計算）

這是炮製好的藥材，受了炮烙之刑，戰場上炮火連天。（測試：炮製/炮烙 vs 炮火）

他埋怨大家，拓本很珍貴，拓片也很稀有。（測試：埋怨/拓本/拓片）

粘貼和黏貼是一個意思，混水摸魚，剝削工人。（測試：粘貼/黏貼/混水/剝削）

復辟是開倒車，執拗是脾氣倔，鑽石和鑽頭都很硬。（測試：復辟/執拗/鑽石/鑽頭）

殷紅的血跡，秘魯的羊駝，弄堂裡的里弄。（測試：殷紅/秘魯/弄堂/里弄）

一石米是多少？萬石君是誰？華山很險，華髮早生。（測試：一石/萬石/華山/華髮）

地殼變動，脫殼而出，甲殼蟲在爬。（測試：地殼/脫殼/甲殼）

他的談吐不凡，吐露心聲，卻嘔吐不止。（測試：談吐/吐露/嘔吐）

累贅是負擔，瘦削的臉龐，說客在遊說。（測試：累贅/瘦削/說客/遊說）

【第十组：单字级异体字修复验证】

這裡有祕密（秘），爲什麼（為），恆心（恒），山峯（峰）。

馬廄（厩），牀鋪（床），線路（线），慾望（欲）。

汙染（污），溼度（湿），這裏（里），這裡（里）。

纔能（才），麵粉（面），剩餘（余），遺跡（迹）。

衝突（冲），摺疊（折），通訊（讯），彙報（汇）。

牠們（它），鹹味（咸），鬆手（松），傢具（家）。

什麼（么），頭髮（发），文藝（艺），鑒定（鉴）。

聯繫（系），嚮往（向），廣大（广），賬戶（账）。

白雲（云），服裝（装），喫飯（吃），軟件（软）。

五穀（谷），心臟（脏），凶惡（恶）。

鬥爭（斗），鬧鐘（闹），際遇（际），舊書（旧）。
        """.trimIndent()


        val sTest = """
            【第一章：多音字】
            这位著名的名角主角，在校对档案时发现，给予他的给养完全不足。
            但他性格倔强，宁可被埋怨，也不愿强词夺理。
            他甚至冒着生命危险，去秘鲁的巷道里当说客，试图游说那些强迫他的人。
            虽然身上伤痕累累，且患有便秘和咯血，但他依然称职地完成了角色扮演。

            【第二章：地名与姓氏】
            他从台州的天台山出发，路过丽水和六安，跨过铅山，最终抵达厦门。
            在闵行区的十里堡，他遇到了复姓万俟和尉迟的两位高人。
            匈奴的单于也派来了使者，虽然名单于是被公布了，但单于本人并未露面。
            他们在瓦窑堡喝着酒，谈论着那曲和番禺的风土人情。

            【第三章：分词测试（绝对不能变）】
            1. 编辑正在校正文稿，而学校正在举行典礼。（测试：校正 vs 学校正在）
            2. 这位说客口才很好，但这本小说客观地记录了历史。（测试：说客 vs 小说客观）
            3. 他因违纪受了处分，但此处分布着许多地雷。（测试：处分 vs 此处分布）
            4. 这种行为真可恶，但我们不能认可恶势力的逻辑。（测试：可恶 vs 认可恶）
            5. 他工作很称职，但这个名称职位并不适合他。（测试：称职 vs 名称职位）

            【第四章：医学与玄幻】
            大夫正在处理流血的伤口，看着血淋淋的创口，他深吸一口气，屏气凝神。
            这具躯壳虽然看似金蝉脱壳，但实际上脉脉含情。
            他口念南无阿彌陀佛，仿佛看到了阿房宫的幻影。
            为了暴虎冯河般的勇气，他喝下了一碗鸡血，发誓要洗刷所有的深恶痛绝。
        """.trimIndent()

        val text = """
            第二章
　　灵溪宗，位于东林洲内，属于通天河的下游支脉所在，立足通天河南北两岸，至今已有万年历史，震慑四方。
　　八座云雾缭绕的惊天山峰，横在通天河上，其中北岸有四座山峰，南岸三座，至于中间的通天河上，赫然有一座最为磅礴的山峰。
　　此山从中段开始就白雪皑皑，竟看不清尽头，只能看到下半部的山体被掏空，使得金色的河水奔腾而过，如同一座山桥。
　　此刻，灵溪宗南岸外，一道长虹疾驰而来，其内中年修士李青候带着白小纯，没入第三峰下的杂役区域，隐隐还可听到长虹内白小纯的惨叫传出。
　　白小纯觉得自己要被吓死了，一路飞行，他看到了无数大山，好几次都觉得自己要抓不住对方的大腿。
　　眼下面前一花，当清晰时，已到了一处阁楼外，落在了地上后，他双腿颤抖，看着四周与村子里完全不同的世界。
　　前方的阁楼旁，竖着一块大石，上面写着龙飞凤舞的三个大字。
　　杂役处。
　　大石旁坐着一个麻脸女子，眼看李青候到来，立刻起身拜见。
　　“将此子送火灶房去。”李青候留下一句话，没有理会白小纯，转身化作长虹远去。
　　麻脸女子听到火灶房三字后一怔，目光扫了白小纯一眼，给了白小纯一个宗门杂役的布袋，面无表情的交代一番，便带着白小纯走出阁楼，一路庭院林立，阁楼无数，青石铺路，还有花草清香，如同仙境，看的白小纯心驰荡漾，心底的紧张与忐忑也少了几分。
　　“好地方啊，这里可比村子里好多了啊。”白小纯目中露出期待，随着走去，越是向前，四周的美景就越发的美奂绝伦，甚至他还看到一些样子秀美的女子时而路过，让白小纯对于这里，一下子就喜欢的不得了。
　　片刻后，白小纯更高兴了，尤其是前方尽头，他看到了一处七层的阁楼，通体晶莹剔透，甚至天空还有仙鹤飞过。
　　“师姐，我们到了吧？”白小纯顿时激动的问道。
　　“恩，就在那。”麻脸女子依旧面无表情，淡淡开口，一指旁侧的小路。
　　白小纯顺着对方所指，满怀期待的看去时，整个人僵住，揉了揉眼睛仔细去看，只见那条小路上，地面多处碎裂，四周更是破破烂烂，几件草房似随时可以坍塌，甚至还有一些怪味从那里飘出……
　　白小纯欲哭无泪，抱着最后一丝希望，问了麻脸女子一句。
　　“师姐，你指错了吧……”
　　“没有。”麻脸女子淡淡开口，当先走上这条小路，白小纯听后，觉得一切美好瞬间坍塌，苦着脸跟了过去。
　　没走多远，他就看到这条破破烂烂的小路尽头，有几口大黑锅窜来窜去，仔细一看，那每一口大黑锅下面，都有一个大胖子，脑满肠肥，似乎一挤都可以流油，不是一般的胖，尤其是里面一个最胖的家伙，跟个肉山似的，白小纯都担心能不能爆了。
　　那几个胖子的四周，有几百口大锅，这些胖子正在添水放米。
　　察觉有人到来，尤其是看到了麻脸女子，那肉山立刻一脸惊喜，拎着大勺，横着就跑了过来，地面都颤了，一身肥膘抖动出无数波澜，白小纯目瞪口呆，下意识的要在身边找斧头。
　　“今早小生听到喜鹊在叫，原来是姐姐你来了，莫非姐姐你已回心转意，觉得我有几分才气，趁着今天良辰，要与小生结成道侣。”肉山目中露出色眯眯的光芒，激动的边跑边喊。
　　“我送此子加入你们火灶房，人已带到，告辞！”麻脸女子在看到肉山后，面色极为难看，还有几分恼怒，赶紧后退。
　　白小纯倒吸口气，那麻脸女子一路上他就留意了，那相貌简直就是鬼斧神工，眼前这大胖子什么口味，居然这样也能一脸色相。
　　还没等白小纯想完，那肉山就呼的一声，出现在了他的面前，直接就将阳光遮盖，把白小纯笼罩在了阴影下。
　　白小纯抬头看着面前这庞大无比，身上的肉还在颤动的胖子，努力咽了口唾沫，这么胖的人，他还是头一次看到。
　　肉山满脸幽怨的将目光从远处麻脸女子离去的方向收回，扫了眼白小纯。
　　“嗬呦，居然来新人了，能把原本安排好的许宝财挤下去，不简单啊。”
　　“师兄，在下……在下白小纯……”白小纯觉得对方魁梧的身体，让自己压力太大，下意识的退后几步。
　　“白小纯？恩……皮肤白，小巧玲珑，模样还很清纯，不错不错，你的名字起的很符合我的口味嘛。”肉山眼睛一亮，拍下了白小纯的肩膀，一下子差点把白小纯直接拍倒。
　　“不知师兄大名是？”白小纯倒吸口气，翻了个白眼，鄙夷的看了眼肉山，心底琢磨着也拿对方的名字玩一玩。
　　“我叫张大胖，那个是黄二胖，还有黑三胖……”肉山嘿嘿一笑。
　　白小纯听到这几个名字，大感人如其名，立刻没了玩一玩的想法。
　　“至于你，以后就叫白九……小师弟，你太瘦了！这样出去会丢我们火灶坊的脸啊，不过也没关系，放心好了，最多一年，你也会胖的，以后你就叫白九胖。”张大胖一拍胸口，肥肉乱颤。
　　听到白九胖这三个字，白小纯脸都挤出苦水了。
　　“既然你已经是九师弟了，那就不是外人了，咱们火灶房向来有背锅的传统，看到我背后这这口锅了吧，它是锅中之王，铁精打造，刻着地火阵法，用这口锅煮出的灵米，味道超出寻常的锅太多太多。你也要去选一口，以后背在身上，那才威风。”张大胖拍了下背后的大黑锅，吹嘘的开口。
　　“师兄，背锅的事，我能不能算了……”白小纯瞄了眼张大胖背后的锅，顿时有种火灶房的人，都是背锅的感觉，脑海里想了一下自己背一口大黑锅的样子，连忙说道。
　　“那怎么行，背锅是我们火灶房的传统，你以后在宗门内，别人只要看到你背着锅，知道你是火灶房的人，就不敢欺负你，咱们火灶房可是很有来头的！”张大胖向白小纯眨了眨眼，不由分说，拎着白小纯就来到草屋后面，那里密密麻麻叠放着数千口大锅，其中绝大多数都落下厚厚一层灰，显然很久都没人过来。
　　“九师弟，你选一口，我们去煮饭了，不然饭糊了，那些外门弟子又要嚷嚷了。”张大胖喊了一声，转身与其他几个胖子，又开始在那上百个锅旁窜来窜去。
　　白小纯唉声叹气，看着那一口口锅，正琢磨选哪一个时，忽然看到了在角落里，放着一口被压在下面的锅。
　　这口锅有些特别，不是圆的，而是椭圆形，看起来不像是锅，反倒像是一个龟壳，隐隐可见似乎还有一些黯淡的纹路。
　　“咦？”白小纯眼睛一亮，快步走了过去，蹲下身子仔细看了看后，将其搬了出来，仔细看后，目中露出满意。
　　他自幼就喜欢乌龟，因为乌龟代表长寿，而他之所以来修仙，就是为了长生，如今一看此锅像龟壳，在他认为，这是很吉利的，是好兆头。
　　将这口锅搬出去后，张大胖远远的看到，拿着大勺就跑了过来。
　　“九师弟你怎么选这口啊，这锅放在那里不知多少年了，没人用过，因为像龟壳，所以也从来没人选背着它在身上，这个……九师弟你确定？”张大胖拍了拍自己的肚子，好心的劝说。
　　“确定，我就要这口锅了。”白小纯越看这口锅越喜欢，坚定道。
　　张大胖又劝说一番，眼看白小纯执意如此，便古怪的看了看他，不再多说，为白小纯安排了在这火灶房居住的草屋后，就又忙碌去了。
　　此刻天色已到黄昏，白小纯在草屋内，将那口龟形的锅仔细的看了看，发现这口锅的背面，有几十条纹路，只是黯淡，若不细看，很难发现。
　　他顿时认为这口锅不凡，将其小心的放在了灶上，这才打量居住的屋舍，这房屋很简单，一张小床，一处桌椅，墙上挂着一面日常所需的铜镜，在他环顾房间时，身后那口平淡无奇的锅上，有一道紫光，一闪而逝！
　　对于白小纯来说，这一天发生了很多事情，如今虽然来到了梦寐以求的仙人世界，可他心里终究是有些茫然。
　　片刻后，他深吸口气，目中露出期望。
　　“我要长生！”白小纯坐在一旁取出杂役处麻脸女子给予的口袋。
　　里面有一枚丹药，一把木剑，一根燃香，再就是杂役的衣服与令牌，最后则是一本竹书，书上有几个小字。
　　“紫气驭鼎功，凝气篇。”
　　黄昏时分，火灶房内张大胖等人忙碌时，屋舍内的白小纯正看着竹书，眼中露出期待，他来到这里是为了长生，而长生的大门，此刻就在他的手中，深呼吸几次后，白小纯打开竹书看了起来。
　　片刻后，白小纯眼中露出兴奋之芒，这竹书上有三幅图，按照上面的说法，修行分为凝气与筑基两个境界，而这紫气驭鼎功分为十层，分别对应凝气的十层。
　　且每修到一层，就可以驭驾外物为己用，当到了第三层后，可以驾驭重量为小半个鼎的物体，到了第六层，则是大半个鼎，而到了第九层，则是一整尊鼎，至于最终的大圆满，则是可以驾驭重量为两尊鼎的物体。
　　只不过这竹书上的功法，只有前三层，余下的没有记录，且若要修炼，还需按照特定的呼吸以及动作，才可以修行这紫气驭鼎功。
　　白小纯打起精神，调整呼吸，闭目摆出竹书上第一幅图的动作，只坚持了三个呼吸，就全身酸痛的惨叫一声，无法坚持下去，且那种呼吸方式，也让他觉得气不够用。
　　“太难了，上面说这修炼这第一幅图，可以感受到体内有一丝气在隐隐游走，可我这里除了难受，什么都没有感觉到。”白小纯有些苦恼，可为了长生，咬牙再次尝试，就这样磕磕绊绊，直至到了傍晚，他始终没有感受到体内的气。
　　他不知道，即便是资质绝佳之人，若没有外力，单纯去修行这紫气驭鼎功的第一层，也需要至少一个月的时间，而他这里才几个时辰，根本就不可能有气感。
　　此刻全身酸痛，白小纯伸了个懒腰，正要去洗把脸，突然的，从门外传来阵阵吵闹之声，白小纯把头伸出窗外，立刻看到一个面黄肌瘦的青年，一脸铁青的站在火灶房院子的大门外。
　　“是谁顶替了我许宝财的名额，给我滚出来！”
　　=========
　　正式更新啦！新书如小树苗一样鲜嫩，急需呵护啊，求推荐票，求收藏！！！推荐，推荐，推荐，收藏，收藏，收藏，重要的事，三遍三遍！！！
        """.trimIndent()


        val content = """夜色如墨，雨丝轻轻打在窗上。
            “你真的……要走吗？”林知远低声问。
            
            苏以安没有回答，她只是抬头看了一眼昏黄的灯光，像是在看另一个世界。
            “行吧，”她终于开口，“人生这条路，本来就没人能替你走。”
            
            林知远笑了笑，笑意却没能到达眼底。
            他伸手，从桌上拿起那本书——《人类简史（Sapiens）》的封面在光下微微反光。
            “这本书你还没看完呢。”
            
            “留给你吧。”她说。
            “你不是说过吗？‘读书不是为了记住，而是为了在某个瞬间被提醒。’”
            
            窗外传来一阵汽车的鸣笛，远处的霓虹灯闪烁着奇异的光。
            她的手机屏幕亮了——“Flight 208 boarding at Gate 5”。
            
            “Time waits for no one.”她轻声说，发音带着一点英式腔调。
            林知远顿了顿，忽然问：“你还记得我们第一次见面的地方吗？”
            
            “当然记得，”她笑了，“那时候你撞到我，还一本正经地说‘对不起，我以为前面没人’。”
            “结果，”她补了一句，“我就成了‘没人’。”
            
            两人都笑了，空气却依然有些凝滞。
            楼下便利店的广播正好响起——
            “现在时间是晚上十点整，欢迎光临7-Eleven。请注意防寒保暖～”
            
            她把外套披上，黑色的头发垂在肩头。
            那条围巾，是他去年冬天送的，上面还绣着她的名字：“An”。
            
            “飞机要起飞了，”她说，“你不用送。”
            “嗯。”
            “真的。”
            “我知道。”
            
            风从门缝里灌进来，带着一点冷意。
            她的脚步声越来越远，直到走廊尽头，只剩下电梯的“叮——”一声。
            
            林知远靠在门边，忽然有点恍惚。
            窗外的雨更大了，像是谁在夜里轻轻啜泣。
            他回到桌前，看着那本书。
            书页之间，夹着一张明信片。
            上面写着一行字：
            
            “别等我，我已经在回来的路上。”
            
            --
            他笑了——
            “真像她的风格。”
            
            电脑屏幕自动亮起，桌面的时间跳到 22:07:45。
            音乐播放器还在循环那首歌：
            🎵《夜的尽头 (The End of the Night)》
            
            他轻声念了一句：“音乐真是奇妙的东西，‘乐’也可以是‘痛’。”
            
            突然，手机振动了一下，是一条新消息：
            【系统通知】您的快递已到达“幸福路12号驿站”。
            
            他愣了几秒，随后打开门，风声立刻钻了进来。
            空气里有雨的味道，也有一种未说出口的温柔。
            
            “她走了，”他喃喃道，“可她的声音，还在我脑子里回荡。”
            
            ……
            
            远处的广播再次响起：
            “明天白天有小雨，气温8到13摄氏度，请注意添衣。”
            
            他关掉灯，只留下窗边那盏旧台灯。
            灯光照在桌上的书页上，最后一行字静静地躺在那里：
            
            “If you hear this, it means I’m still missing you.”
            
            夜，更深了。
            雨，还没停。""".trimIndent()

        const val textLong = """
            夜色如墨，风从远方的山谷吹来，带着湿润的草香与细碎的虫鸣。林深处，一盏孤灯在微微摇曳，映出女子清冷的侧颜。她叫沈知晚，身披青色外袍，指尖轻抚那柄泛着寒光的长剑，剑名“归霜”，是她师父临终前所赠。十年之前，她还是个无忧的少女，笑起来眼角微弯，如新月照水。十年之后，她早已学会了在笑容下隐藏悲意。她踏入这片森林，是为追查一桩旧案——“无相门”的覆灭。传言那夜血流成河，满城皆火，而唯一逃出的她，却忘了是谁推她一掌，从火海中送出。风越刮越烈，枯叶在脚边旋转，她忽然听见一阵低沉的笛声，从林子深处传来，曲调古怪，似悲似喜。她心头一紧，脚步顿住。那笛声她一生都忘不掉，正是那位“无相门”叛徒——顾辞。她缓缓拔剑，寒气与月光交织，落在她眼底如霜。脚下的土地忽然微颤，一阵气浪袭来，一道黑影掠出，带着残破的斗篷与熟悉的气息。顾辞站在她面前，目光淡漠，声音低哑：“十年了，你终于来了。”沈知晚握剑的手微微发颤，却仍挺直脊背，声音冷得像雪：“我来，是为了问你一句——那一夜，你为何救我？”顾辞沉默良久，风吹起他衣角的尘土，月光落在他脸上，那张被岁月刻出深痕的面庞忽然露出一抹苦笑：“因为……那一夜，所有人都该死，除了你。”
        """

         val textEnd = """
              恭喜你完成了TTS演示应用的播放！
                    你可以尝试输入不同的文本，调整语速，切换发音人，体验更多功能。
                    感谢使用本应用，祝你有美好的一天！
        """.trimIndent()
    }
}