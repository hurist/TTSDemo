package com.qq.wx.offlinevoice.synthesizer;

import android.content.Context;
import android.text.TextUtils;
import com.google.common.base.Ascii;
import com.google.common.primitives.SignedBytes;
import com.google.common.primitives.UnsignedBytes;
import com.hurist.ttsdemo.R;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/* loaded from: classes3.dex */
public class Speaker {
    public String avatar;
    public String code;
    public String desc;
    public String extendUI;
    public short gender;
    public String group;
    public String id;
    public String locale;
    public String name;
    public String note;
    public String param;
    public float pitch;
    public int sampleRate;
    public float speed;
    public short type;
    public float volume;

    public Speaker() {
        this.pitch = 1.0f;
    }

    /*public Set<String> getFeatures(Context context) {
        HashSet hashSet = new HashSet();
        hashSet.add(this.name);
        if (this.type != 0) {
            hashSet.add(context.getString(R.string.online));
        }
        hashSet.add(context.getString(this.gender == 0 ? R.string.female : R.string.male));
        if (!TextUtils.isEmpty(this.desc)) {
            hashSet.add(this.desc);
        }
        return hashSet;
    }

    public String getShortName() {
        return d.f(this.group) + AbstractC0943c.l(new byte[]{-57, 65, -98}, new byte[]{37, -63, 60, 66, -91, 108, 97, 125}) + this.name;
    }*/

    public String toString() {
        return AbstractC0943c.l(new byte[]{-4, 86, -35, -125, -39, -26, -20, 79, -52, 73, -36, -121, -113, -92}, new byte[]{-81, 38, -72, -30, -78, -125, -98, 52}) + this.code + '\'' + AbstractC0943c.l(new byte[]{40, -92, -89, 121, 114, -75, 83, 95, 57, -93}, new byte[]{4, -124, -58, Ascii.SI, 19, -63, 50, 45}) + this.avatar + '\'' + AbstractC0943c.l(new byte[]{-17, -108, 75, Ascii.US, 62, -44, 34, 40}, new byte[]{-61, -76, 47, 122, 77, -73, Ascii.US, Ascii.SI}) + this.desc + '\'' + AbstractC0943c.l(new byte[]{-73, 44, 109, SignedBytes.MAX_POWER_OF_TWO, -29, -121, 93, -34, -50, 69, 53, Ascii.US}, new byte[]{-101, Ascii.FF, 8, 56, -105, -30, 51, -70}) + this.extendUI + '\'' + AbstractC0943c.l(new byte[]{-50, 121, -116, -91, Ascii.SI, -74, -1, Ascii.SI, -33}, new byte[]{-30, 89, -21, -64, 97, -46, -102, 125}) + ((int) this.gender) + AbstractC0943c.l(new byte[]{-45, -20, -34, 104, 57, -5, -75, UnsignedBytes.MAX_POWER_OF_TWO, -40}, new byte[]{-1, -52, -71, Ascii.SUB, 86, -114, -59, -67}) + this.group + '\'' + AbstractC0943c.l(new byte[]{33, 6, 102, 89, 82, 109}, new byte[]{Ascii.CR, 38, Ascii.SI, 61, 111, 74, -44, -46}) + this.id + '\'' + AbstractC0943c.l(new byte[]{-86, -55, -99, -17, 2, 104, 43, 6}, new byte[]{-122, -23, -13, -114, 111, Ascii.CR, Ascii.SYN, 33}) + this.name + '\'' + AbstractC0943c.l(new byte[]{37, 59, -52, 5, 106, 112, 34, 59}, new byte[]{9, Ascii.ESC, -94, 106, Ascii.RS, Ascii.NAK, Ascii.US, Ascii.FS}) + this.note + '\'' + AbstractC0943c.l(new byte[]{5, -87, 75, -90, 62, 105, 108, Ascii.RS, Ascii.SO}, new byte[]{41, -119, 59, -57, 76, 8, 1, 35}) + this.param + '\'' + AbstractC0943c.l(new byte[]{118, -127, 112, -28, 58, 19, 51, -52, 103, -122}, new byte[]{90, -95, Ascii.FS, -117, 89, 114, 95, -87}) + this.locale + '\'' + AbstractC0943c.l(new byte[]{-99, 1, 7, 47, -41, 117, -36, 95, -29, SignedBytes.MAX_POWER_OF_TWO, 0, 43, -121}, new byte[]{-79, 33, 116, 78, -70, 5, -80, 58}) + this.sampleRate + AbstractC0943c.l(new byte[]{48, -13, Ascii.DC4, 41, 39, 19, -48, -44}, new byte[]{Ascii.FS, -45, 103, 89, 66, 118, -76, -23}) + this.speed + AbstractC0943c.l(new byte[]{-127, -1, 101, 54, -18, -9, -5}, new byte[]{-83, -33, 17, 79, -98, -110, -58, -42}) + ((int) this.type) + AbstractC0943c.l(new byte[]{Ascii.SO, 5, 34, -42, -12, -117, 4, Ascii.SO, Ascii.US}, new byte[]{34, 37, 84, -71, -104, -2, 105, 107}) + this.volume + AbstractC0943c.l(new byte[]{120, UnsignedBytes.MAX_POWER_OF_TWO, -64, -25, -10, 38, -113, 39}, new byte[]{84, -96, -80, -114, -126, 69, -25, Ascii.SUB}) + this.pitch + '}';
    }

    public Speaker(String str) {
        this.type = (short) 1;
        this.gender = (short) 0;
        this.speed = 1.0f;
        this.volume = 1.0f;
        this.pitch = 1.0f;
        this.sampleRate = 16000;
        this.group = str;
        Locale locale = Locale.getDefault();
        this.locale = locale.getLanguage() + AbstractC0943c.l(new byte[]{-38}, new byte[]{-9, -45, 114, -81, 60, 122, -1, -124}) + locale.getCountry();
    }

    public Speaker(String str, String str2) {
        this(str);
        this.id = str2;
    }

    /*public Speaker(Map<Object, Object> map) {
        this.pitch = 1.0f;
        Object obj = map.get(AbstractC0943c.l(new byte[]{-92, 122}, new byte[]{-51, Ascii.RS, -34, 39, -107, -124, 118, 0}));
        if (obj != null) {
            this.id = obj.toString();
        }
        Object obj2 = map.get(AbstractC0943c.l(new byte[]{67, -110, -94, 116}, new byte[]{32, -3, -58, 17, -23, -124, -59, -13}));
        if (obj2 != null) {
            this.code = obj2.toString();
        }
        Object obj3 = map.get(AbstractC0943c.l(new byte[]{-73, -72, -41, -29}, new byte[]{-39, -39, -70, -122, -96, -120, -81, Ascii.GS}));
        if (obj3 != null) {
            this.name = obj3.toString();
        }
        Object obj4 = map.get(AbstractC0943c.l(new byte[]{99, 115, 70, 59, -52, Ascii.NAK}, new byte[]{2, 5, 39, 79, -83, 103, -11, 95}));
        if (obj4 != null) {
            this.avatar = obj4.toString();
        }
        Object obj5 = map.get(AbstractC0943c.l(new byte[]{-67, -106, -6, 118}, new byte[]{-39, -13, -119, Ascii.NAK, 112, 81, 40, -124}));
        if (obj5 != null) {
            this.desc = obj5.toString();
        }
        Object obj6 = map.get(AbstractC0943c.l(new byte[]{123, -63, -21, Ascii.EM, -65}, new byte[]{Ascii.FS, -77, -124, 108, -49, 42, 93, -120}));
        if (obj6 != null) {
            this.group = obj6.toString();
        }
        Object obj7 = map.get(AbstractC0943c.l(new byte[]{8, Ascii.FF, -67, 114, 90}, new byte[]{120, 109, -49, 19, 55, -94, 79, -42}));
        if (obj7 != null) {
            this.param = obj7.toString();
        }
        Object obj8 = map.get(AbstractC0943c.l(new byte[]{93, 89, 35, 75, -23, 99, UnsignedBytes.MAX_POWER_OF_TWO, -80}, new byte[]{56, 33, 87, 46, -121, 7, -43, -7}));
        if (obj8 != null) {
            this.extendUI = obj8.toString();
        }
        Object obj9 = map.get(AbstractC0943c.l(new byte[]{45, -80, -3, -1}, new byte[]{89, -55, -115, -102, -122, -66, -6, Ascii.DC2}));
        if (obj9 != null) {
            this.type = ((Integer) obj9).shortValue();
        }
        Object obj10 = map.get(AbstractC0943c.l(new byte[]{-58, -60, -95, 48, 73, -20}, new byte[]{-95, -95, -49, 84, 44, -98, 9, -48}));
        if (obj10 != null) {
            this.gender = ((Integer) obj10).shortValue();
        }
        Object obj11 = map.get(AbstractC0943c.l(new byte[]{121, -33, -119, -67, -84, 97}, new byte[]{Ascii.SI, -80, -27, -56, -63, 4, 79, -98}));
        if (obj11 != null) {
            this.volume = ((Double) obj11).floatValue();
        }
        Object obj12 = map.get(AbstractC0943c.l(new byte[]{-62, -92, 89, Utf8.REPLACEMENT_BYTE, 48}, new byte[]{-79, -44, 60, 90, 84, Ascii.US, -63, 57}));
        if (obj12 != null) {
            this.speed = ((Double) obj12).floatValue();
        }
        Object obj13 = map.get(AbstractC0943c.l(new byte[]{-23, -109, Ascii.FS, -122, 54}, new byte[]{-103, -6, 104, -27, 94, -112, -72, -92}));
        if (obj13 != null) {
            this.pitch = ((Double) obj13).floatValue();
        }
        Object obj14 = map.get(AbstractC0943c.l(new byte[]{97, 108, -99, 39, 112, -100, Ascii.EM, 62, 102, 104}, new byte[]{Ascii.DC2, Ascii.CR, -16, 87, Ascii.FS, -7, 75, 95}));
        if (obj14 != null) {
            this.sampleRate = ((Integer) obj14).intValue();
        }
        Object obj15 = map.get(AbstractC0943c.l(new byte[]{-107, -63, 89, 55, 112, -56}, new byte[]{-7, -82, 58, 86, Ascii.FS, -83, -82, -24}));
        if (obj15 != null) {
            this.locale = (String) obj15;
        }
    }*/
}