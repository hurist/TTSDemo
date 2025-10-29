package com.hurist.ttsdemo

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
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
    private lateinit var button: Button

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

        button = findViewById(R.id.button)
        button.setOnClickListener {
            if (tts == null) {
                Log.w(TAG, "TTS not initialized yet")
            } else {
                if (tts!!.isSpeaking()) {
                    tts!!.pause()
                } else {
                    tts!!.resume()
                }
            }
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
        
        tts = TtsSynthesizer(this, "pb")
        
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
                runOnUiThread {
                    button.text = "TTS State: $newState"
                }
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
            一顆年輕的終於在世俗中成熟起來。厭倦的枯燥乏味的生活都成了一種習慣。慵懶地呼吸著混凝土與柏油路吐出的空氣。眼睛在高樓大廈間穿梭，尋覓心走過的路。

　　曾經喜歡一個人流浪在農村空曠的田野上，任心追逐淡而深情的泥土味，徘徊在莊稼與野草間。偶而拽著風的白褶裙隨它在深遂的天宇中飛翔。樹淩亂而尊嚴地站著，紳士般，沒有因葉子的交雜而產生的擁擠與不堪。低雁飛鵲悠閑地在風中散步。偶而會瞥一下孤獨的流浪人。遲緩地低鳴宣布著身體地倦怠。風吹來，撩撥著內心的清純與恬靜，像小貓從桌台跳到地板上那樣輕，生怕驚覺現實而荒蕪的心。

　　也許是一種逃避，沒有容納百川的度量。當失敗的陰影牢牢罩住整個的身心，靈魂才明白所有的才能在這樣的天地中不過是對人生的一種嘲弄。所擁有的黯去光彩，所失去的都化作絕望黑與憂鬱藍，於是失落的心再找不到沉淪的地方。記憶，剛硬而倔強地寫下靈魂的心遊曆過的名勝古跡。名勝?古跡?一種虛飾的擺設罷了。任我們誹謗，任我們修改，沒有反抗的力量。心被自然伏獲後，奴性便在現實中滋長。被人造的自然景觀吸引，一樣的風，一樣的花鳥，一樣的魚石。不一樣的是那味，是那氣息。奴性成熟以後，心就學會了適應，在忍耐中適應。記憶中的往事都成了模糊而虛偽的小說。眼前的現實才是真正的擁有。回首，不過是自己給別人虛構的一個夢罷了。記憶也只是一個可任意刪改的寫字板。誰又會知道這一板前的那些東西?

　　高聲唱那首《壯志在我胸》。輕聲唱那首《最遠的你是我最近的愛》。心在憂鬱地生長，在憤怒中跳動。想起了白樺林，沒有我的腳步，我卻能聽到簌簌的風掀動樹葉聲。想起了花季雨季中開懷的歡笑，想起了彈玻璃球，捉迷藏時的天真與幼稚。那都成了往事，另一個世界運行的電視劇。被現實中瑣屑的事撩擾著，迷惘地走了很長一段路，因為分不清是幻想還是回首的往事，心才迷失。

　　因為空虛，所以才喜歡幻想與回首。被網絡籠絡的心沒有了真情。空虛?多么好的借口:因為生活著，所以空虛著;因為失敗過，所以才更理性的思索;因為不相信回首，也就無所謂失敗與成功。只要生活著，就要快樂著。

　　回首，像一幕電影給視覺短暫的休憩，由眼睛觀看心笨拙的表演。
        """.trimIndent()

        tts?.setCallback(callback)
        tts?.speak(
            text = longText,
        )

        // Example 2: Demonstrate pause/resume functionality
        /*lifecycleScope.launch {
            delay(3000) // Wait 3 seconds
            Log.d(TAG, "Attempting to pause...")
            tts?.pause()

            delay(2000) // Pause for 2 seconds
            Log.d(TAG, "Attempting to resume...")
            tts?.resume()
        }*/

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
            if (!destDir.exists()) {
                destDir.mkdirs()
            } else {
                // Directory already exists, skip copying
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