# TTSLibrary ProGuard/R8 混淆规则说明

本文档说明了为 TTSLibrary 配置的 ProGuard/R8 混淆规则。

## 最近更新 (2025-11-25)

重新整理和完善了整个混淆配置：
- ✅ 重组规则结构，按功能分类，增强可读性和可维护性
- ✅ 完善公共 API 保护：`TtsSynthesizer`、`TtsCallback`、`PreloadManager`
- ✅ 完善枚举类保护：`TtsPlaybackState`、`TtsStrategy`、`SynthesisMode`、`SentenceSplitterStrategy`、`Level`、`Speaker`
- ✅ 完善数据类保护：`TtsStatus`、`DecodedPcm`、`AppLoggerConfig`、`PreloadManager.Config`
- ✅ 加强 JNI 本地方法保护：`SynthesizerNative` 及所有 native 方法
- ✅ 完善内部实现类保护：音频处理、缓存、网络监控、在线 TTS 等模块
- ✅ 优化 Kotlin 特性支持：协程、StateFlow、密封类、数据类解构
- ✅ 更新第三方依赖规则：OkHttp、Kotlin Coroutines
- ✅ 添加序列化支持规则

## 文件说明

### 1. `TTSLibrary/consumer-rules.pro`
此文件包含**库使用者规则**，当其他应用或模块依赖 TTSLibrary 时自动应用。这些规则确保：
- 公共 API 不被混淆（`TtsSynthesizer`、`TtsCallback`、`PreloadManager`）
- 回调接口能正常工作
- 数据类保持完整性（解构、复制功能）
- 枚举类正确工作
- JNI 本地方法正确映射

### 2. `TTSLibrary/proguard-rules.pro`
此文件包含**库内部规则**，在构建 TTSLibrary 本身时使用。包含：
- 调试信息保留
- JNI 本地方法的详细保护
- 第三方依赖规则（OkHttp、Kotlin Coroutines）
- 内部实现类保护
- R8 优化设置

### 3. `app/proguard-rules.pro`
此文件包含**应用规则**，仅用于 TTSDemo 应用。内容精简，因为 TTSLibrary 的规则会通过 consumer-rules.pro 自动应用。

## 保护的关键组件

### 公共 API 类
| 类名 | 说明 |
|------|------|
| `TtsSynthesizer` | 主要的 TTS 合成器类 |
| `TtsCallback` | 回调接口 |
| `PreloadManager` | 预加载管理器 |
| `PreloadManager.Config` | 预加载配置 |
| `TtsStatus` | TTS 状态数据类 |
| `DecodedPcm` | 解码后的 PCM 数据类 |
| `AppLoggerConfig` | 日志配置类 |

### 枚举类
| 枚举名 | 说明 |
|--------|------|
| `TtsPlaybackState` | 播放状态（IDLE, PLAYING, PAUSED, BUFFERING） |
| `TtsStrategy` | TTS 策略（OFFLINE_ONLY, ONLINE_PREFERRED, ONLINE_ONLY） |
| `SynthesisMode` | 合成模式（ONLINE, OFFLINE） |
| `SentenceSplitterStrategy` | 句子分割策略 |
| `Level` | 日志级别 |
| `Speaker` | 发音人配置 |

### JNI 本地方法
| 类名 | 说明 |
|------|------|
| `SynthesizerNative` | 包含本地方法的类，必须保留以确保 JNI 调用正常工作 |

### 内部实现类
| 模块 | 类名 |
|------|------|
| 音频处理 | `AudioPlayer`、`AudioSpeedProcessor`、`Sonic` |
| 缓存 | `TtsCache`、`TtsCacheImpl` |
| 网络 | `NetworkMonitor` |
| 策略 | `SynthesisStrategyManager` |
| 在线TTS | `OnlineTtsApi`、`WxReaderApi`、`Mp3Decoder`、`MediaCodecMp3Decoder` |
| Token管理 | `TokenProvider`、`WxTokenManager`、`TokenRemoteDataSource` |
| 数据仓库 | `TtsRepository` |
| 工具类 | `PathUtils`、`SentenceSplitter`、`SSLHelper` |

## 规则分类说明

### 1. Keep 规则
保留不应被混淆或删除的类、方法和字段：
```proguard
-keep public class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
    public <init>(...);
    public <methods>;
}
```

### 2. KeepAttributes 规则
保留必要的属性信息：
```proguard
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
```

### 3. DontWarn 规则
忽略可选依赖的警告：
```proguard
-dontwarn okhttp3.**
-dontwarn okio.**
```

### 4. 优化设置
允许 R8 进行优化但保持 API 稳定性：
```proguard
-allowaccessmodification
-repackageclasses ''
```

## 测试建议

### 启用混淆构建

在 `build.gradle.kts` 中启用混淆：
```kotlin
// TTSLibrary/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

### 验证步骤
1. 构建 Release 版本：`./gradlew :TTSLibrary:assembleRelease`
2. 检查混淆映射：查看 `build/outputs/mapping/release/mapping.txt`
3. 验证公共 API 未被混淆
4. 测试 JNI 调用正常工作
5. 验证回调接口正常触发
6. 测试所有枚举值可正常使用
7. 验证数据类的解构和复制功能

## 常见问题

### Q: 为什么需要保留 data class 的 component 方法？
A: Kotlin 数据类使用 `component1()`, `component2()` 等方法实现解构声明。如果这些方法被混淆，解构功能将失败。

### Q: 为什么 native 方法必须保留？
A: JNI 通过方法名和签名在运行时查找本地方法实现。如果方法名被混淆，JNI 将无法找到对应的本地实现，导致 `UnsatisfiedLinkError`。

### Q: consumer-rules.pro 和 proguard-rules.pro 的区别？
A: 
- `consumer-rules.pro` 会被打包到 AAR 中，当其他模块依赖此库时自动应用
- `proguard-rules.pro` 仅在构建库本身时使用，不会传递给使用者

### Q: 如何验证规则是否正确？
A: 
1. 启用混淆构建 Release 版本
2. 使用 ProGuard 映射文件检查哪些类被混淆
3. 运行集成测试确保功能正常
4. 检查 APK 大小和方法数是否合理减少

### Q: 为什么保留了 StateFlow 和协程相关类？
A: `TtsSynthesizer` 使用 `StateFlow` 暴露 `isPlaying` 状态，`NetworkMonitor` 使用 `StateFlow` 暴露网络状态。这些需要在运行时保持正确的类型信息。

## 维护建议

1. **添加新的公共 API 时**：更新 `consumer-rules.pro` 保护新的公共类和方法
2. **添加新的依赖库时**：检查是否需要在 `proguard-rules.pro` 添加对应的规则
3. **修改数据类时**：确保序列化相关的字段被保留
4. **定期测试**：在 CI/CD 中定期运行混淆构建并测试

## 参考资源

- [Android ProGuard 官方文档](https://developer.android.com/studio/build/shrink-code)
- [R8 优化指南](https://developer.android.com/studio/build/shrink-code#optimization)
- [ProGuard 规则语法](https://www.guardsquare.com/manual/configuration/usage)
- [Kotlin 混淆最佳实践](https://kotlinlang.org/docs/native-binary.html#configure-obfuscation)
