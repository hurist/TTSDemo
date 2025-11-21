# TTSLibrary ProGuard 规则更新总结

## 更新日期
2025-11-21

## 背景
根据最近的代码重构和功能增强（参见 CHANGES_SUMMARY.md 和 FINAL_SUMMARY.md），对 TTSLibrary 的 ProGuard 混淆规则进行了全面检查和更新。

## 主要发现

### 已有规则评估 ✅
现有的 ProGuard 规则基础良好，包含：
- 公共API类的完整保护（TtsSynthesizer、TtsCallback等）
- JNI本地方法的正确处理
- 数据类和Kotlin特性的支持
- 依赖库（OkHttp、Kotlin Coroutines）的标准规则

### 识别的缺失规则 ⚠️

#### 1. 新增枚举类
- **SynthesisMode**：在 `SynthesisStrategyManager.kt` 中定义，用于区分在线/离线合成模式
- **Level**：在 `AppLogger.kt` 中定义，在 `TtsCallback` 接口中使用

**风险等级**：🔴 高风险 - 这些枚举在回调接口中使用，混淆会导致回调失败

#### 2. 缺失的数据类
- **DecodedPcm**：PCM解码数据类，在 `proguard-rules.pro` 中已保护，但 `consumer-rules.pro` 中缺失

**风险等级**：🟡 中风险 - 可能影响使用者对解码数据的处理

#### 3. 新增的缓存模块
- **TtsCache**：缓存接口
- **TtsCacheImpl**：缓存实现

**风险等级**：🟡 中风险 - 可能影响在线TTS的缓存功能

#### 4. 新增的在线TTS模块
- **Mp3Decoder**：MP3解码器接口
- **MediaCodecMp3Decoder**：MP3解码器实现
- **OnlineTtsApiImp**：在线API实现
- **TokenProvider**、**TokenRemoteDataSource**、**WxTokenManager**：Token管理类

**风险等级**：🟡 中风险 - 影响在线TTS功能

#### 5. 新增的内部管理类
- **NetworkMonitor**：网络状态监控
- **SynthesisStrategyManager**：策略管理器
- **AudioSpeedProcessor**：音频速度处理器

**风险等级**：🟢 低风险 - 通过接口间接使用，但显式保护更安全

#### 6. Kotlin Flow支持
- **StateFlow** 和 **MutableStateFlow**：用于网络状态监控等

**风险等级**：🟡 中风险 - 影响状态管理和响应式编程

## 已实施的更新

### 1. proguard-rules.pro（库内部规则）

#### 添加的枚举类规则
```proguard
# Specifically keep new enums (explicit for clarity)
-keep enum com.qq.wx.offlinevoice.synthesizer.SynthesisMode {
    **[] $VALUES;
    public *;
}

-keep enum com.qq.wx.offlinevoice.synthesizer.Level {
    **[] $VALUES;
    public *;
}
```

#### 添加的Kotlin Flow支持
```proguard
# Keep Kotlin Flow classes (for StateFlow usage in new code)
-keepclassmembers class kotlinx.coroutines.flow.StateFlow {
    <methods>;
}
-keepclassmembers class kotlinx.coroutines.flow.MutableStateFlow {
    <methods>;
}
```

#### 添加的缓存模块规则
```proguard
# ============ Cache Module (New) ============
-keep interface com.qq.wx.offlinevoice.synthesizer.cache.TtsCache {
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.cache.TtsCacheImpl {
    <init>(...);
    <methods>;
}
```

#### 添加的在线TTS模块规则
```proguard
# ============ Online TTS Module (New) ============
-keep interface com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder {
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.MediaCodecMp3Decoder {
    <init>(...);
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.OnlineTtsApiImp {
    <init>(...);
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.token.TokenProvider {
    <init>(...);
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.token.TokenRemoteDataSource {
    <init>(...);
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.token.WxTokenManager {
    <init>(...);
    <methods>;
}
```

#### 添加的内部管理类规则
```proguard
# ============ Internal Management Classes (New) ============
-keep class com.qq.wx.offlinevoice.synthesizer.NetworkMonitor {
    <init>(...);
    <fields>;
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.SynthesisStrategyManager {
    <init>(...);
    <fields>;
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.AudioSpeedProcessor {
    <init>(...);
    <methods>;
}
```

### 2. consumer-rules.pro（库使用者规则）

#### 添加的数据类
```proguard
-keep class com.qq.wx.offlinevoice.synthesizer.DecodedPcm {
    <fields>;
    <methods>;
    <init>(...);
}
```

#### 添加的枚举类
```proguard
-keep enum com.qq.wx.offlinevoice.synthesizer.Level {
    **[] $VALUES;
    public *;
}
```

#### 添加的Kotlin Flow支持
```proguard
# Keep Kotlin Flow classes (used in NetworkMonitor and other components)
-keepclassmembers class kotlinx.coroutines.flow.StateFlow {
    <methods>;
}
-keepclassmembers class kotlinx.coroutines.flow.MutableStateFlow {
    <methods>;
}
```

#### 添加的公开接口和管理类
```proguard
# ============ Cache Module ============
-keep interface com.qq.wx.offlinevoice.synthesizer.cache.TtsCache {
    <methods>;
}

# ============ Online TTS Module ============
-keep interface com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder {
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.token.TokenProvider {
    <methods>;
}

# ============ Network and Strategy Management ============
-keep class com.qq.wx.offlinevoice.synthesizer.NetworkMonitor {
    <fields>;
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.SynthesisStrategyManager {
    <fields>;
    <methods>;
}
```

### 3. PROGUARD_RULES.md（文档更新）

- 添加了"最近更新"章节，说明所有新增的规则
- 更新了枚举类列表，包含 `SynthesisMode` 和 `Level`
- 添加了"新增模块"部分，说明缓存、在线TTS、策略管理等模块
- 添加了 Kotlin Flow 支持说明

## 测试建议

### 1. 构建测试
```bash
./gradlew :TTSLibrary:assembleRelease
```
检查构建是否成功，没有ProGuard警告。

### 2. 映射文件检查
查看 `TTSLibrary/build/outputs/mapping/release/mapping.txt`，验证：
- 公共API类未被混淆（TtsSynthesizer、TtsCallback等）
- 枚举类保持原样
- 数据类的字段和方法名保留
- JNI方法未被混淆

### 3. 功能测试
使用混淆后的Release版本测试：
- ✅ 离线TTS合成
- ✅ 在线TTS合成
- ✅ 在线/离线策略切换
- ✅ 缓存功能
- ✅ 回调接口（特别是 `onLog` 方法使用 `Level` 枚举）
- ✅ 网络状态监控
- ✅ 音频速度调节
- ✅ 暂停/恢复/停止功能

### 4. 集成测试
在实际应用中集成混淆后的库，确保：
- 所有公共API正常工作
- 回调正确触发
- 异常处理正常
- 数据类序列化/反序列化正常

## 变更文件清单

### 修改的文件
1. ✅ `TTSLibrary/proguard-rules.pro` - 添加新增类和模块的保护规则
2. ✅ `TTSLibrary/consumer-rules.pro` - 添加公共API相关的保护规则
3. ✅ `PROGUARD_RULES.md` - 更新文档说明
4. ✅ `PROGUARD_UPDATE_SUMMARY.md` - 本总结文档（新建）

### 未修改的文件
- `TTSLibrary/build.gradle.kts` - ProGuard配置无需更改
- 源代码文件 - 无需修改

## 总结

### 更新前的问题
- ❌ 新增枚举类（SynthesisMode、Level）未保护
- ❌ DecodedPcm 在 consumer-rules.pro 中缺失
- ❌ 缓存模块完全未保护
- ❌ 在线TTS模块不完整
- ❌ Kotlin Flow 支持缺失
- ❌ 内部管理类未显式保护

### 更新后的状态
- ✅ 所有新增枚举类已保护
- ✅ 所有数据类在两个规则文件中都有保护
- ✅ 缓存模块完整保护
- ✅ 在线TTS模块完整保护
- ✅ Kotlin Flow 完整支持
- ✅ 内部管理类显式保护
- ✅ 文档已更新

### 风险评估
- **更新前风险**：🔴 高 - 关键枚举未保护，可能导致回调失败
- **更新后风险**：🟢 低 - 所有已知组件都有适当的保护

## 建议

1. **立即采用**：这些更新修复了潜在的严重问题（回调失败），建议立即应用
2. **测试验证**：在Release构建中进行完整的功能测试
3. **持续维护**：
   - 每次添加新的公共API时，更新ProGuard规则
   - 每次重构或添加新模块时，检查ProGuard规则
   - 定期审查混淆映射文件
4. **CI/CD集成**：建议在CI流程中添加ProGuard规则验证步骤

## 参考
- [Android ProGuard 官方文档](https://developer.android.com/studio/build/shrink-code)
- [R8 优化指南](https://developer.android.com/studio/build/shrink-code#optimization)
- TTSLibrary 代码变更记录：`CHANGES_SUMMARY.md`、`FINAL_SUMMARY.md`
