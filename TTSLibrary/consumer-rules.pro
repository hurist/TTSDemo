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

# ============ Parcelable ============
# Keep Parcelable implementations if any
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
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
