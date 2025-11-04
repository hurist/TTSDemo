package com.hurist.ttsdemo

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.qq.wx.offlinevoice.synthesizer.Speaker
import com.qq.wx.offlinevoice.synthesizer.TtsCallback
import com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState
import com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

    // UI组件
    private lateinit var editTextInput: EditText
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var spinnerVoice: Spinner
    private lateinit var buttonPlay: Button
    private lateinit var buttonPause: Button
    private lateinit var buttonStop: Button
    private lateinit var textViewStatus: TextView
    private lateinit var textViewSpeed: TextView


    private val speakers = listOf(
        Speaker(modelName = "tts_valle", isMale = true),
        Speaker(modelName = "tts_valle_m468_19_0718", isMale = true),
        Speaker(modelName = "tts_valle_caiyu515", isMale = false),
        Speaker(modelName = "tts_valle_10373_f561_0619", isMale = false),
        Speaker(modelName = "chensheng256_vitsb_cn", isMale = true),
        Speaker(modelName = "zhaoyun256_vitsb_cn", isMale = false),
        Speaker(modelName = "talkmale", isMale = true),
        Speaker(modelName = "female3", isMale = false),
        Speaker(modelName = "pdb", isMale = true),
        Speaker(modelName = "male3", isMale = true)
    )

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 初始化UI组件
        initViews()

        // 异步加载语音数据
        lifecycleScope.launch(Dispatchers.IO) {
            // 从assets复制语音数据文件
            copyAssetsToWeReadVoiceDir(this@MainActivity)

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
        editTextInput = findViewById(R.id.editTextInput)
        seekBarSpeed = findViewById(R.id.seekBarSpeed)
        spinnerVoice = findViewById(R.id.spinnerVoice)
        buttonPlay = findViewById(R.id.buttonPlay)
        buttonPause = findViewById(R.id.buttonPause)
        buttonStop = findViewById(R.id.buttonStop)
        textViewStatus = findViewById(R.id.textViewStatus)
        textViewSpeed = findViewById(R.id.textViewSpeed)

        // 设置默认文本
        editTextInput.setText(text)

        // 设置语速滑动条 (0.5x到3.0x，步进0.1，默认1.0x)
        // SeekBar范围: 0-25，映射到0.5-3.0
        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {

            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val speed = 0.5f + (seekBar.progress / 10f)  // 0.5 到 3.0
                textViewSpeed.text = "语速: ${String.format("%.1f", speed)}x"

                // 动态修改语速
                tts?.setSpeed(speed)
            }
        })

        // 设置发音人下拉框
        val voiceAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, speakers.map { it.modelName })
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoice.adapter = voiceAdapter
        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentVoice = speakers[position]
                Log.d(TAG, "选择发音人: $currentVoice")

                // 动态修改发音人
                tts?.setVoice(currentVoice)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 设置按钮点击事件
        buttonPlay.setOnClickListener {
            val text = editTextInput.text.toString().trim()
            if (text.isNotEmpty()) {
                tts?.speak(text)
            } else {
                updateStatus("请输入要播放的文本")
            }
        }

        buttonPause.setOnClickListener {
            if (tts?.isSpeaking() == true) {
                tts?.pause()
            } else {
                tts?.resume()
            }
        }

        buttonStop.setOnClickListener {
            tts?.stop()
        }
    }

    /**
     * 初始化TTS引擎
     */
    private fun initTts() {
        tts = TtsSynthesizer(this, currentVoice)
        tts!!.isPlaying.onEach {
            buttonPause.text = if (it) "暂停" else "继续"
        }.launchIn(lifecycleScope)

        // 设置回调以监听TTS事件
        val callback = object : TtsCallback {
            override fun onInitialized(success: Boolean) {
                Log.d(TAG, "TTS初始化: $success")
                if (success) {
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
                totalSentences: Int
            ) {
                Log.d(TAG, "开始播放第 $sentenceIndex 句，共 $totalSentences 句")
                runOnUiThread {
                    updateStatus("播放中: ${sentenceIndex + 1}/$totalSentences, 当前句: $sentence")
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
                        }

                        TtsPlaybackState.PLAYING -> {
                            updateStatus("播放中")
                        }

                        TtsPlaybackState.PAUSED -> {
                            updateStatus("已暂停")
                        }
                    }
                }
            }

            override fun onSynthesisComplete() {
                Log.d(TAG, "全部播放完成")
                runOnUiThread {
                    updateStatus("播放完成")
                }
                tts?.speak(
                    """
                    恭喜你完成了TTS演示应用的播放！
                    你可以尝试输入不同的文本，调整语速，切换发音人，体验更多功能。
                    感谢使用本应用，祝你有美好的一天！
                    """.trimIndent()
                )
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
        }

        tts?.setCallback(callback)
    }

    /**
     * 更新状态显示
     */
    private fun updateStatus(status: String) {
        textViewStatus.text = "状态: $status"
    }

    /**
     * 启用/禁用控制按钮
     */
    private fun enableControls(enabled: Boolean) {
        buttonPlay.isEnabled = enabled
        buttonPause.isEnabled = enabled
        buttonStop.isEnabled = enabled
        seekBarSpeed.isEnabled = enabled
        spinnerVoice.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.release()
    }

    /**
     * 从assets复制语音数据文件到外部存储
     */
    private fun copyAssetsToWeReadVoiceDir(context: Context) {
        val destDir = File(context.getExternalFilesDir(null), "voice/weread")
        copyAssetFolder(context, "", destDir.absolutePath)
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
    }
}