# TTS 引擎优化 - API 文档

本文档详细说明了优化后的 TTS 引擎的完整 API。

## 核心接口

### TtsEngine

主要的 TTS 引擎接口，定义了所有 TTS 操作。

```kotlin
interface TtsEngine {
    // 初始化引擎
    fun initialize()
    
    // 播放文本（新 API）
    fun speak(
        text: String, 
        speed: Float = 50f, 
        volume: Float = 50f, 
        callback: TtsCallback? = null
    )
    
    // 控制方法
    fun pause()
    fun resume()
    fun stop()
    
    // 状态查询
    fun getStatus(): TtsStatus
    fun isSpeaking(): Boolean
    
    // 释放资源
    fun release()
    
    // 已废弃方法（向后兼容）
    @Deprecated fun synthesize(speed: Float, volume: Float, text: String, callback: g?)
    @Deprecated fun cancel()
}
```

### TtsCallback

事件回调接口，监听 TTS 的各种事件。

```kotlin
interface TtsCallback {
    // 初始化完成
    fun onInitialized(success: Boolean) {}
    
    // 开始合成（所有句子）
    fun onSynthesisStart() {}
    
    // 句子级别事件
    fun onSentenceStart(sentenceIndex: Int, sentence: String, totalSentences: Int) {}
    fun onSentenceComplete(sentenceIndex: Int, sentence: String) {}
    
    // 状态变化
    fun onStateChanged(newState: TtsPlaybackState) {}
    
    // PCM 数据（可选）
    fun onPcmDataAvailable(pcmData: ByteArray, length: Int) {}
    
    // 完成事件
    fun onSynthesisComplete() {}
    
    // 控制事件
    fun onPaused() {}
    fun onResumed() {}
    
    // 错误处理
    fun onError(errorMessage: String) {}
}
```

## 数据类

### TtsPlaybackState

播放状态枚举。

```kotlin
enum class TtsPlaybackState {
    UNINITIALIZED,  // 未初始化
    IDLE,           // 空闲
    PLAYING,        // 播放中
    PAUSED,         // 已暂停
    STOPPING,       // 停止中
    ERROR           // 错误
}
```

### TtsStatus

当前状态数据。

```kotlin
data class TtsStatus(
    val state: TtsPlaybackState,        // 当前状态
    val totalSentences: Int,            // 总句数
    val currentSentenceIndex: Int,      // 当前句子索引
    val currentSentence: String,        // 当前句子文本
    val errorMessage: String? = null    // 错误信息（如有）
)
```

## 工具类

### SentenceSplitter

句子分割工具。

```kotlin
object SentenceSplitter {
    // 分割文本为句子（去除分隔符）
    fun splitIntoSentences(text: String): List<String>
    
    // 分割文本并保留分隔符
    fun splitWithDelimiters(text: String): List<String>
}
```

支持的分隔符：
- 中文：。！？；
- 英文：. ! ? ;

## 使用示例

### 1. 基础播放

```kotlin
val tts = TtsSynthesizer(context, speaker)
tts.initialize()
tts.speak("这是第一句。这是第二句！")
```

### 2. 带参数的播放

```kotlin
tts.speak(
    text = "你好，世界！",
    speed = 70f,    // 速度 0-100
    volume = 80f,   // 音量 0-100
    callback = null
)
```

### 3. 使用完整回调

```kotlin
val callback = object : TtsCallback {
    override fun onInitialized(success: Boolean) {
        if (success) {
            Log.d(TAG, "TTS 初始化成功")
        }
    }
    
    override fun onSynthesisStart() {
        Log.d(TAG, "开始播放")
    }
    
    override fun onSentenceStart(index: Int, sentence: String, total: Int) {
        Log.d(TAG, "播放句子 $index/$total: $sentence")
        updateProgressUI(index, total)
    }
    
    override fun onSentenceComplete(index: Int, sentence: String) {
        Log.d(TAG, "完成句子 $index")
    }
    
    override fun onStateChanged(newState: TtsPlaybackState) {
        when (newState) {
            TtsPlaybackState.PLAYING -> showPlayingUI()
            TtsPlaybackState.PAUSED -> showPausedUI()
            TtsPlaybackState.IDLE -> showIdleUI()
            else -> {}
        }
    }
    
    override fun onSynthesisComplete() {
        Log.d(TAG, "播放完成")
        showCompletionMessage()
    }
    
    override fun onPaused() {
        Log.d(TAG, "已暂停")
    }
    
    override fun onResumed() {
        Log.d(TAG, "已恢复")
    }
    
    override fun onError(errorMessage: String) {
        Log.e(TAG, "错误: $errorMessage")
        showErrorDialog(errorMessage)
    }
}

tts.speak("长文本...", callback = callback)
```

### 4. 播放控制

```kotlin
// 暂停播放
pauseButton.setOnClickListener {
    tts.pause()
}

// 恢复播放
resumeButton.setOnClickListener {
    tts.resume()
}

// 停止播放
stopButton.setOnClickListener {
    tts.stop()
}

// 检查是否在播放
if (tts.isSpeaking()) {
    // 正在播放
}
```

### 5. 状态查询

```kotlin
val status = tts.getStatus()

println("状态: ${status.state}")
println("总句数: ${status.totalSentences}")
println("当前句子: ${status.currentSentenceIndex}")
println("当前文本: ${status.currentSentence}")

if (status.state == TtsPlaybackState.ERROR) {
    println("错误: ${status.errorMessage}")
}
```

### 6. 完整的生命周期管理

```kotlin
class TtsManager(private val context: Context) {
    private var tts: TtsSynthesizer? = null
    
    fun initialize() {
        val speaker = Speaker().apply { code = "fn" }
        tts = TtsSynthesizer(context, speaker)
        tts?.initialize()
    }
    
    fun speak(text: String, callback: TtsCallback? = null) {
        tts?.speak(text, callback = callback)
    }
    
    fun pause() {
        tts?.pause()
    }
    
    fun resume() {
        tts?.resume()
    }
    
    fun stop() {
        tts?.stop()
    }
    
    fun getStatus(): TtsStatus? {
        return tts?.getStatus()
    }
    
    fun release() {
        tts?.release()
        tts = null
    }
}
```

## 最佳实践

### 1. 单例模式

```kotlin
object TtsHelper {
    private var tts: TtsSynthesizer? = null
    
    fun getInstance(context: Context): TtsSynthesizer {
        if (tts == null) {
            val speaker = Speaker().apply { code = "fn" }
            tts = TtsSynthesizer(context.applicationContext, speaker)
            tts?.initialize()
        }
        return tts!!
    }
    
    fun release() {
        tts?.release()
        tts = null
    }
}
```

### 2. 错误处理

```kotlin
val callback = object : TtsCallback {
    override fun onError(errorMessage: String) {
        when {
            errorMessage.contains("not initialized") -> {
                // 重新初始化
                tts.initialize()
                tts.speak(text)
            }
            errorMessage.contains("synthesis") -> {
                // 合成失败，尝试重新播放
                tts.stop()
                Handler().postDelayed({ tts.speak(text) }, 500)
            }
            else -> {
                // 其他错误
                Log.e(TAG, "TTS error: $errorMessage")
            }
        }
    }
}
```

### 3. UI 集成

```kotlin
class TtsViewModel : ViewModel() {
    private val _playbackState = MutableLiveData<TtsPlaybackState>()
    val playbackState: LiveData<TtsPlaybackState> = _playbackState
    
    private val _progress = MutableLiveData<Pair<Int, Int>>()
    val progress: LiveData<Pair<Int, Int>> = _progress
    
    private val callback = object : TtsCallback {
        override fun onStateChanged(newState: TtsPlaybackState) {
            _playbackState.postValue(newState)
        }
        
        override fun onSentenceStart(index: Int, sentence: String, total: Int) {
            _progress.postValue(Pair(index, total))
        }
    }
    
    fun speak(text: String, tts: TtsSynthesizer) {
        tts.speak(text, callback = callback)
    }
}
```

## 线程安全

- 所有公共方法都是线程安全的
- 回调在后台线程中调用，UI 更新需要切换到主线程：

```kotlin
override fun onSentenceStart(index: Int, sentence: String, total: Int) {
    runOnUiThread {
        textView.text = sentence
    }
}
```

## 性能考虑

1. **避免频繁初始化**: 复用同一个实例
2. **长文本优化**: 自动分句已优化
3. **内存管理**: 及时调用 `release()`
4. **回调性能**: 回调方法应快速返回，避免阻塞

## 常见模式

### 朗读文章

```kotlin
fun readArticle(article: String) {
    tts.speak(
        text = article,
        speed = 50f,
        volume = 70f,
        callback = object : TtsCallback {
            override fun onSentenceStart(index: Int, sentence: String, total: Int) {
                highlightSentence(index)
            }
            
            override fun onSynthesisComplete() {
                showCompletionAnimation()
            }
        }
    )
}
```

### 可控制的播放

```kotlin
class ArticleReader(private val tts: TtsSynthesizer) {
    private var isPaused = false
    
    fun start(article: String) {
        tts.speak(article, callback = callback)
    }
    
    fun togglePause() {
        if (isPaused) {
            tts.resume()
        } else {
            tts.pause()
        }
        isPaused = !isPaused
    }
    
    fun stop() {
        tts.stop()
        isPaused = false
    }
}
```

## 迁移指南

### 从旧 API 迁移

旧 API:
```kotlin
synthesizer.synthesize(50f, 50f, "文本", null)
synthesizer.cancel()
```

新 API:
```kotlin
synthesizer.speak("文本", speed = 50f, volume = 50f)
synthesizer.stop()
```

## 故障排查

### 问题: TTS 不播放

**解决方案**:
1. 检查是否已初始化：`tts.initialize()`
2. 检查状态：`tts.getStatus().state`
3. 查看错误回调：`onError()`

### 问题: 暂停后无法恢复

**解决方案**:
确保在 `PAUSED` 状态下调用 `resume()`：

```kotlin
if (tts.getStatus().state == TtsPlaybackState.PAUSED) {
    tts.resume()
}
```

### 问题: 内存泄漏

**解决方案**:
在 Activity/Fragment 销毁时调用 `release()`:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    tts.release()
}
```
