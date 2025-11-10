# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ==============================================================================
# ProGuard Rules for TTSLibrary (com.qq.wx.offlinevoice.synthesizer)
# ==============================================================================

# Keep native methods - Critical for JNI functionality
-keepclasseswithmembernames class com.qq.wx.offlinevoice.synthesizer.SynthesizerNative {
    native <methods>;
}

# Keep all members of SynthesizerNative as they are called from native code
-keep class com.qq.wx.offlinevoice.synthesizer.SynthesizerNative {
    public <methods>;
    public <fields>;
}

# Keep the main TTS synthesizer public API
-keep public class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# Keep callback interface - All methods must be preserved for user implementations
-keep interface com.qq.wx.offlinevoice.synthesizer.TtsCallback {
    <methods>;
}

# Keep all implementations of TtsCallback
-keep class * implements com.qq.wx.offlinevoice.synthesizer.TtsCallback {
    <methods>;
}

# Keep data classes and their members - Used in public API
-keep class com.qq.wx.offlinevoice.synthesizer.TtsStatus {
    public <init>(...);
    public <methods>;
    public <fields>;
}

# Keep enum class - Used in public API
-keep enum com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState {
    **[] $VALUES;
    public *;
}

# Keep constants object
-keep class com.qq.wx.offlinevoice.synthesizer.TtsConstants {
    public static final <fields>;
}

# Keep utility classes that might be accessed reflectively or from native code
-keep class com.qq.wx.offlinevoice.synthesizer.PathUtils {
    public <methods>;
}

-keep class com.qq.wx.offlinevoice.synthesizer.XorDecoder {
    public <methods>;
}

# Keep AudioPlayer - Important for audio playback functionality
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer {
    public <init>(...);
    public <methods>;
}

# Keep SentenceSplitter - Used by TtsSynthesizer
-keep class com.qq.wx.offlinevoice.synthesizer.SentenceSplitter {
    public static <methods>;
}

# Preserve annotations for the synthesizer package
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Kotlin metadata for the synthesizer package to ensure proper Kotlin functionality
-keep class com.qq.wx.offlinevoice.synthesizer.**$Companion { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.**$WhenMappings { *; }

# Keep sealed classes and their subclasses (used in TtsSynthesizer)
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer$Command { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer$Command$* { *; }

# Keep AudioPlayer internal queue items
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer$QueueItem { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer$QueueItem$* { *; }

# Preserve StateFlow and coroutine-related members
-keepclassmembers class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
    kotlinx.coroutines.flow.StateFlow isPlaying;
}

# Don't warn about missing classes from optional dependencies
-dontwarn com.qq.wx.offlinevoice.synthesizer.**