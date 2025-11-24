# TTSLibrary ProGuard Rules
# These rules are applied when the library is consumed by an app

# ============ Public API Classes ============
# Keep all public API classes and their public methods
-keep public class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
    public <init>(...);
    public <methods>;
}

# Keep callback interface and all its methods
-keep public interface com.qq.wx.offlinevoice.synthesizer.TtsCallback {
    <methods>;
}

# ============ Data Classes & Enums ============
# Keep data classes used in public API (preserve all fields and methods)
-keep class com.qq.wx.offlinevoice.synthesizer.Speaker {
    <fields>;
    <methods>;
    <init>(...);
}

-keep class com.qq.wx.offlinevoice.synthesizer.TtsStatus {
    <fields>;
    <methods>;
    <init>(...);
}

-keep class com.qq.wx.offlinevoice.synthesizer.DecodedPcm {
    <fields>;
    <methods>;
    <init>(...);
}

# Keep all enums and their values
-keep enum com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState {
    **[] $VALUES;
    public *;
}

-keep enum com.qq.wx.offlinevoice.synthesizer.TtsStrategy {
    **[] $VALUES;
    public *;
}

-keep enum com.qq.wx.offlinevoice.synthesizer.SynthesisMode {
    **[] $VALUES;
    public *;
}

-keep enum com.qq.wx.offlinevoice.synthesizer.Level {
    **[] $VALUES;
    public *;
}

# ============ Native Methods (JNI) ============
# Keep all native methods and their declaring class
-keepclasseswithmembernames class com.qq.wx.offlinevoice.synthesizer.SynthesizerNative {
    native <methods>;
}

# Keep native method parameters
-keepclassmembers class com.qq.wx.offlinevoice.synthesizer.SynthesizerNative {
    <init>(...);
    public <methods>;
}

# ============ Kotlin Specific ============
# Keep data class generated methods (copy, componentN, toString, etc.)
-keepclassmembers class com.qq.wx.offlinevoice.synthesizer.Speaker {
    public ** component*();
    public ** copy(...);
}

-keepclassmembers class com.qq.wx.offlinevoice.synthesizer.TtsStatus {
    public ** component*();
    public ** copy(...);
}

# Keep companion objects
-keepclassmembers class * {
    public ** Companion;
}

-keepclassmembers class **$Companion {
    <fields>;
    <methods>;
}

# ============ Kotlin Coroutines ============
# Keep coroutines-related classes
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep ServiceLoader for coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Kotlin Flow classes (used in NetworkMonitor and other components)
-keepclassmembers class kotlinx.coroutines.flow.StateFlow {
    <methods>;
}
-keepclassmembers class kotlinx.coroutines.flow.MutableStateFlow {
    <methods>;
}

# ============ OkHttp ============
# OkHttp platform used only on JVM and when Conscrypt and other security providers are available
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# A resource is loaded with a relative path so the package of this class must be preserved.
-keeppackagenames okhttp3.internal.publicsuffix.**
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# OkHttp platform used only on JVM and when Conscrypt dependency is available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============ Serialization ============
# Keep attributes for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions

# Keep generic signatures for reflection
-keepattributes InnerClasses,EnclosingMethod

# ============ Internal Classes (Optional) ============
# Keep internal classes that may be accessed via reflection or used in callbacks
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer {
    public <methods>;
}

# Keep internal enums that might be exposed through callbacks
-keep enum com.qq.wx.offlinevoice.synthesizer.SentenceSplitterStrategy {
    **[] $VALUES;
    public *;
}

# ============ Cache Module ============
# Keep cache interface (may be accessed via reflection or dependency injection)
-keep interface com.qq.wx.offlinevoice.synthesizer.cache.TtsCache {
    <methods>;
}

# ============ Online TTS Module ============
# Keep MP3 decoder interface (may be used by consumers for custom decoders)
-keep interface com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder {
    <methods>;
}

# Keep token management interface (may be exposed through API)
-keep class com.qq.wx.offlinevoice.synthesizer.online.token.TokenProvider {
    <methods>;
}

# ============ Network and Strategy Management ============
# Keep network monitor (used with strategy manager)
-keep class com.qq.wx.offlinevoice.synthesizer.NetworkMonitor {
    <fields>;
    <methods>;
}

# Keep synthesis strategy manager (accessed by public API)
-keep class com.qq.wx.offlinevoice.synthesizer.SynthesisStrategyManager {
    <fields>;
    <methods>;
}

# ============ Parcelable ============
# Keep Parcelable implementations if any
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 1. 保留 PreloadManager 类及其公共实例方法
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager {
    # 注意：这里不再包含 static 方法的规则
    public void preload(...);
    public void cancel(...);
    public void cancelAll();
}

# 2. 明确保留 Companion 对象及其公共方法
# 这是对您问题的直接修正
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Companion {
    public com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager getInstance(android.content.Context);
    public void initialize(android.content.Context, com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config);
}

# 3. 保留 Config 数据类
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config {
    public <init>(...);
    public *;
}

# ============ General Android ============
# Keep custom view constructors
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ============ R8 Optimizations ============
# Allow R8 to optimize away unused code
-allowaccessmodification
-repackageclasses ''
