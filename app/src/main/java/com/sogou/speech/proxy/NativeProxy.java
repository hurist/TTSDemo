package com.sogou.speech.proxy;

/* loaded from: classes3.dex */
public class NativeProxy {

    public NativeProxy() {
        System.loadLibrary("sgproxy_lib");
    }

    /* renamed from: a, reason: collision with root package name */

    public long f8668a = 1;

    public final synchronized int a(String str) {
        return native_connect(this.f8668a, 1, str);
    }

    public final synchronized void b(String str) {
        native_destroyService(this.f8668a, str);
    }

    public final synchronized byte[] c(int[] iArr) {
        return native_read(this.f8668a, iArr);
    }

    public final synchronized long d(String str, String str2) {
        long jNative_searchService;
        jNative_searchService = native_searchService(str, str2);
        this.f8668a = jNative_searchService;
        return jNative_searchService;
    }

    public final native int native_connect(long j2, int i, String str);

    public final native int native_destroyService(long j2, String str);

    public final native byte[] native_read(long j2, int[] iArr);

    public final native long native_searchService(String str, String str2);

    public final native int native_write(long j2, String str);
}