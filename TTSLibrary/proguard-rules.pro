# =============================================================================
# TTSLibrary Internal ProGuard Rules
#
# 这些规则在构建库本身时使用，用于处理内部实现细节、
# JNI 本地方法和第三方依赖。
#
# 更新日期: 2025-11-25
# =============================================================================

# -----------------------------------------------------------------------------
# 第一部分：调试与 Kotlin 元数据
# -----------------------------------------------------------------------------

# 保留源文件和行号信息，便于调试
-keepattributes SourceFile,LineNumberTable

# 保留签名信息（泛型支持）
-keepattributes Signature

# 保留注解信息
-keepattributes *Annotation*

# 保留异常信息
-keepattributes Exceptions

# 保留内部类和外部类关系
-keepattributes InnerClasses,EnclosingMethod

# 保留 Kotlin 元数据，确保 Kotlin 反射和特性正常工作
-keep class kotlin.Metadata { *; }

# -----------------------------------------------------------------------------
# 第二部分：JNI 本地方法（关键）
# -----------------------------------------------------------------------------

# 保留所有包含 native 方法的类及其方法名
# 这是确保 JNI 调用正常工作的标准且最安全的方式
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 特别保护 SynthesizerNative 类（核心 JNI 类）
-keep class com.qq.wx.offlinevoice.synthesizer.SynthesizerNative {
    <init>(...);
    <methods>;
    <fields>;
}

# -----------------------------------------------------------------------------
# 第三部分：OkHttp 依赖规则
# -----------------------------------------------------------------------------

# OkHttp 相关警告抑制
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 保留 OkHttp 内部类名
-keeppackagenames okhttp3.internal.publicsuffix.**
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# OkHttp 平台相关类
-keepnames class okhttp3.internal.platform.** { *; }
-dontnote okhttp3.internal.platform.**

# -----------------------------------------------------------------------------
# 第四部分：Kotlin 协程依赖规则
# -----------------------------------------------------------------------------

# 保留协程主调度器工厂类名
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# 保留协程中的 volatile 字段
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# 协程调试支持
-keepnames class kotlinx.coroutines.debug.internal.DebugProbesImpl {}

# StateFlow 和 MutableStateFlow 支持
-keep class kotlinx.coroutines.flow.StateFlow { *; }
-keep class kotlinx.coroutines.flow.MutableStateFlow { *; }

# -----------------------------------------------------------------------------
# 第五部分：内部实现类保护
# -----------------------------------------------------------------------------

# 5.1 音频处理相关
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer$* { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.AudioSpeedProcessor { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.Sonic { *; }

# 5.2 TTS 仓库
-keep class com.qq.wx.offlinevoice.synthesizer.TtsRepository { *; }

# 5.3 网络监控
-keep class com.qq.wx.offlinevoice.synthesizer.NetworkMonitor { *; }

# 5.4 策略管理
-keep class com.qq.wx.offlinevoice.synthesizer.SynthesisStrategyManager { *; }

# 5.5 缓存模块
-keep interface com.qq.wx.offlinevoice.synthesizer.cache.TtsCache { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.cache.TtsCacheImpl { *; }

# 5.6 在线 TTS 模块
-keep interface com.qq.wx.offlinevoice.synthesizer.online.OnlineTtsApi { *; }
-keep interface com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.online.WxReaderApi { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.online.MediaCodecMp3Decoder { *; }

# 5.7 Token 管理
-keep class com.qq.wx.offlinevoice.synthesizer.online.token.** { *; }

# 5.8 预加载模块
-keep class com.qq.wx.offlinevoice.synthesizer.preload.PreloadJob { *; }

# 5.9 工具类
-keep class com.qq.wx.offlinevoice.synthesizer.PathUtils { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.XorDecoder { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.SentenceSplitter { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.SSLHelper { *; }

# 5.10 日志系统
-keep class com.qq.wx.offlinevoice.synthesizer.AppLogger { *; }
-keep interface com.qq.wx.offlinevoice.synthesizer.AppLoggerCallback { *; }

# 5.11 常量类
-keep class com.qq.wx.offlinevoice.synthesizer.TtsConstants { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.ErrCode { *; }

# -----------------------------------------------------------------------------
# 第六部分：所有内部枚举类
# -----------------------------------------------------------------------------

# 保留所有枚举类（包括内部定义的枚举）
-keep enum com.qq.wx.offlinevoice.synthesizer.** {
    **[] $VALUES;
    public *;
}

# -----------------------------------------------------------------------------
# 第七部分：Kotlin 特性支持
# -----------------------------------------------------------------------------

# 7.1 保留 Companion 对象
-keep class com.qq.wx.offlinevoice.synthesizer.**$Companion { *; }

# 7.2 保留 WhenMappings（Kotlin when 表达式）
-keep class com.qq.wx.offlinevoice.synthesizer.**$WhenMappings { *; }

# 7.3 保留密封类和其子类
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer$Command { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer$Command$* { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer$SynthesisResult { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer$SynthesisResult$* { *; }

# 7.4 保留内部数据类
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer$TtsBag { *; }

# 7.5 保留数据类的 component 和 copy 方法
-keepclassmembers class com.qq.wx.offlinevoice.synthesizer.** {
    public ** component*();
    public ** copy(...);
}

# -----------------------------------------------------------------------------
# 第八部分：Android 相关
# -----------------------------------------------------------------------------

# 保留自定义 View 构造函数
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context, ...);
}

# 保留 Parcelable CREATOR
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 保留 Serializable 类的序列化方法
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# -----------------------------------------------------------------------------
# 第九部分：R8 优化设置
# -----------------------------------------------------------------------------

# 允许修改访问权限以优化代码
-allowaccessmodification

# 将内部类重新打包到默认包，减少类名长度
-repackageclasses ''

# 不输出未使用的优化信息
-dontnote com.qq.wx.offlinevoice.synthesizer.**

# -----------------------------------------------------------------------------
# 第十部分：异常类保护
# -----------------------------------------------------------------------------

# 保留自定义异常类
-keep class com.qq.wx.offlinevoice.synthesizer.online.WxApiException { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.ForbiddenNetworkException { *; }