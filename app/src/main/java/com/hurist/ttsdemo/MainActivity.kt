package com.hurist.ttsdemo

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.qq.wx.offlinevoice.synthesizer.Speaker
import com.qq.wx.offlinevoice.synthesizer.SynthesizerNative
import com.qq.wx.offlinevoice.synthesizer.TtsVoiceManager
import com.qq.wx.offlinevoice.synthesizer.a
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ShortBuffer


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 将

        // /storage/emulated/0/Android/data/org.nobody.multitts/files/voice/weread/common/word_data.dat


        //Log.d("TTSDemo", "onCreate: SynthesizerNative ${NativeLib.sign("test")}")

        lifecycleScope.launch(Dispatchers.IO) {
            copyAssetsToWeReadVoiceDir(this@MainActivity)
            withContext(Dispatchers.Main) {
               /* val key = TtsVoiceManager(this@MainActivity).voiceFolderPath
                SynthesizerNative.init(key.toByteArray())
                SynthesizerNative.setVoiceName("F191")
                val f31a = ShortBuffer.allocate(64000)
                val sArrArray: ShortArray = f31a.array()*/
               // SynthesizerNative.synthesize(sArrArray, 64000, )
                val  t = a(this@MainActivity, Speaker().apply {
                    code = "fn"
                })
                t.c()
                t.d(50f, 50f, "如果你愿意我可以帮你画一条完整的 TTS 流程：合成 → 处理 → 播放，标出每一步对应的方法。这样你能很清楚地看到哪里开始播放。", null)
            }
        }
        //Java_com_qq_wx_offlinevoice_synthesizer_SynthesizerNative_setVoiceName
        //NativeProxy()//.a("")
    }


    fun copyAssetsToWeReadVoiceDir(context: Context) {
        // 外部 files/voice/weread 目录
        val destDir = File(context.getExternalFilesDir(null), "voice/weread")
        copyAssetFolder(context, "", destDir.absolutePath)
    }

    /**
     * 递归复制 assets 中的文件夹
     * @param context Context
     * @param assetPath 当前 assets 路径（空字符串表示根目录）
     * @param destPath  目标路径（外部存储）
     */
    private fun copyAssetFolder(context: Context, assetPath: String, destPath: String) {
        try {
            val assets = context.assets.list(assetPath) ?: return
            val destDir = File(destPath)
            if (!destDir.exists()) destDir.mkdirs()

            for (fileName in assets) {
                val assetFilePath = if (assetPath.isEmpty()) fileName else "$assetPath/$fileName"
                val destFile = File(destDir, fileName)

                val subFiles = context.assets.list(assetFilePath)
                if (subFiles.isNullOrEmpty()) {
                    // 是文件
                    copyAssetFile(context, assetFilePath, destFile.absolutePath)
                } else {
                    // 是文件夹
                    copyAssetFolder(context, assetFilePath, destFile.absolutePath)
                }
            }
            Log.d("TTSDemo", "copyAssetFolder: 复制完成 $assetPath 到 $destPath")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * 复制单个文件
     */
    private fun copyAssetFile(context: Context, assetFilePath: String, destFilePath: String) {
        context.assets.open(assetFilePath).use { input ->
            File(destFilePath).outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}