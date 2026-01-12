# TTSLibrary 模块文档

TTSLibrary 是一个面向 Android 的双模（在线 + 离线）文本转语音组件，封装了句子切分、文本归一化、缓存、网络容错、预下载以及本地播放器等能力。模块以 `TtsSynthesizer` 为核心入口，提供 Actor 风格的异步控制 API，并对外暴露状态、进度与日志回调。

## 目录结构

```
TTSLibrary/
├─ build.gradle.kts               // 模块依赖与编译配置（compileSdk=36，jniLibs 指向 libs/）
├─ libs/                          // 随库分发的离线引擎 so（hwTTS / weread-tts）
└─ src/main/java/com/qq/wx/offlinevoice/synthesizer
   ├─ TtsSynthesizer.kt           // 核心入口，Actor 命令通道 + 播放状态管理
   ├─ TtsStrategy.kt              // ONLINE_ONLY / ONLINE_PREFERRED / OFFLINE_ONLY 策略
   ├─ Speaker.kt                  // 发音人枚举（含在线模型名与离线模型名映射）
   ├─ TtsCallback.kt              // 上层事件回调接口
   ├─ TtsRepository.kt            // 在线拉流、MP3 解码与二级缓存协调
   ├─ AudioPlayer.kt, AudioSpeedProcessor.kt, Sonic.java // PCM 播放与变速处理
   ├─ SentenceSplitter*.kt        // 句子分割策略
   ├─ StringExtension.kt, WrTextUtil.kt // 文本预处理与空标点判断
   ├─ NetworkMonitor.kt           // 网络质量观测（StateFlow<Boolean>）
   ├─ cache/                      // TtsCache 接口 + L1/L2 缓存实现 (内存 + DiskLruCache)
   ├─ normalizer/                 // 简繁体归一化 (SimplifiedTtsNormalizer / TraditionalTtsNormalizer)
   ├─ online/                     // WxReaderApi（在线拉流）、Mp3Decoder/MediaCodecMp3Decoder、token 管理
   ├─ preload/                    // PreloadManager / PreloadJob 预下载入口
   └─ disklrucache/               // 轻量级 DiskLruCache 实现
```

## 快速上手

1. **准备资源与依赖**
   - minSdk 21，Java/Kotlin 11。
   - libs 目录内置 `libhwTTS.so`、`libweread-tts.so`，无需额外配置，`sourceSets["main"].jniLibs.srcDir("libs")` 已在模块内设定。
   - 需要将离线语音数据解压到 `PathUtils.getTtsResourcePath(context)/voices` 下（例：`dtn.bin`、`lsl.bin`、`F191.bin` 等）。示例应用使用 `AssetUnpacker.ensureResourcesAreReady()` 从 `app/src/main/assets` 自动下发。
   - 如需在线合成，请确保能够获取 token 与 uid。库内提供 `WxTokenManager`（可设置自定义 token 接口）。

2. **初始化示例**

```kotlin
// Application 级别可选：初始化预加载管理器（在线预取）
PreloadManager.initialize(appContext)

// 运行时准备离线资源（示例同 Demo 应用）
AssetUnpacker.ensureResourcesAreReady(context)

// 创建合成器并设置回调
val tts = TtsSynthesizer(
    context = context,
    speaker = Speaker.MALE1,
    currentCallback = object : TtsCallback {
        override fun onInitialized(error: Throwable?) { /* ready */ }
        override fun onSentenceStart(index: Int, sentence: String, total: Int, mode: SynthesisMode, startPos: Int, endPos: Int, triggerReason: String?) {}
        override fun onSentenceProgressChanged(index: Int, sentence: String, progress: Int, char: String, startPos: Int, endPos: Int) {}
        override fun onSynthesisComplete() {}
        override fun onStateChanged(newState: TtsPlaybackState) {}
        override fun onLog(level: Level, logMessage: String) {}
        override fun onError(errorMessage: String) {}
    }
)

// 在线鉴权（可选，按需调用）
tts.setToken(token = "<server-token>", uid = 123L)

// 策略设置：在线优先 / 仅在线 / 仅离线
tts.setStrategy(TtsStrategy.ONLINE_PREFERRED)

// 播放控制
tts.speak("你好，世界！")
tts.setSpeed(1.2f)
tts.setVoice(Speaker.FEMALE1)
tts.seekToSentence(2)          // 按句跳转
tts.pause()
tts.resume()
tts.stop()

// 释放
tts.release()
```

3. **预加载在线缓存（可选）**

```kotlin
PreloadManager.initialize(appContext) // 建议在 Application.onCreate 调用一次
PreloadManager.getInstance(context).preload(
    content = "要预读的章节内容",
    speaker = Speaker.MALE1,
    isTtsTextTraditional = false
) { result -> /* 预加载完成或失败回调 */ }
```

4. **缓存与清理**
   - 在线音频：L1 内存 + L2 磁盘（`TtsCacheImpl`），键为 `SHA-1(speaker + text)`。
   - 解码后的 PCM：`TtsRepository` 内部 LruCache。
   - 对外提供 `tts.clearCache()` 清理缓存，需在空闲态使用。

## 核心 API 概览

| 类型 | 作用 |
| --- | --- |
| `TtsSynthesizer` | 核心入口。公开 `speak/pause/resume/stop/release/setSpeed/setVolume/setVoice/setStrategy/seekToSentence/setToken/clearCache/isSpeaking/getStatus` 等方法。内部通过 Channel + 协程驱动播放、退避与状态机。 |
| `TtsCallback` | 事件回调：初始化完成、句子开始/进度/完成、整体完成、状态变化、暂停/恢复、错误、日志、在线合成错误、按句 seek 完成。 |
| `TtsStrategy` | 合成策略：`OFFLINE_ONLY`、`ONLINE_PREFERRED`、`ONLINE_ONLY`。`SynthesisStrategyManager` 根据网络状态（`NetworkMonitor.isNetworkGood`）选择 ONLINE/OFFLINE。 |
| `Speaker` | 发音人枚举，内含在线模型名与对应离线资源名（`offlineModelName`）。提供 `isResourceAvailable(context)` 检查离线包是否就绪。 |
| `TtsPlaybackState` | 播放状态：`IDLE / PLAYING / PAUSED / BUFFERING`。 |
| `TtsStatus` | 对外的简化状态模型（当前状态、总句数、当前句索引、当前句文本）。 |
| `PreloadManager` | 在线预下载入口，支持并发数/任务数配置，网络恢复自动重试。 |
| `WxTokenManager`/`TokenProvider` | 在线 token 获取与自动刷新（-13 失效时单飞刷新）。 |

## 内部流程（简述）

1. **命令入口**：上层调用 `TtsSynthesizer` 的公开方法，命令写入 `Channel<Command>`，由单协程顺序处理，保证线程安全。
2. **文本预处理**：可选简繁体归一（`TraditionalTtsNormalizer` / `SimplifiedTtsNormalizer`）→ 文本清洗 (`processForTts`)。
3. **句子切分**：`SentenceSplitter` 按换行/终止/停顿标点 + 长度限制生成 `TtsBag` 队列，附带原始位置、行号映射，支持 `beginPos`、按句 seek。
4. **策略与模式选择**：`SynthesisStrategyManager` 基于 `TtsStrategy` 与 `NetworkMonitor` 决定 `SynthesisMode`（ONLINE / OFFLINE）。在线失败会指数退避并自动降级。
5. **音频获取**：
   - **离线**：`SynthesizerNative` 调用 `libhwTTS`/`libweread-tts`，支持动态语速、音量、发音人；PCM 直接送入播放器。
   - **在线**：`TtsRepository` 优先查缓存 → WxReaderApi 拉流 → MP3 解码 (`MediaCodecMp3Decoder`/`Mp3Decoder`) → 缓存命中回填。
6. **播放与回调**：`AudioPlayer` 按句流式播放，`AudioSpeedProcessor` 变速；`TtsSynthesizer` 统一触发 `TtsCallback` 事件（start/progress/complete/state/log/error），并维护 BUFFERING 去抖、防抖加载窗与按句 seek。
7. **预加载**：`PreloadManager`/`PreloadJob` 可在阅读前批量拉取在线音频并落盘，网络恢复后自动重启未完成任务。

## 常见集成注意事项

- **资源路径**：`PathUtils.getTtsResourcePath(context)` 会根据 Debug/Release 选择外部或内部目录；确保 `voices/*.bin` 存在，否则 `Speaker.isResourceAvailable` 会返回 false。
- **网络监听**：模块自动注册 `NetworkMonitor` 回调；在不再使用时调用 `TtsSynthesizer.release()` 以释放网络、播放器与协程资源。
- **日志与排查**：调用 `AppLogger.setCallback` 可接收库内日志（已在 `TtsSynthesizer` 构造时注册），便于 UI 展示。
- **线程模型**：外部 API 为非阻塞调用；回调可能在后台线程触发，UI 更新需切回主线程（示例应用中使用 `runOnUiThread`）。

## 开发与测试

- 推荐命令：`./gradlew test`（运行单元测试）与 `./gradlew :app:connectedAndroidTest`（仪器测试）。  
- 当前分支在本地执行 `./gradlew test` 时受插件解析阻塞：构建无法解析 `com.android.application:8.12.3` 插件（Google/MavenCentral 未提供）；此文档变更未修改构建配置，如需运行测试请先解决插件版本可用性或配置自有仓库。

