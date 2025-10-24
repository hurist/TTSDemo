package com.qq.wx.offlinevoice.synthesizer;

import android.content.Context;
import android.media.AudioManager;
import java.nio.charset.StandardCharsets;

/* renamed from: o0.c, reason: case insensitive filesystem */
/* loaded from: classes.dex */
public abstract class AbstractC0943c {

    /* renamed from: a, reason: collision with root package name */
    public static AudioManager f14519a;

    /* renamed from: b, reason: collision with root package name */
    public static Context f14520b;

    /*public static void A(ViewGroup viewGroup) {
        Drawable background = viewGroup.getBackground();
        if (background instanceof K3.g) {
            z(viewGroup, (K3.g) background);
        }
    }

    public static void B(View view, boolean z5, Context context, RoleRule roleRule, AutoCompleteTextView autoCompleteTextView, AtomicReference atomicReference, TextInputEditText textInputEditText) {
        if (!z5) {
            view.findViewById(R.id.role_edit_regex_box).setVisibility(0);
            view.findViewById(R.id.role_secondary_lang_box).setVisibility(8);
            return;
        }
        view.findViewById(R.id.role_edit_regex_box).setVisibility(8);
        view.findViewById(R.id.role_secondary_lang_box).setVisibility(0);
        String strSubstring = !TextUtils.isEmpty(roleRule.regex) ? roleRule.regex.startsWith(l(new byte[]{-20, -49, -28, 108, -65}, new byte[]{UnsignedBytes.MAX_POWER_OF_TWO, -82, -118, 11, -123, 19, -24, 61})) ? roleRule.regex.substring(5) : roleRule.regex : null;
        ArrayList arrayListF = O6.c.f2969f.f2972c ? O6.c.f() : O6.c.e();
        AbstractC1083q.i(context, arrayListF, strSubstring, autoCompleteTextView, new D0.g(atomicReference, 4, arrayListF, textInputEditText));
    }

    public static void a(StringBuilder sb, Object obj, l lVar) {
        if (lVar != null) {
            sb.append((CharSequence) lVar.invoke(obj));
            return;
        }
        if (obj == null ? true : obj instanceof CharSequence) {
            sb.append((CharSequence) obj);
        } else if (obj instanceof Character) {
            sb.append(((Character) obj).charValue());
        } else {
            sb.append((CharSequence) obj.toString());
        }
    }

    public static String b(String sourceStr) {
        kotlin.jvm.internal.i.g(sourceStr, "sourceStr");
        int iY = n.Y(sourceStr, 6, "/");
        int iA = i2.b.a(sourceStr, "_v[0-9]");
        int i = iY + 1;
        if (sourceStr.length() != 0 && i >= 0 && iA >= 0 && i < iA && i < sourceStr.length() && iA <= sourceStr.length()) {
            String strSubstring = sourceStr.substring(i, iA);
            kotlin.jvm.internal.i.b(strSubstring, "(this as java.lang.Strin…ing(startIndex, endIndex)");
            return strSubstring;
        }
        int iA2 = i2.b.a(sourceStr, "\\.model|_model|\\.dat");
        if (iA2 <= 0 || iA2 <= iY) {
            String strSubstring2 = sourceStr.substring(i, sourceStr.length());
            kotlin.jvm.internal.i.b(strSubstring2, "(this as java.lang.Strin…ing(startIndex, endIndex)");
            return strSubstring2;
        }
        String strSubstring3 = sourceStr.substring(i, iA2);
        kotlin.jvm.internal.i.b(strSubstring3, "(this as java.lang.Strin…ing(startIndex, endIndex)");
        return strSubstring3;
    }

    public static short[] d(byte[] bArr) {
        if (bArr == null) {
            return null;
        }
        short[] sArr = new short[bArr.length / 2];
        ByteBuffer.wrap(bArr).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sArr);
        return sArr;
    }

    public static int e(String str) {
        if (!n.Q(str, "size")) {
            return 0;
        }
        int iY = n.Y(str, 6, "size");
        int iA = n.Q(str, "md5") ? i2.b.a(str, "_md5") : i2.b.a(str, "\\.model|_model|\\.dat");
        if (iY >= iA) {
            return -1;
        }
        String strSubstring = str.substring(iY + 4, iA);
        kotlin.jvm.internal.i.b(strSubstring, "(this as java.lang.Strin…ing(startIndex, endIndex)");
        try {
            return Integer.parseInt(strSubstring);
        } catch (Exception unused) {
            return -1;
        }
    }

    public static final void f(C0170v c0170v, String name, String value) {
        kotlin.jvm.internal.i.f(c0170v, "<this>");
        kotlin.jvm.internal.i.f(name, "name");
        kotlin.jvm.internal.i.f(value, "value");
        ArrayList arrayList = c0170v.f4099a;
        arrayList.add(name);
        arrayList.add(n.j0(value).toString());
    }

    public static void g(String str, String str2) throws IOException {
        File file = new File(str);
        if (!file.exists() || !file.isDirectory()) {
            throw new IOException(B.i.n("Folder ", str, " does't exist or isn't a directory"));
        }
        File file2 = new File(str2);
        if (!file2.exists()) {
            File parentFile = file2.getParentFile();
            if (!parentFile.exists() && !parentFile.mkdirs()) {
                throw new IOException("Zip folder " + parentFile.getAbsolutePath() + " not created");
            }
            if (!file2.createNewFile()) {
                throw new IOException(B.i.n("Zip file ", str2, " not created"));
            }
        }
        ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file2)));
        try {
            byte[] bArr = new byte[8192];
            for (String str3 : file.list()) {
                if (!str3.equals(".") && !str3.equals("..")) {
                    File file3 = new File(file, str3);
                    if (file3.isFile()) {
                        BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(file3), 8192);
                        try {
                            zipOutputStream.putNextEntry(new ZipEntry(str3));
                            while (true) {
                                int i = bufferedInputStream.read(bArr, 0, 8192);
                                if (i != -1) {
                                    zipOutputStream.write(bArr, 0, i);
                                } else {
                                    try {
                                        break;
                                    } catch (IOException unused) {
                                    }
                                }
                            }
                            bufferedInputStream.close();
                        } catch (Throwable th) {
                            try {
                                bufferedInputStream.close();
                            } catch (IOException unused2) {
                            }
                            throw th;
                        }
                    }
                }
            }
        } finally {
            try {
                zipOutputStream.close();
            } catch (IOException unused3) {
            }
        }
    }

    public static C1180a h(byte[] bArr) {
        return new C1180a(ByteBuffer.wrap(bArr, 0, bArr.length).slice(), 0);
    }

    public static i2.b i(int i) {
        return i != 0 ? i != 1 ? new K3.i() : new K3.d() : new K3.i();
    }

    public static Bitmap k(int i, byte[] bArr, int i8) throws IOException {
        BitmapFactory.Options options;
        int i9 = 0;
        int iE = 1;
        if (i8 != -1) {
            options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bArr, 0, i, options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            for (int iMax = Math.max(options.outWidth, options.outHeight); iMax > i8; iMax /= 2) {
                options.inSampleSize *= 2;
            }
        } else {
            options = null;
        }
        Bitmap bitmapDecodeByteArray = BitmapFactory.decodeByteArray(bArr, 0, i, options);
        if (options != null) {
            options.inSampleSize = 1;
        }
        if (bitmapDecodeByteArray == null) {
            throw ParserException.a(new IllegalStateException(), "Could not decode image data");
        }
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bArr);
        try {
            C0434g c0434g = new C0434g(byteArrayInputStream);
            byteArrayInputStream.close();
            C0430c c0430cC = c0434g.c("Orientation");
            if (c0430cC != null) {
                try {
                    iE = c0430cC.e(c0434g.f10663f);
                } catch (NumberFormatException unused) {
                }
            }
            switch (iE) {
                case 3:
                case 4:
                    i9 = 180;
                    break;
                case 5:
                case 8:
                    i9 = 270;
                    break;
                case 6:
                case 7:
                    i9 = 90;
                    break;
            }
            if (i9 == 0) {
                return bitmapDecodeByteArray;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(i9);
            return Bitmap.createBitmap(bitmapDecodeByteArray, 0, 0, bitmapDecodeByteArray.getWidth(), bitmapDecodeByteArray.getHeight(), matrix, false);
        } catch (Throwable th) {
            try {
                byteArrayInputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }*/

    public static String l(byte[] bArr, byte[] bArr2) {
        int length = bArr.length;
        int length2 = bArr2.length;
        int i = 0;
        int i8 = 0;
        while (i < length) {
            if (i8 >= length2) {
                i8 = 0;
            }
            bArr[i] = (byte) (bArr[i] ^ bArr2[i8]);
            i++;
            i8++;
        }
        return new String(bArr, StandardCharsets.UTF_8);
    }

    /*public static void n(Context context, RoleRule roleRule, R6.e eVar) {
        B4.a aVar = new B4.a(context, R.style.Theme_MultiTTS_DialogTheme);
        View viewInflate = LayoutInflater.from(context).inflate(R.layout.dialog_edit_role, (ViewGroup) null);
        C0544g c0544g = (C0544g) aVar.f738c;
        c0544g.f11581r = viewInflate;
        String string = context.getString(R.string.msg_editor_title, roleRule.name);
        if (!TextUtils.isEmpty(roleRule.message)) {
            StringBuilder sbA = w.e.a(string);
            sbA.append(roleRule.message);
            string = sbA.toString();
        }
        c0544g.f11568d = string;
        aVar.p(context.getString(R.string.msg_confirm), null);
        aVar.o(context.getString(R.string.msg_cancel), null);
        c0544g.f11574k = context.getString(R.string.msg_default_value);
        DialogInterfaceC0548k dialogInterfaceC0548kI = aVar.i();
        dialogInterfaceC0548kI.setCancelable(false);
        dialogInterfaceC0548kI.setOnShowListener(new c7.a(viewInflate, roleRule, context, dialogInterfaceC0548kI, eVar, 1));
        dialogInterfaceC0548kI.show();
    }

    public static InterfaceC1049g o(InterfaceC1049g interfaceC1049g, InterfaceC1050h key) {
        kotlin.jvm.internal.i.f(key, "key");
        if (kotlin.jvm.internal.i.a(interfaceC1049g.getKey(), key)) {
            return interfaceC1049g;
        }
        return null;
    }

    public static synchronized AudioManager p(Context context) {
        try {
            Context applicationContext = context.getApplicationContext();
            if (applicationContext != null) {
                f14519a = null;
            }
            AudioManager audioManager = f14519a;
            if (audioManager != null) {
                return audioManager;
            }
            Looper looperMyLooper = Looper.myLooper();
            if (looperMyLooper != null && looperMyLooper != Looper.getMainLooper()) {
                q0.e eVar = new q0.e();
                AbstractC1030a.o().execute(new J(22, applicationContext, eVar));
                eVar.b();
                AudioManager audioManager2 = f14519a;
                audioManager2.getClass();
                return audioManager2;
            }
            AudioManager audioManager3 = (AudioManager) applicationContext.getSystemService("audio");
            f14519a = audioManager3;
            audioManager3.getClass();
            return audioManager3;
        } catch (Throwable th) {
            throw th;
        }
    }

    public static Drawable q(Context context, int i) {
        return J0.d().f(context, i);
    }

    public static final void s(String name) {
        kotlin.jvm.internal.i.f(name, "name");
        if (name.length() <= 0) {
            throw new IllegalArgumentException("name is empty");
        }
        int length = name.length();
        for (int i = 0; i < length; i++) {
            char cCharAt = name.charAt(i);
            if ('!' > cCharAt || cCharAt >= 127) {
                StringBuilder sb = new StringBuilder("Unexpected char 0x");
                i2.b.f(16);
                String string = Integer.toString(cCharAt, 16);
                kotlin.jvm.internal.i.e(string, "toString(...)");
                if (string.length() < 2) {
                    string = SpeechEngineDefines.WAKEUP_MODE_NORMAL.concat(string);
                }
                sb.append(string);
                sb.append(" at ");
                sb.append(i);
                sb.append(" in header name: ");
                sb.append(name);
                throw new IllegalArgumentException(sb.toString().toString());
            }
        }
    }

    public static final void t(String value, String name) {
        kotlin.jvm.internal.i.f(value, "value");
        kotlin.jvm.internal.i.f(name, "name");
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char cCharAt = value.charAt(i);
            if (cCharAt != '\t' && (' ' > cCharAt || cCharAt >= 127)) {
                StringBuilder sb = new StringBuilder("Unexpected char 0x");
                i2.b.f(16);
                String string = Integer.toString(cCharAt, 16);
                kotlin.jvm.internal.i.e(string, "toString(...)");
                if (string.length() < 2) {
                    string = SpeechEngineDefines.WAKEUP_MODE_NORMAL.concat(string);
                }
                sb.append(string);
                sb.append(" at ");
                sb.append(i);
                sb.append(" in ");
                sb.append(name);
                sb.append(" value");
                sb.append(W5.c.k(name) ? "" : ": ".concat(value));
                throw new IllegalArgumentException(sb.toString().toString());
            }
        }
    }

    public static int u(int i) {
        if (i == 1) {
            return 0;
        }
        if (i == 2) {
            return 1;
        }
        if (i == 4) {
            return 2;
        }
        if (i == 8) {
            return 3;
        }
        if (i == 16) {
            return 4;
        }
        if (i == 32) {
            return 5;
        }
        if (i == 64) {
            return 6;
        }
        if (i == 128) {
            return 7;
        }
        if (i == 256) {
            return 8;
        }
        if (i == 512) {
            return 9;
        }
        throw new IllegalArgumentException(AbstractC0488U.k(i, "type needs to be >= FIRST and <= LAST, type="));
    }

    public static InterfaceC1051i v(InterfaceC1049g interfaceC1049g, InterfaceC1050h key) {
        kotlin.jvm.internal.i.f(key, "key");
        return kotlin.jvm.internal.i.a(interfaceC1049g.getKey(), key) ? C1052j.f15732a : interfaceC1049g;
    }

    public static InterfaceC1051i w(InterfaceC1049g interfaceC1049g, InterfaceC1051i context) {
        kotlin.jvm.internal.i.f(context, "context");
        return context == C1052j.f15732a ? interfaceC1049g : (InterfaceC1051i) context.w(interfaceC1049g, new C1044b(1));
    }

    public static Intent x(Context context, BroadcastReceiver broadcastReceiver, IntentFilter intentFilter) {
        return Build.VERSION.SDK_INT >= 26 ? context.registerReceiver(broadcastReceiver, intentFilter, null, null, 0) : context.registerReceiver(broadcastReceiver, intentFilter, null, null);
    }

    public static void y(ViewGroup viewGroup, float f3) {
        Drawable background = viewGroup.getBackground();
        if (background instanceof K3.g) {
            ((K3.g) background).l(f3);
        }
    }

    public static void z(View view, K3.g gVar) {
        B3.a aVar = gVar.f2343a.f2328b;
        if (aVar == null || !aVar.f731a) {
            return;
        }
        float fE = 0.0f;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            WeakHashMap weakHashMap = Q.f3353a;
            fE += H.e((View) parent);
        }
        K3.f fVar = gVar.f2343a;
        if (fVar.f2337l != fE) {
            fVar.f2337l = fE;
            gVar.r();
        }
    }

    public abstract void C(ArrayList arrayList);

    public abstract void c(H1.c cVar, Object obj);

    public abstract String j();

    public abstract void m();

    public void r(H1.a connection, Object obj) throws Exception {
        kotlin.jvm.internal.i.f(connection, "connection");
        if (obj == null) {
            return;
        }
        H1.c cVarC = connection.C(j());
        try {
            c(cVarC, obj);
            cVarC.B();
            i2.b.h(cVarC, null);
            Q6.c.H(connection);
        } finally {
        }
    }*/
}