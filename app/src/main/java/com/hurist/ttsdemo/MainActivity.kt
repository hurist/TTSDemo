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
import com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Main activity demonstrating TTS usage
 * Shows both legacy API (class a) and new API (TtsSynthesizer)
 */
class MainActivity : AppCompatActivity() {
    
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

        lifecycleScope.launch(Dispatchers.IO) {
            // Copy voice data files from assets
            copyAssetsToWeReadVoiceDir(this@MainActivity)
            
            withContext(Dispatchers.Main) {
                // Example 1: Using the new TtsSynthesizer API (recommended)
                useTtsSynthesizerExample()
                
                // Example 2: Using legacy API (backward compatible)
                // useLegacyApiExample()
            }
        }
    }
    
    /**
     * Example using the new TtsSynthesizer API (recommended)
     */
    private fun useTtsSynthesizerExample() {
        val speaker = Speaker().apply {
            code = "fn"
        }
        
        val synthesizer = TtsSynthesizer(this, speaker)
        synthesizer.initialize()
        
        val text = "如果你愿意我可以帮你画一条完整的 TTS 流程：合成 → 处理 → 播放，标出每一步对应的方法。这样你能很清楚地看到哪里开始播放。"
        synthesizer.synthesize(50f, 50f, text, null)
        
        Log.d(TAG, "TTS synthesis started with new API")
    }


    /**
     * Copy voice data files from assets to external storage
     */
    private fun copyAssetsToWeReadVoiceDir(context: Context) {
        val destDir = File(context.getExternalFilesDir(null), "voice/weread")
        copyAssetFolder(context, "", destDir.absolutePath)
    }

    /**
     * Recursively copy asset folder to destination
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
                    copyAssetFile(context, assetFilePath, destFile.absolutePath)
                } else {
                    copyAssetFolder(context, assetFilePath, destFile.absolutePath)
                }
            }
            Log.d(TAG, "Asset folder copied: $assetPath -> $destPath")
        } catch (e: IOException) {
            Log.e(TAG, "Error copying asset folder", e)
        }
    }

    /**
     * Copy a single asset file to destination
     */
    private fun copyAssetFile(context: Context, assetFilePath: String, destFilePath: String) {
        context.assets.open(assetFilePath).use { input ->
            File(destFilePath).outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}