# =========================================================================
# TTSLibrary Internal ProGuard Rules
#
# These rules are for building the library itself. They handle internal
# implementation details, JNI, and third-party dependencies.
# =========================================================================

# --- 1. Debugging & Kotlin Metadata ---
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,Exceptions,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

# --- 2. JNI (Native Code) ---
# This is the standard and safest way to keep all JNI bindings.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- 3. Third-Party Dependencies (OkHttp, Coroutines) ---
# Rules for libraries used INTERNALLY. The consumer does not see these.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keeppackagenames okhttp3.internal.publicsuffix.**
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- 4. Internal Implementation Details ---
# Keep all internal classes that are NOT part of the public API but are
# dynamically created or need their names preserved for other reasons.
# It is often better to keep specific classes rather than using wildcards.

# Keep internal classes accessed via reflection, if any.
-keep class com.qq.wx.offlinevoice.synthesizer.PathUtils { *; }

# Keep all other internal implementation classes.
# Note: If these classes are only used directly and not via reflection,
# R8 is smart enough to keep them, and you might not even need these rules.
# Add them if you encounter runtime crashes like ClassNotFoundException.
-keep class com.qq.wx.offlinevoice.synthesizer.AudioPlayer { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.TtsRepository { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.online.** { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.cache.** { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.NetworkMonitor { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.SynthesisStrategyManager { *; }
-keep class com.qq.wx.offlinevoice.synthesizer.AudioSpeedProcessor { *; }

# Keep internal enums
-keep enum com.qq.wx.offlinevoice.synthesizer.** { *; }

# --- 5. General Android & Kotlin ---
# Keep custom view constructors (if you have any in the library).
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context, ...);
}

# Keep Parcelable CREATOR for internal parcelable classes.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# --- 6. Optimization Settings ---
# These are fine for the library's internal build process.
-allowaccessmodification
-repackageclasses ''