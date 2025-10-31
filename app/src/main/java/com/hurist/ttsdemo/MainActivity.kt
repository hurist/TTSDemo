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

    // 可用的发音人列表
    private val availableVoices = listOf("dtn", "F191", "F191_4", "femaleen", "femaleen_4", "lsl", "lsl_4", "maleen", "maleen_4")
    private var currentVoice = "lsl"

    companion object {
        private const val TAG = "MainActivity"
    }
    
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
        editTextInput.setText("""夜色如墨，雨丝轻轻打在窗上。
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

——
他笑了。
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
雨，还没停。""".trimIndent())
        
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
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availableVoices)
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoice.adapter = voiceAdapter
        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentVoice = availableVoices[position]
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

            override fun onSentenceStart(sentenceIndex: Int, sentence: String, totalSentences: Int) {
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
}