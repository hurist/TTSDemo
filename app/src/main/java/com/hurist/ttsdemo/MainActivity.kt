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
        editTextInput.setText("""那天傍晚，风有点凉。
“你要走了吗？”她轻声问。
李行停下脚步，微微一笑：“走一趟很长的路。”
“长？多长？”
“也许是一辈子。”
他的话像风一样飘散。远处传来几声狗叫，街灯一盏盏亮起。
他回头望了她一眼，那一瞬间，仿佛所有的往事都被藏在了她的眼里——明亮，却又带着一丝悲伤。
她忽然笑了：“那我等你回来，当作……一种乐趣吧。”
李行愣了愣，也笑了，“乐”这个字，在风里轻轻回荡着。
夜色重了，雨点开始落下……一切都像没发生过似的。""".trimIndent())
        
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