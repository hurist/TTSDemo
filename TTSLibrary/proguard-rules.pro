# TTSLibrary Internal ProGuard Rules
# These rules are applied when building the library itself

# ============ Debugging Information ============
# Preserve line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses,EnclosingMethod

# Hide the original source file name for security
-renamesourcefileattribute SourceFile

# ============ Native Methods (JNI) ============
# Keep all native methods - critical for JNI
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Specifically keep SynthesizerNative
-keep class com.qq.wx.offlinevoice.synthesizer.SynthesizerNative {
    <init>();
    <methods>;
}

# ============ Public API ============
# Keep main synthesizer class
-keep public class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
    public <init>(...);
    public <methods>;
}

# Keep callback interface
-keep public interface com.qq.wx.offlinevoice.synthesizer.TtsCallback {
    <methods>;
}

# ============ Data Classes ============
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

# ============ Enums ============
-keep enum com.qq.wx.offlinevoice.synthesizer.** {
    **[] $VALUES;
    public *;
}

# Specifically keep new enums (explicit for clarity)
-keep enum com.qq.wx.offlinevoice.synthesizer.SynthesisMode {
    **[] $VALUES;
    public *;
}

-keep enum com.qq.wx.offlinevoice.synthesizer.Level {
    **[] $VALUES;
    public *;
}

# ============ Kotlin Coroutines ============
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# Keep volatile fields in coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep ServiceLoader
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory

# Keep Kotlin Flow classes (for StateFlow usage in new code)
-keepclassmembers class kotlinx.coroutines.flow.StateFlow {
    <methods>;
}
-keepclassmembers class kotlinx.coroutines.flow.MutableStateFlow {
    <methods>;
}

# ============ OkHttp & Okio ============
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

-keeppackagenames okhttp3.internal.publicsuffix.**
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============ Reflection ============
# Keep classes accessed by reflection (if any)
-keep class com.qq.wx.offlinevoice.synthesizer.PathUtils {
    <methods>;
}

# ============ Serialization ============
# Keep Parcelable CREATOR
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ============ Kotlin Metadata ============
# Keep metadata for Kotlin reflection
-keep class kotlin.Metadata { *; }

# ============ Companion Objects ============
-keepclassmembers class * {
    public ** Companion;
}

-keepclassmembers class **$Companion {
    <fields>;
    <methods>;
}

# Keep static fields in companion objects
-keepclassmembers class **$Companion {
    public static **;
}

# ============ Data Class Methods ============
# Keep data class generated methods
-keepclassmembers class * {
    public ** component*();
    public ** copy(...);
}

# ============ Internal Implementation ============
# Keep AudioPlayer - used in callbacks
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer {
    public <methods>;
}

# Keep repository and API classes
-keep class com.qq.wx.offlinevoice.synthesizer.TtsRepository {
    <init>(...);
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.OnlineTtsApi {
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.WxReaderApi {
    <init>(...);
    <methods>;
}

# Keep exception classes
-keep class com.qq.wx.offlinevoice.synthesizer.online.WxApiException {
    <init>(...);
    <fields>;
    <methods>;
}

# ============ Cache Module (New) ============
# Keep cache interface and implementation
-keep interface com.qq.wx.offlinevoice.synthesizer.cache.TtsCache {
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.cache.TtsCacheImpl {
    <init>(...);
    <methods>;
}

# ============ Online TTS Module (New) ============
# Keep MP3 decoder interface and implementation
-keep interface com.qq.wx.offlinevoice.synthesizer.online.Mp3Decoder {
    <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.online.MediaCodecMp3Decoder {
    <init>(...);
    <methods>;
}

# Keep online TTS API implementation
-keep class com.qq.wx.offlinevoice.synthesizer.online.OnlineTtsApiImp {
    <init>(...);
    <methods>;
}

# Keep token management classes
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

# ============ Internal Management Classes (New) ============
# Keep network monitor for online/offline strategy
-keep class com.qq.wx.offlinevoice.synthesizer.NetworkMonitor {
    <init>(...);
    <fields>;
    <methods>;
}

# Keep synthesis strategy manager
-keep class com.qq.wx.offlinevoice.synthesizer.SynthesisStrategyManager {
    <init>(...);
    <fields>;
    <methods>;
}

# Keep audio speed processor
-keep class com.qq.wx.offlinevoice.synthesizer.AudioSpeedProcessor {
    <init>(...);
    <methods>;
}

# ============ Android Components ============
# Keep custom view constructors
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ============ Optimization Settings ============
# Don't warn about missing dependencies
-dontwarn java.lang.invoke.**
-dontwarn javax.lang.model.**

# Optimize method inlining
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5

# Allow access modification for better optimization
-allowaccessmodification

# Repackage classes to reduce APK size
-repackageclasses ''