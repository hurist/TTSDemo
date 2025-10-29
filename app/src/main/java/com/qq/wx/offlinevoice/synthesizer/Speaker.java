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

    public Speaker(String str) {
        this.type = (short) 1;
        this.gender = (short) 0;
        this.speed = 1.0f;
        this.volume = 1.0f;
        this.pitch = 1.0f;
        this.sampleRate = 16000;
        this.group = str;
        Locale locale = Locale.getDefault();
    }

    public Speaker(String str, String str2) {
        this(str);
        this.id = str2;
    }

}