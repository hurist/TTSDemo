package com.qq.wx.offlinevoice.synthesizer;

import android.content.Context;
import android.widget.Toast;
import com.google.common.collect.ImmutableList;
import java.util.Iterator;
import java.util.List;

/* loaded from: classes.dex */
public abstract /* synthetic */ class i {
    /*public static P a(S s8, kotlin.jvm.internal.d dVar, C0767d c0767d) {
        return s8.l(B2.c.q(dVar), c0767d);
    }

    public static C0454b b(f1.i iVar, byte[] bArr, int i) {
        ImmutableList.Builder builder = ImmutableList.builder();
        f1.h hVar = f1.h.f10829c;
        Objects.requireNonNull(builder);
        iVar.p(bArr, 0, i, hVar, new Z(builder, 16));
        return new C0454b(builder.build());
    }*/

    public static /* synthetic */ int c(int i) {
        if (i == 1) {
            return 8000;
        }
        if (i == 2) {
            return 16000;
        }
        if (i == 3) {
            return 24000;
        }
        throw null;
    }

    /*public static *//* synthetic *//* String d(int i) {
        if (i == 1) {
            return "16";
        }
        if (i == 2) {
            return "32";
        }
        if (i == 3) {
            return "64";
        }
        if (i == 4) {
            return "128";
        }
        if (i == 5) {
            return SpeechEngineDefines.WAKEUP_MODE_NORMAL;
        }
        throw null;
    }*/

    public static /* synthetic */ int e(int i) {
        switch (i) {
            case 1:
                return 0;
            case 2:
                return 1;
            case 3:
                return 2;
            case 4:
                return 3;
            case 5:
                return 4;
            case 6:
                return 5;
            case 7:
                return 10;
            default:
                throw null;
        }
    }

  /*  public static PropertyCollection g(long j2, IntRef intRef) {
        Contracts.throwIfFail(j2);
        return new PropertyCollection(intRef);
    }*/

    public static ClassCastException h(Iterator it) {
        it.next().getClass();
        return new ClassCastException();
    }

    public static Object i(int i, List list) {
        return list.get(list.size() - i);
    }

    public static String j(int i, int i8, String str, String str2) {
        return str + i + str2 + i8;
    }

    public static String k(int i, String str, String str2) {
        return str + i + str2;
    }

   /* public static String l(String str, AbstractComponentCallbacksC0508s abstractComponentCallbacksC0508s, String str2) {
        return str + abstractComponentCallbacksC0508s + str2;
    }*/

    public static String m(String str, String str2) {
        return str + str2;
    }

    public static String n(String str, String str2, String str3) {
        return str + str2 + str3;
    }

    public static String o(StringBuilder sb, int i, char c8) {
        sb.append(i);
        sb.append(c8);
        return sb.toString();
    }

    public static String p(StringBuilder sb, long j2, String str) {
        sb.append(j2);
        sb.append(str);
        return sb.toString();
    }

    public static String q(byte[] bArr, byte[] bArr2, StringBuilder sb) {
        sb.append(AbstractC0943c.l(bArr, bArr2));
        return sb.toString();
    }

    public static String r(byte[] bArr, byte[] bArr2, StringBuilder sb, String str) {
        sb.append(AbstractC0943c.l(bArr, bArr2));
        sb.append(str);
        return sb.toString();
    }

    public static void s(Context context, int i, Context context2, int i8) {
        Toast.makeText(context2, context.getString(i), i8).show();
    }

    /*public static void t(C0860m c0860m, I i) {
        i.d(new C0861n(c0860m));
    }*/

    public static void u(byte[] bArr, byte[] bArr2, Context context, StringBuilder sb) {
        sb.append(context.getExternalFilesDir(AbstractC0943c.l(bArr, bArr2)).getAbsolutePath());
    }

    public static void v(byte[] bArr, byte[] bArr2, StringBuilder sb, String str) {
        sb.append(AbstractC0943c.l(bArr, bArr2));
        sb.append(str);
    }

    public static /* synthetic */ String w(int i) {
        switch (i) {
            case 1:
                return "BEGIN_ARRAY";
            case 2:
                return "END_ARRAY";
            case 3:
                return "BEGIN_OBJECT";
            case 4:
                return "END_OBJECT";
            case 5:
                return "NAME";
            case 6:
                return "STRING";
            case 7:
                return "NUMBER";
            case 8:
                return "BOOLEAN";
            case 9:
                return "NULL";
            case 10:
                return "END_DOCUMENT";
            default:
                return "null";
        }
    }

    public static /* synthetic */ int x(String str) {
        if (str == null) {
            throw new NullPointerException("Name is null");
        }
        if (str.equals("DEFAULT")) {
            return 1;
        }
        if (str.equals("HIGH_SPEED_NETWORK")) {
            return 2;
        }
        if (str.equals("HIGH_SPEED_NETWORK_WITHOUT_ETHER")) {
            return 3;
        }
        throw new IllegalArgumentException("No enum constant com.baidu.tts.MixMode.".concat(str));
    }
}