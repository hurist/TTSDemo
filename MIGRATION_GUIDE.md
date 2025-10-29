# TTS 代码迁移指南

## 概述

本指南帮助开发者从旧的混乱 API 迁移到新的清晰架构。

## 快速对比

### 旧代码 vs 新代码

| 概念 | 旧 API | 新 API |
|------|--------|--------|
| 合成器类 | `a` | `TtsSynthesizer` |
| 引擎接口 | `h` | `TtsEngine` |
| 回调接口 | `g` | `TtsCallback` |
| 初始化 | `c()` | `initialize()` |
| 合成 | `d(float, float, String, g)` | `synthesize(float, float, String, TtsCallback?)` |
| 取消 | `cancel()` | `cancel()` |
| 释放 | `release()` | `release()` |

## 迁移步骤

### 步骤 1: 导入新类

**旧代码**:
```kotlin
import com.qq.wx.offlinevoice.synthesizer.a
import com.qq.wx.offlinevoice.synthesizer.h
import com.qq.wx.offlinevoice.synthesizer.g
```

**新代码**:
```kotlin
import com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer
import com.qq.wx.offlinevoice.synthesizer.TtsEngine
import com.qq.wx.offlinevoice.synthesizer.TtsCallback
```

### 步骤 2: 创建合成器实例

**旧代码**:
```kotlin
val speaker = Speaker().apply {
    code = "fn"
}
val tts = a(context, speaker)
```

**新代码**:
```kotlin
val speaker = Speaker().apply {
    code = "fn"
}
val tts = TtsSynthesizer(context, speaker)
```

### 步骤 3: 初始化

**旧代码**:
```kotlin
tts.c()  // 不清楚这是什么操作
```

**新代码**:
```kotlin
tts.initialize()  // 清晰明了
```

### 步骤 4: 合成文本

**旧代码**:
```kotlin
tts.d(50f, 50f, "测试文本", null)
// d 是什么意思？参数顺序是什么？
```

**新代码**:
```kotlin
tts.synthesize(
    speed = 50f,
    volume = 50f,
    text = "测试文本",
    callback = null
)
// 清晰的命名参数
```

### 步骤 5: 释放资源

**旧代码**:
```kotlin
tts.release()
```

**新代码**:
```kotlin
tts.release()  // 相同，但新版本有更好的资源管理
```

## 完整示例对比

### 旧代码（不推荐）

```kotlin
@Suppress("DEPRECATION")
class OldTtsExample(private val context: Context) {
    
    private var ttsEngine: a? = null
    
    fun initAndSpeak(text: String) {
        // 创建 Speaker
        val speaker = Speaker().apply {
            code = "fn"
        }
        
        // 创建引擎（类名不清晰）
        ttsEngine = a(context, speaker)
        
        // 初始化（方法名不清晰）
        ttsEngine?.c()
        
        // 合成（参数含义不清晰）
        ttsEngine?.d(50f, 50f, text, null)
    }
    
    fun stop() {
        ttsEngine?.cancel()
    }
    
    fun cleanup() {
        ttsEngine?.release()
        ttsEngine = null
    }
}
```

### 新代码（推荐）

```kotlin
class NewTtsExample(private val context: Context) {
    
    private var synthesizer: TtsSynthesizer? = null
    
    fun initAndSpeak(text: String) {
        // 创建 Speaker 配置
        val speaker = Speaker().apply {
            code = "fn"
        }
        
        // 创建合成器（类名清晰）
        synthesizer = TtsSynthesizer(context, speaker)
        
        // 初始化（方法名清晰）
        synthesizer?.initialize()
        
        // 合成并播放（参数清晰）
        synthesizer?.synthesize(
            speed = 50f,
            volume = 50f,
            text = text,
            callback = null
        )
    }
    
    fun stop() {
        synthesizer?.cancel()
    }
    
    fun cleanup() {
        synthesizer?.release()
        synthesizer = null
    }
}
```

## 高级特性

### 添加回调监听

新 API 支持更清晰的回调接口：

```kotlin
class TtsWithCallback(private val context: Context) {
    
    private val callback = object : TtsCallback {
        override fun onSynthesisStart() {
            Log.d(TAG, "开始合成")
            // 显示加载指示器
        }
        
        override fun onPcmDataAvailable(pcmData: ByteArray, length: Int) {
            Log.d(TAG, "PCM 数据: $length bytes")
            // 可以保存或进一步处理 PCM 数据
        }
        
        override fun onSynthesisComplete() {
            Log.d(TAG, "合成完成")
            // 隐藏加载指示器
        }
        
        override fun onError(errorMessage: String) {
            Log.e(TAG, "错误: $errorMessage")
            // 显示错误消息
        }
    }
    
    fun speak(text: String) {
        val speaker = Speaker().apply { code = "fn" }
        val synthesizer = TtsSynthesizer(context, speaker)
        
        synthesizer.initialize()
        synthesizer.synthesize(50f, 50f, text, callback)
    }
}
```

### 使用协程（推荐）

```kotlin
class TtsWithCoroutines(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Main)
    
    fun speakAsync(text: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val speaker = Speaker().apply { code = "fn" }
                val synthesizer = TtsSynthesizer(context, speaker)
                
                synthesizer.initialize()
                synthesizer.synthesize(50f, 50f, text, null)
                synthesizer.release()
            }
        }
    }
    
    fun cleanup() {
        scope.cancel()
    }
}
```

## 常见问题

### Q1: 旧代码还能用吗？

**A**: 可以。我们保留了旧 API 作为适配器，但标记为 `@Deprecated`。建议尽快迁移到新 API。

```kotlin
// 这样仍然可以工作，但会有废弃警告
@Suppress("DEPRECATION")
val oldTts = a(context, speaker)
oldTts.c()
```

### Q2: 新旧 API 有性能差异吗？

**A**: 没有。新 API 内部调用的是相同的底层实现，只是提供了更好的抽象和模块化。实际上，新 API 可能略微更高效，因为：
- 更好的资源管理
- 减少了不必要的代码路径

### Q3: 如何调整音高和速度？

**A**: 新架构在 `TtsConstants` 中定义了这些参数：

```kotlin
// 如需自定义，可以修改常量或创建新的 PcmProcessor
object TtsConstants {
    const val PITCH_FACTOR = 0.68f  // 降低音高
    const val SONIC_SPEED = 0.78f   // 轻微减速
}
```

### Q4: 如何处理多语音合成？

```kotlin
class MultiVoiceTts(private val context: Context) {
    
    fun speakWithDifferentVoices(texts: List<Pair<String, String>>) {
        // texts 是 (文本, 语音编码) 对
        
        texts.forEach { (text, voiceCode) ->
            val speaker = Speaker().apply {
                code = voiceCode
            }
            
            val synthesizer = TtsSynthesizer(context, speaker)
            synthesizer.initialize()
            synthesizer.synthesize(50f, 50f, text, null)
            synthesizer.release()
        }
    }
}
```

### Q5: 如何在 Fragment 中使用？

```kotlin
class TtsFragment : Fragment() {
    
    private var synthesizer: TtsSynthesizer? = null
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val speaker = Speaker().apply { code = "fn" }
        synthesizer = TtsSynthesizer(requireContext(), speaker)
        synthesizer?.initialize()
    }
    
    fun speak(text: String) {
        synthesizer?.synthesize(50f, 50f, text, null)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        synthesizer?.release()
        synthesizer = null
    }
}
```

## 最佳实践

### 1. 生命周期管理

```kotlin
class TtsManager(private val context: Context) : LifecycleObserver {
    
    private var synthesizer: TtsSynthesizer? = null
    
    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        val speaker = Speaker().apply { code = "fn" }
        synthesizer = TtsSynthesizer(context, speaker)
        synthesizer?.initialize()
    }
    
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        synthesizer?.release()
        synthesizer = null
    }
}
```

### 2. 单例模式（全局 TTS）

```kotlin
object GlobalTts {
    
    @Volatile
    private var instance: TtsSynthesizer? = null
    
    fun getInstance(context: Context): TtsSynthesizer {
        return instance ?: synchronized(this) {
            instance ?: createSynthesizer(context).also {
                instance = it
            }
        }
    }
    
    private fun createSynthesizer(context: Context): TtsSynthesizer {
        val speaker = Speaker().apply { code = "fn" }
        return TtsSynthesizer(context.applicationContext, speaker).apply {
            initialize()
        }
    }
    
    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}
```

### 3. 配置化

```kotlin
data class TtsConfig(
    val voiceCode: String = "fn",
    val speed: Float = 50f,
    val volume: Float = 50f
)

class ConfigurableTts(
    private val context: Context,
    private val config: TtsConfig
) {
    
    private val synthesizer: TtsSynthesizer
    
    init {
        val speaker = Speaker().apply {
            code = config.voiceCode
        }
        synthesizer = TtsSynthesizer(context, speaker)
        synthesizer.initialize()
    }
    
    fun speak(text: String) {
        synthesizer.synthesize(
            config.speed,
            config.volume,
            text,
            null
        )
    }
    
    fun cleanup() {
        synthesizer.release()
    }
}
```

## 迁移检查清单

- [ ] 替换所有 `import ...a` 为 `import ...TtsSynthesizer`
- [ ] 替换所有 `.c()` 调用为 `.initialize()`
- [ ] 替换所有 `.d()` 调用为 `.synthesize()`
- [ ] 添加适当的注释和文档
- [ ] 测试所有 TTS 功能
- [ ] 验证资源正确释放
- [ ] 更新相关文档

## 支持与帮助

如有问题，请查看：
- [架构文档](ARCHITECTURE.md)
- [重构说明](REFACTORING.md)
- 代码中的 KDoc 注释

## 总结

新 API 的优势：
- ✅ 清晰的命名
- ✅ 更好的文档
- ✅ 模块化设计
- ✅ 易于测试
- ✅ 向后兼容

建议所有新代码使用新 API，现有代码逐步迁移。
