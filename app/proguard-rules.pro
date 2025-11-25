# =============================================================================
# TTSDemo App ProGuard Rules
#
# 这些规则用于应用程序本身的混淆配置。
# TTSLibrary 的规则会通过 consumer-rules.pro 自动应用。
#
# 更新日期: 2025-11-25
# =============================================================================

# -----------------------------------------------------------------------------
# 第一部分：调试信息保留
# -----------------------------------------------------------------------------

# 保留源文件名和行号信息，便于崩溃日志分析
-keepattributes SourceFile,LineNumberTable

# 如果保留了行号信息，可以用此选项隐藏原始源文件名
#-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# 第二部分：Kotlin 相关
# -----------------------------------------------------------------------------

# 保留 Kotlin 元数据
-keep class kotlin.Metadata { *; }

# 保留 Kotlin 协程相关类
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# -----------------------------------------------------------------------------
# 第三部分：OkHttp 规则
# -----------------------------------------------------------------------------

# OkHttp 警告抑制
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 保留 OkHttp 平台相关类
-keepnames class okhttp3.internal.platform.** { *; }

# -----------------------------------------------------------------------------
# 第四部分：Guava 规则（如果使用）
# -----------------------------------------------------------------------------

# Guava 相关警告抑制
-dontwarn com.google.errorprone.annotations.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.lang.model.element.Modifier
-dontwarn afu.org.checkerframework.**
-dontwarn org.checkerframework.**

# -----------------------------------------------------------------------------
# 第五部分：WebView JavaScript 接口（如需要）
# -----------------------------------------------------------------------------

# 如果项目使用 WebView 并且有 JavaScript 接口，取消下面的注释
# 并指定 JavaScript 接口类的完整类名
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# -----------------------------------------------------------------------------
# 第六部分：序列化支持
# -----------------------------------------------------------------------------

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

# 保留 Parcelable CREATOR
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# -----------------------------------------------------------------------------
# 第七部分：通用属性保留
# -----------------------------------------------------------------------------

# 保留注解信息
-keepattributes *Annotation*

# 保留签名信息（用于泛型）
-keepattributes Signature

# 保留内部类信息
-keepattributes InnerClasses,EnclosingMethod

# 保留异常信息
-keepattributes Exceptions