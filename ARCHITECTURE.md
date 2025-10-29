# TTS 系统架构文档（v3.0）

## 架构概览

本 TTS (Text-to-Speech) 系统采用模块化设计，实现了完整的句子级播放控制、状态管理和事件回调系统。

## 核心组件

### 1. TtsSynthesizer（主合成器）
**文件**: `TtsSynthesizer.kt`

**职责**:
- 协调整个 TTS 流程和句子队列管理
- 实现播放控制（播放、暂停、恢复、停止）
- 管理播放状态和线程同步
- 提供完整的事件回调

**关键方法**:
```kotlin
fun initialize()                                        // 初始化 TTS 引擎
fun speak(text, speed, volume, callback)               // 播放文本（自动分句）
fun pause()                                            // 暂停播放
fun resume()                                           // 恢复播放
fun stop()                                             // 停止播放
fun getStatus(): TtsStatus                             // 获取当前状态
fun isSpeaking(): Boolean                              // 检查是否正在播放
fun release()                                          // 释放资源
```

**工作流程**:
```
文本输入 
  ↓
自动分句 (SentenceSplitter) -----> 识别句子边界
  ↓                                创建句子队列
句子队列循环
  ↓
prepareForSynthesis() -----------> 设置语音、速度、音量
  ↓                                重试机制（最多3次）
synthesizeSentence() ------------> 合成单个句子
  ↓                                检查暂停/停止标志
PcmProcessor.process() ----------> 音高和速度调整
  ↓                                Sonic 算法处理
AudioPlayer.play() --------------> 播放处理后的音频
  ↓
检查暂停状态 (checkPauseState) --> 等待恢复或继续
  ↓
下一句/完成
```

### 2. SentenceSplitter（句子分割器）
**文件**: `SentenceSplitter.kt`

**职责**:
- 自动识别句子边界（中英文标点）
- 将长文本分割为句子列表
- 过滤无效或过短的片段

**关键方法**:
```kotlin
fun splitIntoSentences(text): List<String>         // 分句（去除分隔符）
fun splitWithDelimiters(text): List<String>        // 分句（保留分隔符）
```

**支持的分隔符**: 。！？；.!?;

### 3. AudioPlayer（音频播放器）
**文件**: `AudioPlayer.kt`

**职责**:
- 使用 Android AudioTrack API 播放 PCM 数据
- 管理播放线程和缓冲区
- 处理播放状态和错误

**特性**:
- 独立播放线程，不阻塞主线程
- 自动计算最佳缓冲区大小
- 优雅的资源清理和错误处理

**关键方法**:
```kotlin
fun play(pcmData: ShortArray)    // 播放 PCM 数据
fun stopAndRelease()             // 停止并释放资源
```

### 3. PcmProcessor（PCM 处理器）
**文件**: `PcmProcessor.kt`

**职责**:
- 使用 Sonic 库处理 PCM 音频
- 调整音高（pitch）而不改变速度
- 字节序转换（Short ↔ Byte）

**配置参数**:
- `PITCH_FACTOR`: 0.68f （降低音高约 6 个半音）
- `SONIC_SPEED`: 0.78f
- `SONIC_RATE`: 1.0f

**关键方法**:
```kotlin
fun initialize(speed, pitch, rate)   // 初始化 Sonic
fun process(inputPcm): ShortArray    // 处理 PCM 数据
fun flush()                          // 刷新缓冲区
```

### 4. TtsConstants（常量定义）
**文件**: `TtsConstants.kt`

**包含的配置**:

| 类别 | 常量名 | 值 | 说明 |
|------|--------|-----|------|
| 音频配置 | DEFAULT_SAMPLE_RATE | 24000 | 默认采样率 |
| | SONIC_SAMPLE_RATE | 16000 | Sonic 处理采样率 |
| | NUM_CHANNELS | 1 | 单声道 |
| | PCM_BUFFER_SIZE | 64000 | PCM 缓冲区大小 |
| 音频处理 | PITCH_FACTOR | 0.68f | 音高调整系数 |
| | SONIC_SPEED | 0.78f | 速度系数 |
| | SONIC_RATE | 1.0f | 播放速率 |
| | SONIC_QUALITY | 1 | 处理质量 |
| 播放设置 | MIN_BUFFER_SIZE_FALLBACK | 2048 | 最小缓冲区回退值 |
| | CHUNK_SIZE_MIN | 1024 | 最小块大小 |
| | PLAYBACK_SLEEP_MS | 1000L | 播放等待时间 |
| 其他 | MAX_PREPARE_RETRIES | 3 | 最大准备重试次数 |
| | SPEED_VOLUME_SCALE | 50.0f | 速度/音量缩放因子 |

## 数据流

### 完整的数据流转过程

```
用户输入文本
    ↓
TtsSynthesizer.synthesize()
    ↓
【1. 准备阶段】
prepareForSynthesis()
  - 设置语音编码（voice code）
  - 设置速度和音量
  - 调用 prepareUTF8()（带重试）
    ↓
【2. 合成阶段】
executeSynthesis() - 循环处理
  while (!cancelled) {
    SynthesizerNative.synthesize()
      → 返回 PCM short[] 数据
      ↓
    【3. 处理阶段】
    PcmProcessor.process()
      → shortsToBytes() 转换
      → Sonic.writeBytesToStream()
      → Sonic.readBytesFromStream()
      → bytesToShorts() 转回
      ↓
    【4. 播放阶段】
    AudioPlayer.play()
      → 创建 AudioTrack
      → 在独立线程中播放
      → 分块写入音频数据
      ↓
    等待播放（sleep）
  }
    ↓
【5. 清理阶段】
  - PcmProcessor.flush()
  - SynthesizerNative.reset()
```

## 类关系图

```
TtsSynthesizer
    |
    ├─── implements TtsEngine
    |
    ├─── uses AudioPlayer
    |        └─── uses AudioTrack (Android API)
    |
    ├─── uses PcmProcessor
    |        └─── uses Sonic (第三方库)
    |
    └─── uses SynthesizerNative
             └─── JNI 调用原生 TTS 库

TtsEngine (interface)
    └─── 定义标准 TTS 操作

TtsCallback (interface)
    └─── 可选的事件回调

TtsConstants (object)
    └─── 全局配置常量
```

## 向后兼容层

### 旧 API（已废弃但保留）

```
a.java (废弃)           →  委托给 TtsSynthesizer
h.java (废弃接口)        →  被 TtsEngine 替代
g.java (旧回调)         →  被 TtsCallback 替代
i.java (旧工具类)        →  功能已迁移到 Wereader.kt
```

## 使用示例

### 基础用法

```kotlin
// 1. 创建语音配置
val speaker = Speaker().apply {
    code = "fn"  // 语音编码，如 fn, F191 等
}

// 2. 创建合成器
val synthesizer = TtsSynthesizer(context, speaker)

// 3. 初始化
synthesizer.initialize()

// 4. 合成并播放
synthesizer.synthesize(
    speed = 50f,      // 速度 0-100
    volume = 50f,     // 音量 0-100
    text = "你好，世界",
    callback = null   // 可选回调
)

// 5. 释放资源
synthesizer.release()
```

### 高级用法（带回调）

```kotlin
val callback = object : TtsCallback {
    override fun onSynthesisStart() {
        Log.d(TAG, "合成开始")
    }
    
    override fun onPcmDataAvailable(pcmData: ByteArray, length: Int) {
        Log.d(TAG, "PCM 数据就绪: $length bytes")
    }
    
    override fun onSynthesisComplete() {
        Log.d(TAG, "合成完成")
    }
    
    override fun onError(errorMessage: String) {
        Log.e(TAG, "合成错误: $errorMessage")
    }
}

synthesizer.synthesize(50f, 50f, "测试文本", callback)
```

## 线程模型

```
主线程 (Main Thread)
    └─── TtsSynthesizer.synthesize() 
          └─── 创建工作线程 (Worker Thread)
                ├─── executeSynthesis()
                │     ├─── SynthesizerNative (可能阻塞)
                │     └─── PcmProcessor (CPU 密集)
                │
                └─── AudioPlayer.play()
                      └─── 创建播放线程 (Playback Thread)
                            └─── AudioTrack.write() (循环写入)
```

**线程安全**:
- `TtsSynthesizer` 的公共方法都使用 `synchronized`
- `AudioPlayer` 使用独立线程播放
- 取消操作使用 `volatile` 标志

## 性能优化

1. **Sonic 实例复用**: PcmProcessor 复用同一个 Sonic 实例
2. **缓冲区预分配**: PCM 缓冲区预先分配，避免频繁 GC
3. **分块播放**: 音频数据分块写入，减少内存占用
4. **异步处理**: 合成和播放在后台线程进行

## 错误处理

- **合成失败**: 最多重试 3 次
- **播放错误**: 捕获 IllegalStateException，优雅降级
- **资源泄漏**: 所有资源都在 finally 块中清理
- **日志记录**: 完整的错误日志便于调试

## 扩展点

### 1. 添加播放控制
```kotlin
class AudioPlayer {
    fun pause()     // 暂停播放
    fun resume()    // 恢复播放
    fun seek(ms)    // 跳转位置
}
```

### 2. 实现队列管理
```kotlin
class TtsQueue {
    fun enqueue(text)
    fun playNext()
    fun clear()
}
```

### 3. 缓存合成结果
```kotlin
class TtsCache {
    fun get(text): PCM?
    fun put(text, pcm)
}
```

## 测试建议

### 单元测试
- `PcmProcessor`: 测试 PCM 转换和 Sonic 处理
- `AudioPlayer`: 测试播放逻辑和资源管理
- `TtsConstants`: 验证常量值的合理性

### 集成测试
- 完整的合成→处理→播放流程
- 取消操作的及时性
- 资源释放的完整性

### 性能测试
- 不同长度文本的合成时间
- 内存占用情况
- CPU 使用率

## 依赖项

- **Android SDK**: AudioTrack, Context
- **Sonic**: 第三方音频处理库
- **Native Libraries**: hwTTS, weread-tts
- **Kotlin Coroutines**: 用于异步操作（MainActivity）

## 总结

本架构通过职责分离、模块化设计，将原本混乱的逆向代码重构为：
- ✅ 清晰的类职责
- ✅ 完整的文档
- ✅ 良好的可测试性
- ✅ 易于维护和扩展
- ✅ 向后兼容
