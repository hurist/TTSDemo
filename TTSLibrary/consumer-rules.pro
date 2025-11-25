# =============================================================================
# TTSLibrary Consumer ProGuard Rules
#
# 这些规则会自动应用于任何使用此库的应用程序。
# 它们保护公共 API 契约，确保库的功能在混淆后正常工作。
#
# 更新日期: 2025-11-25
# =============================================================================

# -----------------------------------------------------------------------------
# 第一部分：核心公共 API
# -----------------------------------------------------------------------------

# 1.1 主入口类 TtsSynthesizer
# 保留构造函数和所有公共方法，这是库的主要入口点
-keep public class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
    public <init>(...);
    public <methods>;
}

# 1.2 回调接口 TtsCallback
# 保留所有方法，确保用户实现的回调能正常工作
-keep public interface com.qq.wx.offlinevoice.synthesizer.TtsCallback {
    <methods>;
}

# 1.3 保留所有实现 TtsCallback 的类
# 确保用户自定义的回调实现不被混淆
-keep class * implements com.qq.wx.offlinevoice.synthesizer.TtsCallback {
    <methods>;
}

# -----------------------------------------------------------------------------
# 第二部分：预加载管理器 API
# -----------------------------------------------------------------------------

# 2.1 PreloadManager 类
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager {
    public void preload(...);
    public void cancel(...);
    public void cancelAll();
}

# 2.2 PreloadManager 的 Companion 对象（用于静态方法访问）
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Companion {
    public com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager getInstance(android.content.Context);
    public void initialize(android.content.Context, com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config);
}

# 2.3 PreloadManager.Config 配置类
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# -----------------------------------------------------------------------------
# 第三部分：公共枚举类
# -----------------------------------------------------------------------------

# 3.1 播放状态枚举
-keep public enum com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState {
     *;
}

# 3.2 TTS 策略枚举
-keep public enum com.qq.wx.offlinevoice.synthesizer.TtsStrategy {
     *;
}

# 3.3 合成模式枚举
-keep public enum com.qq.wx.offlinevoice.synthesizer.SynthesisMode {
     *;
}

# 3.4 句子分割策略枚举
-keep public enum com.qq.wx.offlinevoice.synthesizer.SentenceSplitterStrategy {
     *;
}

# 3.5 日志级别枚举（用于回调）
-keep public enum com.qq.wx.offlinevoice.synthesizer.Level {
     *;
}

# 3.6 Speaker 枚举（发音人配置）
-keep public enum com.qq.wx.offlinevoice.synthesizer.Speaker {
     *;
}

# -----------------------------------------------------------------------------
# 第四部分：公共数据类
# -----------------------------------------------------------------------------

# 4.1 TtsStatus 状态数据类
-keep public class com.qq.wx.offlinevoice.synthesizer.TtsStatus {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# 4.2 DecodedPcm 解码数据类
-keep public class com.qq.wx.offlinevoice.synthesizer.DecodedPcm {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# 4.3 AppLoggerConfig 日志配置类
-keep public class com.qq.wx.offlinevoice.synthesizer.AppLoggerConfig {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# -----------------------------------------------------------------------------
# 第五部分：Kotlin 数据类支持
# -----------------------------------------------------------------------------

# 保留数据类的 component 方法（用于解构）和 copy 方法
-keepclassmembers class com.qq.wx.offlinevoice.synthesizer.TtsStatus,
                        com.qq.wx.offlinevoice.synthesizer.DecodedPcm,
                        com.qq.wx.offlinevoice.synthesizer.AppLoggerConfig,
                        com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config {
    public ** component*();
    public ** copy(...);
}

# -----------------------------------------------------------------------------
# 第六部分：序列化支持
# -----------------------------------------------------------------------------

# 6.1 Parcelable 支持
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 6.2 Serializable 支持（DecodedPcm 实现了 Serializable）
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
# 第七部分：JNI 本地方法支持
# -----------------------------------------------------------------------------

# 保留所有包含 native 方法的类，确保 JNI 调用正常
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# -----------------------------------------------------------------------------
# 第八部分：Kotlin 相关支持
# -----------------------------------------------------------------------------

# 8.1 保留 Kotlin 元数据
-keep class kotlin.Metadata { *; }

# 8.2 保留 Companion 对象
-keep class com.qq.wx.offlinevoice.synthesizer.**$Companion { *; }

# 8.3 保留 Kotlin 枚举的 WhenMappings
-keep class com.qq.wx.offlinevoice.synthesizer.**$WhenMappings { *; }

# -----------------------------------------------------------------------------
# 第九部分：属性保留
# -----------------------------------------------------------------------------

# 保留注解、签名、内部类等信息
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod