# TTSLibrary ProGuard/R8 混淆规则说明

本文档说明了为 TTSLibrary 添加的 ProGuard/R8 混淆规则。

## 文件说明

### 1. `consumer-rules.pro`
此文件包含**库使用者规则**，当其他应用或模块依赖 TTSLibrary 时自动应用。这些规则确保：
- 公共 API 不被混淆
- 回调接口能正常工作
- 数据类保持序列化兼容性
- JNI 本地方法正确映射

### 2. `proguard-rules.pro`
此文件包含**库内部规则**，在构建 TTSLibrary 本身时使用。包含更详细的内部实现保护规则。

## 保护的关键组件

### 公共 API 类
- `TtsSynthesizer` - 主要的 TTS 合成器类，保留所有公共方法
- `TtsCallback` - 回调接口，保留所有方法以确保回调正常触发
- `Speaker` - 语音配置数据类
- `TtsStatus` - TTS 状态数据类

### 枚举类
- `TtsPlaybackState` - 播放状态枚举
- `TtsStrategy` - TTS 策略枚举
- `SynthesisMode` - 合成模式枚举
- `SentenceSplitterStrategy` - 句子分割策略枚举

### JNI 本地方法
- `SynthesizerNative` - 包含本地方法的类，必须保留以确保 JNI 调用正常工作
- 所有 `native` 方法签名都被保留

### 依赖库规则
- **Kotlin Coroutines** - 保留协程相关类和 volatile 字段
- **OkHttp** - 添加标准 OkHttp ProGuard 规则
- **Kotlin 元数据** - 保留 Kotlin 反射所需的元数据

### 数据类特性
- `component*()` 方法 - Kotlin 数据类解构
- `copy()` 方法 - 数据类复制功能
- 伴生对象（Companion objects）

## 规则分类

### 1. Keep 规则
保留不应被混淆或删除的类、方法和字段：
```proguard
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
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

构建 Release 版本时启用 ProGuard/R8：
```kotlin
// build.gradle.kts
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

## 维护建议

1. **添加新的公共 API 时**：更新 keep 规则保护新的公共类和方法
2. **添加新的依赖库时**：检查是否需要添加对应的 ProGuard 规则
3. **修改数据类时**：确保序列化相关的字段被保留
4. **定期测试**：在 CI/CD 中定期运行混淆构建并测试

## 参考资源

- [Android ProGuard 官方文档](https://developer.android.com/studio/build/shrink-code)
- [R8 优化指南](https://developer.android.com/studio/build/shrink-code#optimization)
- [ProGuard 规则语法](https://www.guardsquare.com/manual/configuration/usage)
