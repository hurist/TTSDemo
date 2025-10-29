# TTSDemo - Text-to-Speech 示例项目

一个基于微信读书 TTS 引擎的文本转语音示例项目，经过完整重构，提供清晰、可维护的代码架构。

## 项目概述

本项目展示了如何使用原生 TTS 引擎进行文本合成，并通过 Sonic 库进行音频处理（音高调整），最后使用 Android AudioTrack 播放。

### 主要特性

- ✅ 高质量的中文 TTS 合成
- ✅ 音高和速度调节（基于 Sonic 库）
- ✅ 清晰的模块化架构
- ✅ 完善的文档
- ✅ 向后兼容的 API

## 快速开始

### 基本用法

```kotlin
// 1. 创建语音配置
val speaker = Speaker().apply {
    code = "fn"  // 语音编码
}

// 2. 创建并初始化 TTS 合成器
val synthesizer = TtsSynthesizer(context, speaker)
synthesizer.initialize()

// 3. 合成并播放文本
synthesizer.synthesize(
    speed = 50f,
    volume = 50f,
    text = "你好，世界！",
    callback = null
)

// 4. 使用完毕后释放资源
synthesizer.release()
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
        
        // 合成文本
        tts?.synthesize(50f, 50f, "这是一个测试", null)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tts?.release()
    }
}
```

## 架构设计

### 核心组件

```
TtsSynthesizer (主合成器)
    ├── AudioPlayer (音频播放)
    ├── PcmProcessor (音频处理)
    └── SynthesizerNative (原生引擎)
```

### 数据流

```
文本输入
    ↓
准备合成 (设置参数、语音)
    ↓
合成 PCM (原生 TTS 引擎)
    ↓
处理 PCM (Sonic 音高调整)
    ↓
播放音频 (AudioTrack)
```

详细架构说明请参阅 [ARCHITECTURE.md](ARCHITECTURE.md)

## 重构说明

本项目代码经过完整重构，将原本混乱的逆向工程代码转换为清晰、可维护的架构。

### 重构前

- 混乱的命名：class `a`, method `d`, 变量 `f28e`, `f29f`
- 所有逻辑混在一个 200+ 行的方法中
- 大量注释掉的代码
- 硬编码的常量
- 缺乏文档

### 重构后

- 清晰的命名：`TtsSynthesizer`, `AudioPlayer`, `PcmProcessor`
- 职责分离：合成、处理、播放各司其职
- 移除所有无用代码
- 配置集中管理（`TtsConstants`）
- 完善的文档和注释

详细重构说明请参阅 [REFACTORING.md](REFACTORING.md)

## 文档

- **[ARCHITECTURE.md](ARCHITECTURE.md)** - 完整的架构文档
  - 组件说明
  - 数据流图
  - 线程模型
  - 性能优化

- **[REFACTORING.md](REFACTORING.md)** - 重构说明
  - 重构前后对比
  - 改进点
  - API 使用示例

- **[MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)** - 迁移指南
  - 从旧 API 迁移到新 API
  - 代码示例
  - 最佳实践

## 项目结构

```
app/src/main/java/
├── com.hurist.ttsdemo/
│   └── MainActivity.kt              # 示例 Activity
└── com.qq.wx.offlinevoice.synthesizer/
    ├── TtsSynthesizer.kt           # 主合成器
    ├── AudioPlayer.kt              # 音频播放器
    ├── PcmProcessor.kt             # PCM 处理器
    ├── TtsEngine.kt                # TTS 引擎接口
    ├── TtsCallback.kt              # 回调接口
    ├── TtsConstants.kt             # 常量定义
    ├── Speaker.java                # 语音配置
    ├── SynthesizerNative.kt        # JNI 原生接口
    ├── Wereader.kt                 # 路径工具
    └── [legacy files]              # 旧版本兼容文件
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
    // Android
    implementation 'androidx.core:core-ktx:1.17.0'
    implementation 'androidx.appcompat:appcompat:1.7.1'
    
    // Guava (用于工具类)
    implementation 'com.google.guava:guava:33.5.0-android'
    
    // Native libraries
    // - hwTTS (原生 TTS 引擎)
    // - weread-tts (微信读书 TTS)
}
```

## 向后兼容

旧代码仍可使用（标记为 `@Deprecated`）：

```kotlin
// 旧 API（不推荐，但仍可用）
val oldTts = a(context, speaker)
oldTts.c()
oldTts.d(50f, 50f, "文本", null)
```

建议迁移到新 API 以获得更好的可维护性和文档支持。

## 常见问题

### Q: 如何更改语音？

```kotlin
val speaker = Speaker().apply {
    code = "fn"  // 更改为其他语音编码，如 "F191", "M001" 等
}
```

### Q: 如何调整音高和速度？

音高和速度在 `TtsConstants` 中配置。如需自定义，可以创建新的 `PcmProcessor` 实例。

### Q: 支持哪些语言？

取决于加载的语音数据文件。当前配置支持中文。

### Q: 如何处理播放完成事件？

实现 `TtsCallback` 接口：

```kotlin
val callback = object : TtsCallback {
    override fun onSynthesisComplete() {
        // 播放完成
    }
}

synthesizer.synthesize(50f, 50f, text, callback)
```

## 性能建议

1. **复用合成器**: 避免频繁创建和销毁 `TtsSynthesizer`
2. **异步处理**: 在后台线程中调用 `synthesize()`
3. **资源管理**: 及时调用 `release()` 释放资源

## 开发团队

本项目经过专业重构，提供生产级别的代码质量。

## 许可证

[添加你的许可证信息]

## 更新日志

### v2.0 (2024-10-29)
- ✅ 完整重构代码架构
- ✅ 分离合成、处理、播放逻辑
- ✅ 添加完整文档
- ✅ 保持向后兼容

### v1.0
- 初始版本（逆向工程代码）

---

**注意**: 本项目使用了微信读书的 TTS 引擎，仅供学习研究使用。
