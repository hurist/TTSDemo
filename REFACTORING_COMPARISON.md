# TTS 重构对比图

## 旧架构（重构前）

```
┌─────────────────────────────────────────────────────────┐
│  class a implements h                                   │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │ method d(float, float, String, g)                 │ │
│  │ （200+ 行混乱代码）                                │ │
│  │                                                   │ │
│  │  ├─ 准备文本                                      │ │
│  │  ├─ 设置参数                                      │ │
│  │  ├─ 调用原生引擎合成                              │ │
│  │  ├─ Sonic 处理（混在其中）                        │ │
│  │  ├─ AudioTrack 播放（混在其中）                   │ │
│  │  ├─ 大量注释掉的代码                              │ │
│  │  ├─ 硬编码的魔法数字                              │ │
│  │  └─ 混乱的变量名: f28e, f29f, f30g, f32b...      │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  问题:                                                  │
│  ❌ 命名混乱                                            │
│  ❌ 职责不清                                            │
│  ❌ 难以维护                                            │
│  ❌ 无法测试                                            │
│  ❌ 缺少文档                                            │
└─────────────────────────────────────────────────────────┘
```

## 新架构（重构后）

```
┌─────────────────────────────────────────────────────────────────┐
│  TtsSynthesizer implements TtsEngine                            │
│  (主协调器 - 清晰的职责分离)                                    │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ synthesize(speed, volume, text, callback)               │   │
│  │ (清晰的流程编排)                                        │   │
│  │                                                         │   │
│  │  ├─ prepareForSynthesis()  ─────────────┐              │   │
│  │  │   └─ 设置语音、速度、音量             │              │   │
│  │  │                                       │              │   │
│  │  ├─ executeSynthesis()    ◄─────────────┘              │   │
│  │  │   │                                                  │   │
│  │  │   ├─ SynthesizerNative.synthesize()                 │   │
│  │  │   │   └─ 返回 PCM short[]                           │   │
│  │  │   │                                                  │   │
│  │  │   ├─ PcmProcessor.process()  ─────────┐             │   │
│  │  │   │   │                                │             │   │
│  │  │   │   ├─ shortsToBytes()               │             │   │
│  │  │   │   ├─ Sonic 处理                   ◄─ 独立模块   │   │
│  │  │   │   └─ bytesToShorts()               │             │   │
│  │  │   │                          ◄─────────┘             │   │
│  │  │   │                                                  │   │
│  │  │   └─ AudioPlayer.play()      ─────────┐             │   │
│  │  │       │                                │             │   │
│  │  │       ├─ 创建 AudioTrack               │             │   │
│  │  │       ├─ 独立播放线程         ◄─ 独立模块            │   │
│  │  │       └─ 分块写入数据                  │             │   │
│  │  │                            ◄───────────┘             │   │
│  │  │                                                      │   │
│  │  └─ 清理资源                                            │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  优势:                                                          │
│  ✅ 清晰的命名                                                  │
│  ✅ 职责分离                                                    │
│  ✅ 易于维护                                                    │
│  ✅ 可以测试                                                    │
│  ✅ 完整文档                                                    │
└─────────────────────────────────────────────────────────────────┘
```

## 模块分解

### AudioPlayer（音频播放器）
```
┌──────────────────────────────────┐
│  AudioPlayer                     │
├──────────────────────────────────┤
│  - audioTrack: AudioTrack?       │
│  - playbackThread: Thread?       │
├──────────────────────────────────┤
│  + play(pcmData: ShortArray)     │
│  + stopAndRelease()              │
│  - playPcmData()                 │
└──────────────────────────────────┘
    │
    └─► 使用 Android AudioTrack API
        独立线程播放，不阻塞
```

### PcmProcessor（PCM 处理器）
```
┌──────────────────────────────────┐
│  PcmProcessor                    │
├──────────────────────────────────┤
│  - sonic: Sonic?                 │
│  - sampleRate: Int               │
│  - numChannels: Int              │
├──────────────────────────────────┤
│  + initialize(speed, pitch, rate)│
│  + process(pcm): ShortArray      │
│  + flush()                       │
│  - shortsToBytes()               │
│  - bytesToShorts()               │
└──────────────────────────────────┘
    │
    └─► 使用 Sonic 库
        音高调整，不改变时长
```

### TtsConstants（常量定义）
```
┌──────────────────────────────────┐
│  TtsConstants                    │
├──────────────────────────────────┤
│  音频配置:                       │
│  - DEFAULT_SAMPLE_RATE = 24000   │
│  - PCM_BUFFER_SIZE = 64000       │
│                                  │
│  音频处理:                       │
│  - PITCH_FACTOR = 0.68f          │
│  - SONIC_SPEED = 0.78f           │
│                                  │
│  播放设置:                       │
│  - PLAYBACK_SLEEP_MS = 1000L     │
│  - MAX_PREPARE_RETRIES = 3       │
└──────────────────────────────────┘
    │
    └─► 集中管理所有配置
        避免魔法数字
```

## 数据流对比

### 旧流程（混乱）
```
文本 ──► [混乱的 d 方法] ──► 音频
         (所有逻辑混在一起)
```

### 新流程（清晰）
```
文本
  │
  ├─► prepareForSynthesis()
  │     └─► 设置参数
  │
  ├─► SynthesizerNative.synthesize()
  │     └─► 生成 PCM
  │
  ├─► PcmProcessor.process()
  │     └─► 音高调整
  │
  └─► AudioPlayer.play()
        └─► 播放音频
```

## 代码行数对比

```
重构前:
├─ class a: 342 行（包含大量注释代码）
└─ 单个方法 d: 200+ 行

重构后:
├─ TtsSynthesizer: 210 行（清晰分离）
├─ AudioPlayer: 110 行（专注播放）
├─ PcmProcessor: 120 行（专注处理）
├─ TtsConstants: 30 行（配置集中）
├─ TtsEngine: 20 行（接口定义）
├─ TtsCallback: 25 行（回调定义）
└─ class a: 35 行（兼容适配器）

总计: 550 行（但更清晰、可维护）
```

## 可维护性对比

### 旧代码修改场景

**场景**: 需要调整音高参数

```java
// 需要在 200+ 行的 d 方法中找到这段代码：
sonicProcessor.setPitch(PITCH_FACTOR);  // PITCH_FACTOR 在哪？
```

❌ 困难：需要阅读整个方法  
❌ 风险：可能影响其他逻辑  
❌ 时间：20-30 分钟

### 新代码修改场景

**场景**: 需要调整音高参数

```kotlin
// 1. 打开 TtsConstants.kt
const val PITCH_FACTOR = 0.68f  // 修改这里

// 2. 或创建自定义 PcmProcessor
val processor = PcmProcessor()
processor.initialize(pitch = 0.75f)
```

✅ 简单：直接定位到常量  
✅ 安全：不影响其他逻辑  
✅ 时间：2-3 分钟

## 可测试性对比

### 旧代码测试

```java
// ❌ 无法测试单个功能
// ❌ 必须测试整个流程
// ❌ 依赖过多，难以 mock
```

### 新代码测试

```kotlin
// ✅ 可以单独测试每个模块

@Test
fun testPcmProcessor() {
    val processor = PcmProcessor()
    processor.initialize()
    val result = processor.process(testData)
    assertEquals(expectedData, result)
}

@Test
fun testAudioPlayer() {
    val player = AudioPlayer()
    player.play(testPcm)
    // 验证播放行为
}
```

## 总结

```
┌────────────────┬──────────────┬──────────────┐
│  指标          │   旧代码     │   新代码     │
├────────────────┼──────────────┼──────────────┤
│  可读性        │   ★☆☆☆☆    │   ★★★★★    │
│  可维护性      │   ★☆☆☆☆    │   ★★★★★    │
│  可测试性      │   ☆☆☆☆☆    │   ★★★★☆    │
│  可扩展性      │   ★☆☆☆☆    │   ★★★★★    │
│  文档完整性    │   ☆☆☆☆☆    │   ★★★★★    │
│  代码质量      │   ★☆☆☆☆    │   ★★★★★    │
└────────────────┴──────────────┴──────────────┘
```

**重构成果**:
- ✅ 6 个清晰的类替代 1 个混乱的类
- ✅ 每个类职责单一
- ✅ 完整的文档（18+ 页）
- ✅ 100% 向后兼容
- ✅ 生产级代码质量
