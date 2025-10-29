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
import com.qq.wx.offlinevoice.synthesizer.TtsCallback
import com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState
import com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Main activity demonstrating TTS usage with the new optimized API
 */
class MainActivity : AppCompatActivity() {
    
    private var tts: TtsSynthesizer? = null
    
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
                // Demonstrate the new TTS features
                demonstrateNewTtsFeatures()
            }
        }
    }
    
    /**
     * Demonstrate the new TTS features with comprehensive callbacks
     */
    private fun demonstrateNewTtsFeatures() {
        val speaker = Speaker().apply {
            code = "fn"
        }
        
        tts = TtsSynthesizer(this, speaker)
        
        // Create a comprehensive callback to demonstrate all events
        val callback = object : TtsCallback {
            override fun onInitialized(success: Boolean) {
                Log.d(TAG, "TTS Initialized: $success")
            }
            
            override fun onSynthesisStart() {
                Log.d(TAG, "TTS Synthesis started")
            }
            
            override fun onSentenceStart(sentenceIndex: Int, sentence: String, totalSentences: Int) {
                Log.d(TAG, "Starting sentence $sentenceIndex/$totalSentences: $sentence")
            }
            
            override fun onSentenceComplete(sentenceIndex: Int, sentence: String) {
                Log.d(TAG, "Completed sentence $sentenceIndex: $sentence")
            }
            
            override fun onStateChanged(newState: TtsPlaybackState) {
                Log.d(TAG, "State changed to: $newState")
            }
            
            override fun onSynthesisComplete() {
                Log.d(TAG, "All synthesis completed!")
            }
            
            override fun onPaused() {
                Log.d(TAG, "TTS paused")
            }
            
            override fun onResumed() {
                Log.d(TAG, "TTS resumed")
            }
            
            override fun onError(errorMessage: String) {
                Log.e(TAG, "TTS error: $errorMessage")
            }
        }
        
        // Initialize TTS
        tts?.initialize()
        
        // Example 1: Basic usage - speak multiple sentences
        val longText = """
            这是第一句话。这是第二句话！这是第三句话？
            文本转语音引擎会自动分句。每句话读完后，会自动读下一句。
            直到所有句子都读完为止。
        """.trimIndent()
        
        tts?.speak(
            text = longText,
            speed = 50f,
            volume = 50f,
            callback = callback
        )
        
        // Example 2: Demonstrate pause/resume functionality
        lifecycleScope.launch {
            delay(3000) // Wait 3 seconds
            Log.d(TAG, "Attempting to pause...")
            tts?.pause()
            
            delay(2000) // Pause for 2 seconds
            Log.d(TAG, "Attempting to resume...")
            tts?.resume()
        }
        
        // Example 3: Monitor status
        lifecycleScope.launch {
            repeat(20) {
                delay(1000)
                val status = tts?.getStatus()
                Log.d(TAG, "Status: ${status?.state}, Sentence: ${status?.currentSentenceIndex}/${status?.totalSentences}")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tts?.release()
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