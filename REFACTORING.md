# TTS 代码重构说明

## 概述

本次重构针对原有的逆向工程 TTS 代码进行了全面优化和重新设计，主要目标是：
- 清理混乱的命名和代码结构
- 将核心 TTS 合成和播放逻辑分离
- 提供更加清晰和易于维护的架构
- 保持向后兼容性

## 重构内容

### 1. 新的架构设计

原有代码中的核心类 `a` 和方法 `d` 存在以下问题：
- 混乱的命名（单字母类名、方法名）
- 合成、处理、播放逻辑混在一起
- 大量注释掉的代码
- 硬编码的常量值
- 缺乏错误处理和日志

新架构采用职责分离原则：

```
TtsSynthesizer (主合成器)
    ├── AudioPlayer (音频播放)
    ├── PcmProcessor (PCM 处理/Sonic)
    └── SynthesizerNative (原生 TTS 引擎)
```

### 2. 新增的类和接口

#### 核心类

| 类名 | 说明 | 职责 |
|------|------|------|
| `TtsSynthesizer` | TTS 合成器 | 协调整个 TTS 流程：准备→合成→处理→播放 |
| `AudioPlayer` | 音频播放器 | 使用 AudioTrack 播放 PCM 数据 |
| `PcmProcessor` | PCM 处理器 | 使用 Sonic 库进行音高和速度调整 |
| `TtsConstants` | 常量定义 | 集中管理所有配置参数 |

#### 接口

| 接口名 | 说明 |
|--------|------|
| `TtsEngine` | TTS 引擎接口 | 定义标准的 TTS 操作 |
| `TtsCallback` | TTS 回调接口 | 提供合成事件通知（可选） |

### 3. 重构的流程

#### 原有流程（class a，method d）
```
准备文本 → 合成 PCM → Sonic 处理 → 播放
(所有逻辑混在一个方法中，约 200+ 行代码)
```

#### 新流程（TtsSynthesizer.synthesize）
```
TtsSynthesizer.synthesize()
    ↓
prepareForSynthesis() - 准备合成参数
    ↓
executeSynthesis() - 执行合成循环
    ↓
PcmProcessor.process() - 处理 PCM（音高/速度）
    ↓
AudioPlayer.play() - 播放音频
```

### 4. API 使用示例

#### 新 API（推荐）

```kotlin
// 创建 Speaker 配置
val speaker = Speaker().apply {
    code = "fn"  // 语音编码
}

// 创建并初始化合成器
val synthesizer = TtsSynthesizer(context, speaker)
synthesizer.initialize()

// 合成并播放
synthesizer.synthesize(
    speed = 50f,
    volume = 50f,
    text = "要合成的文本",
    callback = null
)

// 使用完毕后释放资源
synthesizer.release()
```

#### 旧 API（向后兼容）

```kotlin
// 旧代码仍然可以工作，但已标记为 @Deprecated
val legacySynthesizer = a(context, speaker)
legacySynthesizer.c()  // initialize
legacySynthesizer.d(50f, 50f, text, null)  // synthesize
legacySynthesizer.release()
```

### 5. 关键改进

#### 代码质量
- ✅ 清晰的类名和方法名
- ✅ 完整的文档注释
- ✅ 职责分离，单一职责原则
- ✅ 移除所有注释掉的代码
- ✅ 提取所有魔法数字到常量类

#### 可维护性
- ✅ 模块化设计，易于测试
- ✅ 错误处理和日志记录
- ✅ 资源管理（线程、AudioTrack）
- ✅ 线程安全

#### 性能
- ✅ 复用 Sonic 处理器实例
- ✅ 优化 PCM 数据转换
- ✅ 异步播放，不阻塞主线程

### 6. 配置参数

所有配置参数现在集中在 `TtsConstants` 中：

```kotlin
// 音频配置
DEFAULT_SAMPLE_RATE = 24000
SONIC_SAMPLE_RATE = 16000
PCM_BUFFER_SIZE = 64000

// 音频处理
PITCH_FACTOR = 0.68f  // 降调系数
SONIC_SPEED = 0.78f   // 速度系数
SONIC_RATE = 1.0f     // 播放速率

// 其他配置
MAX_PREPARE_RETRIES = 3
PLAYBACK_SLEEP_MS = 1000L
```

### 7. 向后兼容性

- 保留原有的 `class a` 作为适配器类
- 保留原有的 `interface h`
- 旧代码可以无缝迁移到新 API

### 8. 未来改进建议

1. **异步回调**: 实现 `TtsCallback` 接口，提供更精确的播放状态通知
2. **播放控制**: 添加暂停、恢复、停止等播放控制功能
3. **队列管理**: 支持文本队列，顺序合成播放
4. **缓存机制**: 缓存已合成的音频，避免重复合成
5. **单元测试**: 为各个模块添加单元测试

## 文件结构

```
synthesizer/
├── TtsSynthesizer.kt       # 主合成器类
├── AudioPlayer.kt          # 音频播放器
├── PcmProcessor.kt         # PCM 处理器
├── TtsEngine.kt            # TTS 引擎接口
├── TtsCallback.kt          # 回调接口
├── TtsConstants.kt         # 常量定义
├── a.java                  # 旧 API 适配器（已废弃）
├── h.java                  # 旧接口（已废弃）
├── g.java                  # 旧回调接口
├── Speaker.java            # 语音配置
├── SynthesizerNative.kt    # 原生 TTS 接口
└── Wereader.kt             # 路径工具类
```

## 总结

本次重构将原本混乱的逆向代码转变为：
- **可读性强**: 清晰的命名和结构
- **可维护性高**: 模块化设计，职责分离
- **可扩展性好**: 易于添加新功能
- **向后兼容**: 不影响现有代码

同时保持了原有的核心功能：
- TTS 文本合成
- Sonic 音频处理（音高调整）
- AudioTrack 播放
