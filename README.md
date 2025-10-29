# TTSDemo - 优化的文本转语音示例项目

一个基于微信读书 TTS 引擎的高级文本转语音项目，提供完整的句子级别播放控制和状态管理。

## 项目概述

本项目展示了如何使用原生 TTS 引擎进行文本合成，实现了完整的句子级别播放控制，包括自动分句、暂停/恢复、状态跟踪等高级功能。

### 主要特性

- ✅ **自动分句播放**: 传入大段文本自动分句，按句子顺序阅读
- ✅ **顺序播放**: 每句读完自动读下一句，直到全部完成
- ✅ **播放控制**: 支持暂停、恢复、停止等完整控制
- ✅ **状态管理**: 实时获取播放状态、当前句子信息
- ✅ **完整回调**: 初始化、播放状态变化、句子进度、开始结束等全面的事件回调
- ✅ **音高和速度调节**: 基于 Sonic 库的高质量音频处理
- ✅ **清晰的架构**: 模块化设计，易于维护和扩展

## 快速开始

### 基本用法

```kotlin
// 1. 创建语音配置
val speaker = Speaker().apply {
    code = "fn"  // 语音编码
}

// 2. 创建并初始化 TTS
val tts = TtsSynthesizer(context, speaker)
tts.initialize()

// 3. 播放文本（自动分句）
val text = """
    这是第一句话。这是第二句话！
    TTS引擎会自动分句并依次播放。
"""
tts.speak(text, speed = 50f, volume = 50f)

// 4. 控制播放
tts.pause()   // 暂停
tts.resume()  // 恢复
tts.stop()    // 停止

// 5. 获取状态
val status = tts.getStatus()
println("当前状态: ${status.state}")
println("当前句子: ${status.currentSentenceIndex}/${status.totalSentences}")

// 6. 释放资源
tts.release()
```

### 使用回调监听事件

```kotlin
val callback = object : TtsCallback {
    override fun onInitialized(success: Boolean) {
        println("初始化: $success")
    }
    
    override fun onSynthesisStart() {
        println("开始合成")
    }
    
    override fun onSentenceStart(sentenceIndex: Int, sentence: String, totalSentences: Int) {
        println("开始读第 $sentenceIndex 句: $sentence")
    }
    
    override fun onSentenceComplete(sentenceIndex: Int, sentence: String) {
        println("完成第 $sentenceIndex 句")
    }
    
    override fun onStateChanged(newState: TtsPlaybackState) {
        println("状态变更: $newState")
    }
    
    override fun onSynthesisComplete() {
        println("全部完成！")
    }
    
    override fun onPaused() {
        println("已暂停")
    }
    
    override fun onResumed() {
        println("已恢复")
    }
    
    override fun onError(errorMessage: String) {
        println("错误: $errorMessage")
    }
}

tts.speak("你好，世界！这是测试。", callback = callback)
```

### 在 Activity 中使用

```kotlin
class MainActivity : AppCompatActivity() {
    
    private var tts: TtsSynthesizer? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 TTS
        val speaker = Speaker().apply { code = "fn" }
        tts = TtsSynthesizer(this, speaker)
        tts?.initialize()
        
        // 播放长文本
        val text = """
            第一句话。第二句话！第三句话？
            自动分句并顺序播放。
        """
        tts?.speak(text, speed = 50f, volume = 50f)
    }
    
    fun onPauseClicked() {
        tts?.pause()
    }
    
    fun onResumeClicked() {
        tts?.resume()
    }
    
    fun onStopClicked() {
        tts?.stop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tts?.release()
    }
}
```

## 核心功能详解

### 1. 自动分句

TTS 引擎会自动识别以下分句标点：
- 中文：。！？；
- 英文：. ! ? ;

```kotlin
val text = "第一句。第二句！第三句？"
tts.speak(text)  // 自动分为3句依次播放
```

### 2. 播放控制

| 方法 | 说明 |
|------|------|
| `speak(text, speed, volume, callback)` | 开始播放文本 |
| `pause()` | 暂停当前播放 |
| `resume()` | 恢复播放 |
| `stop()` | 停止播放并清空队列 |
| `isSpeaking()` | 检查是否正在播放 |
| `getStatus()` | 获取当前状态 |

### 3. 播放状态

| 状态 | 说明 |
|------|------|
| `UNINITIALIZED` | 未初始化 |
| `IDLE` | 已初始化但未播放 |
| `PLAYING` | 正在播放 |
| `PAUSED` | 已暂停 |
| `STOPPING` | 正在停止 |
| `ERROR` | 发生错误 |

### 4. 回调事件

完整的回调接口包括：

```kotlin
interface TtsCallback {
    fun onInitialized(success: Boolean)
    fun onSynthesisStart()
    fun onSentenceStart(sentenceIndex: Int, sentence: String, totalSentences: Int)
    fun onSentenceComplete(sentenceIndex: Int, sentence: String)
    fun onStateChanged(newState: TtsPlaybackState)
    fun onSynthesisComplete()
    fun onPaused()
    fun onResumed()
    fun onError(errorMessage: String)
}
```

### 5. 状态查询

```kotlin
val status = tts.getStatus()
// TtsStatus(
//     state: TtsPlaybackState,
//     totalSentences: Int,
//     currentSentenceIndex: Int,
//     currentSentence: String,
//     errorMessage: String?
// )
```

## 架构设计

### 核心组件

```
TtsSynthesizer (主控制器)
    ├── SentenceSplitter (句子分割)
    ├── AudioPlayer (音频播放)
    ├── PcmProcessor (音频处理)
    └── SynthesizerNative (原生引擎)
```

### 播放流程

```
文本输入
    ↓
自动分句 (SentenceSplitter)
    ↓
句子队列 (List<String>)
    ↓
逐句处理循环
    ├─ 通知句子开始
    ├─ 合成 PCM (原生引擎)
    ├─ 处理 PCM (Sonic)
    ├─ 播放音频 (AudioTrack)
    └─ 通知句子完成
    ↓
全部完成通知
```

详细架构说明请参阅 [ARCHITECTURE.md](ARCHITECTURE.md)

## 项目结构

```
app/src/main/java/
├── com.hurist.ttsdemo/
│   └── MainActivity.kt              # 示例 Activity
└── com.qq.wx.offlinevoice.synthesizer/
    ├── TtsSynthesizer.kt           # 主合成器 (核心)
    ├── TtsEngine.kt                # TTS 引擎接口
    ├── TtsCallback.kt              # 事件回调接口
    ├── TtsPlaybackState.kt         # 播放状态枚举
    ├── TtsStatus.kt                # 状态数据类
    ├── SentenceSplitter.kt         # 句子分割工具
    ├── AudioPlayer.kt              # 音频播放器
    ├── PcmProcessor.kt             # PCM 处理器
    ├── TtsConstants.kt             # 常量定义
    ├── Speaker.java                # 语音配置
    ├── SynthesizerNative.kt        # JNI 原生接口
    └── Wereader.kt                 # 路径工具
```

## 配置参数

所有配置都在 `TtsConstants` 中定义：

| 参数 | 值 | 说明 |
|------|-----|------|
| DEFAULT_SAMPLE_RATE | 24000 | 默认采样率 |
| PITCH_FACTOR | 0.68f | 音高调整系数 |
| SONIC_SPEED | 0.78f | 速度系数 |
| PCM_BUFFER_SIZE | 64000 | PCM 缓冲区大小 |

## 依赖项

```gradle
dependencies {
    implementation 'androidx.core:core-ktx:1.17.0'
    implementation 'androidx.appcompat:appcompat:1.7.1'
    implementation 'com.google.guava:guava:33.5.0-android'
}
```

## 最佳实践

1. **复用 TTS 实例**: 避免频繁创建和销毁
   ```kotlin
   class MyActivity : AppCompatActivity() {
       private lateinit var tts: TtsSynthesizer
       
       override fun onCreate(savedInstanceState: Bundle?) {
           tts = TtsSynthesizer(this, speaker)
           tts.initialize()
       }
       
       override fun onDestroy() {
           tts.release()
       }
   }
   ```

2. **使用回调监听状态**: 实时掌握播放进度
   ```kotlin
   tts.speak(text, callback = object : TtsCallback {
       override fun onSentenceStart(index: Int, sentence: String, total: Int) {
           updateUI(index, total)
       }
   })
   ```

3. **合理使用控制方法**: 提供良好的用户体验
   ```kotlin
   pauseButton.setOnClickListener { tts.pause() }
   resumeButton.setOnClickListener { tts.resume() }
   stopButton.setOnClickListener { tts.stop() }
   ```

4. **错误处理**: 监听错误并提供反馈
   ```kotlin
   tts.speak(text, callback = object : TtsCallback {
       override fun onError(errorMessage: String) {
           Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
       }
   })
   ```

## 常见问题

### Q: 如何自定义分句规则？

编辑 `SentenceSplitter.kt` 中的 `SENTENCE_DELIMITERS` 正则表达式。

### Q: 如何调整播放速度和音量？

```kotlin
tts.speak(text, speed = 70f, volume = 80f)  // 速度和音量范围 0-100
```

### Q: 如何在播放过程中更换文本？

先调用 `stop()` 停止当前播放，再调用 `speak()` 播放新文本。

### Q: 暂停后能否从当前位置恢复？

可以，调用 `resume()` 会从暂停的句子继续播放。

### Q: 如何知道当前正在播放哪一句？

```kotlin
val status = tts.getStatus()
println("正在播放: ${status.currentSentence}")
println("进度: ${status.currentSentenceIndex}/${status.totalSentences}")
```

## 性能建议

1. **避免频繁重新初始化**: 复用同一个 TTS 实例
2. **合理设置缓冲区**: 默认配置已优化，一般无需修改
3. **及时释放资源**: Activity 销毁时调用 `release()`
4. **监听错误事件**: 出错时及时处理，避免资源泄漏

## 更新日志

### v3.0 (2024-10-29) - 重大更新
- ✅ **完整重构**: 全新的句子级别播放架构
- ✅ **自动分句**: 智能识别句子边界
- ✅ **播放控制**: pause/resume/stop 完整支持
- ✅ **状态管理**: 实时状态查询和回调
- ✅ **完整回调**: 10+ 回调事件全覆盖
- ✅ **优化架构**: 更清晰的职责分离
- ✅ **向后兼容**: 保留旧 API（已标记废弃）

### v2.0 (2024-10-29)
- ✅ 代码重构和文档完善

### v1.0
- 初始版本（逆向工程代码）

## 开发团队

本项目经过专业重构，提供生产级别的代码质量和完整的功能支持。

## 许可证

仅供学习研究使用。

---

**注意**: 本项目使用了微信读书的 TTS 引擎，仅供学习研究使用。

