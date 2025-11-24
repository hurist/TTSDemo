# =========================================================================
# TTSLibrary Consumer ProGuard Rules
#
# These are the essential rules for any app that uses this library.
# They protect the public API contract and nothing more.
# =========================================================================

# --- 1. Core Public API ---
# Keep the main entry point class and its public methods.
-keep public class com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer {
    public <init>(...);
    public <methods>;
}

# Keep the public callback interface.
-keep public interface com.qq.wx.offlinevoice.synthesizer.TtsCallback {
    <methods>;
}

# --- 2. Preload Manager Public API ---
# Keep the PreloadManager and its public instance methods.
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager {
    public void preload(...);
    public void cancel(...);
    public void cancelAll();
}

# Keep the Companion object for static-like accessors.
-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Companion {
    public com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager getInstance(android.content.Context);
    public void initialize(android.content.Context, com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config);
}

# --- 3. Public Data Models & Enums ---
# Keep all public data classes and enums used in the API.
# The '*' rule is safe here because we are targeting specific public models.

-keep public class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config {
    public <init>(...);
    public *;
}

-keep public class com.qq.wx.offlinevoice.synthesizer.Speaker {
    public <init>(...);
    public *;
}

-keep public class com.qq.wx.offlinevoice.synthesizer.TtsStatus {
    public <init>(...);
    public *;
}

# Keep this data class if it's part of the public API (e.g., returned in a callback).
# If not, this rule can be removed from here.
-keep public class com.qq.wx.offlinevoice.synthesizer.DecodedPcm {
    public <init>(...);
    public *;
}

# Keep public enums that are used as parameters or return types in the public API.
-keep public enum com.qq.wx.offlinevoice.synthesizer.SentenceSplitterStrategy
-keep public enum com.qq.wx.offlinevoice.synthesizer.SynthesisMode
-keep public enum com.qq.wx.offlinevoice.synthesizer.TtsPlaybackState
# Add any other PUBLIC enums here...

# --- 4. Kotlin Data Class Support ---
# Keep auto-generated methods for public data classes. This is more targeted.
-keepclassmembers,allowobfuscation class com.qq.wx.offlinevoice.synthesizer.preload.PreloadManager$Config,
                                       com.qq.wx.offlinevoice.synthesizer.Speaker,
                                       com.qq.wx.offlinevoice.synthesizer.TtsStatus,
                                       com.qq.wx.offlinevoice.synthesizer.DecodedPcm {
    public ** component*();
    public ** copy(...);
}

# --- 5. Parcelable Support ---
# If any of your PUBLIC data models implement Parcelable.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}