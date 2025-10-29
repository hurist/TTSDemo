package com.qq.wx.offlinevoice.synthesizer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.google.common.base.Ascii;
import com.qq.wx.offlinevoice.synthesizer.SynthesizerNative;

import java.io.ByteArrayOutputStream;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.TarsosDSPAudioFloatConverter;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;

/* loaded from: classes3.dex */
public final class a implements h {

    /* renamed from: e, reason: collision with root package name */
    public static final AtomicInteger f28e;

    /* renamed from: f, reason: collision with root package name */
    public static volatile SynthesizerNative f29f;

    /* renamed from: g, reason: collision with root package name */
    public static volatile String f30g;

    /* renamed from: a, reason: collision with root package name */
    public final ShortBuffer f31a = ShortBuffer.allocate(64000);

    /* renamed from: b, reason: collision with root package name */
    public volatile boolean f32b;

    /* renamed from: c, reason: collision with root package name */
    public final String f33c;

    /* renamed from: d, reason: collision with root package name */
    public final String f34d;

    static {
        System.loadLibrary(AbstractC0943c.l(new byte[]{-39, -45, 2, Ascii.DC2, 115, -114, 112, -28, -38, -59}, new byte[]{-82, -74, 112, 119, Ascii.DC2, -22, 93, -112}));
        System.loadLibrary(AbstractC0943c.l(new byte[]{10, -107, Ascii.NAK, 103, -77}, new byte[]{98, -30, 65, 51, -32, 119, 114, -62}));
        f28e = new AtomicInteger(0);
    }

    public a(Context context, Speaker speaker) {
        this.f33c = speaker.code;
        StringBuilder sb = new StringBuilder();
        i.u(new byte[]{68, 111, 42, 100, -19}, new byte[]{50, 0, 67, 7, -120, 65, 34, Ascii.SUB}, context, sb);
        this.f34d = i.q(new byte[]{-105, Ascii.DLE, Ascii.SYN, -80, -70, 86, 114}, new byte[]{-72, 103, 115, -62, -33, 55, Ascii.SYN, -27}, sb);
    }

    public final int a(String str, float f3, float f8) {
        if (!this.f33c.equals(f30g)) {
            f30g = this.f33c;
            f29f.setVoiceName(this.f33c);
        }
        f29f.setSpeed(f3 / 50.0f);
        f29f.setVolume(f8 / 50.0f);
        int iPrepareUTF8 = -1;
        for (int i = 0; i < 3 && (iPrepareUTF8 = f29f.prepareUTF8(str.getBytes())) != 0; i++) {
            f29f.setVoiceName(this.f33c);
        }
        return iPrepareUTF8;
    }

    @Override // R6.h
    public final synchronized void c() {
        if (f28e.incrementAndGet() == 1) {
            f29f =  SynthesizerNative.INSTANCE;
            f29f.init(this.f34d.getBytes());
        }
    }

    @Override // R6.h
    public final synchronized void cancel() {
        this.f32b = true;
        if (this.f33c.equals(f30g)) {
            f29f.reset();
        }
    }

    private AudioTrack audioTrack;
    private Thread audioPlayThread;
    private short[] pcmData;
    int sampleRate = 24000;

    private void startPlaybackFromBeginning() {
        stopAndReleaseAudioTrack();
        if (pcmData == null || pcmData.length == 0) return;

        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBufferSize <= 0) {
            minBufferSize = 2048;
        }

        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize,
                AudioTrack.MODE_STREAM
        );
        audioTrack.play();

        final int chunkSamples = Math.max(minBufferSize / 2, 1024); // shorts per write
        audioPlayThread = new Thread(() -> {
            int offset = 0;
            while (!Thread.currentThread().isInterrupted() && offset < pcmData.length) {
                int toWrite = Math.min(chunkSamples, pcmData.length - offset);
                int written = audioTrack.write(pcmData, offset, toWrite);
                if (written > 0) {
                    offset += written;
                } else if (written == AudioTrack.ERROR_INVALID_OPERATION || written == AudioTrack.ERROR_BAD_VALUE) {
                    break;
                }
            }
        }, "AudioPlayThread");
        audioPlayThread.start();
    }

    private void stopAndReleaseAudioTrack() {
        if (audioPlayThread != null) {
            audioPlayThread.interrupt();
            audioPlayThread = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (IllegalStateException ignored) {
            }
            audioTrack.release();
            audioTrack = null;
        }
    }

    /*@Override // R6.h
    public final synchronized void d(float f3, float f8, String str, g gVar) {



        int iA = a(str, f3, f8);
        if (iA != 0) {
            //((C0377a) gVar).u(AbstractC0943c.l(new byte[]{-100, -88, 51, -108, -51, 88, -80, 77, -118, -69, Utf8.REPLACEMENT_BYTE, -120, -55, 78, -7, 77, -98, -65, 34, -39}, new byte[]{-20, -38, 86, -28, -84, 42, -43, 109}) + iA);
            return;
        }
        this.f32b = false;
        int[] iArr = {0};
        short[] sArrArray = this.f31a.array();
        //C0377a c0377a = (C0377a) gVar;
        //c0377a.v();
        while (!this.f32b) {
            int iSynthesize = f29f.synthesize(sArrArray, 64000, iArr, 1);
            if (iSynthesize == -1) {
                Log.d("SynthesizerNative", "synthesize failed");
                //c0377a.u(AbstractC0943c.l(new byte[]{-59, 71, -7, -73, -79, 97, -25, -80, -52, 91, -73, -91, -72, 109, -8, -68, -46, Ascii.DC2, -73, -79, -68, 112, -87}, new byte[]{-74, 62, -105, -61, -39, 4, -108, -39}) + iSynthesize);
                f29f.reset();
                return;
            }
            pcmData = sArrArray;
            startPlaybackFromBeginning();
            int i = iArr[0];
            AbstractC0943c.l(new byte[]{3, -71, -90, -41, -96, -97, 54, -1, 10, -91, -14, -125, -92, -97, 43, -85}, new byte[]{112, -64, -56, -93, -56, -6, 69, -106});
            if (i <= 0) {
                break;
            }
            int iMin = Math.min(sArrArray.length, i);
            int i8 = iMin << 1;
            byte[] bArr = new byte[i8];
            int i9 = 0;
            for (int i10 = 0; i10 < iMin; i10++) {
                short s8 = sArrArray[i10];
                int i11 = i9 + 1;
                bArr[i9] = (byte) (s8 & 255);
                i9 += 2;
                bArr[i11] = (byte) ((s8 >>> 8) & 255);
            }
            if (this.f32b) {
                break;
            } else {
                //c0377a.w(i8, bArr);
            }
        }
        //c0377a.t();
        f29f.reset();
    }*/

/*    public final synchronized void d(float f3, float f8, String str, g gVar) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 原 d 方法内容
                int iA = a(str, f3, f8);
                if (iA != 0) return;

                a.this.f32b = false;
                int[] iArr = {0};
                short[] sArrArray = a.this.f31a.array();

                // PCM 循环播放标记
                boolean loop = true;

                while (!a.this.f32b) {
                    int iSynthesize = f29f.synthesize(sArrArray, 64000, iArr, 1);
                    if (iSynthesize == -1) {
                        Log.d("SynthesizerNative", "synthesize failed");
                        f29f.reset();
                        return;
                    }


                    int i = iArr[0];
                    if (i <= 0) break;

                    int iMin = Math.min(sArrArray.length, i);
                    int i8 = iMin << 1;
                    byte[] bArr = new byte[i8];
                    int i9 = 0;
                    for (int i10 = 0; i10 < iMin; i10++) {
                        short s8 = sArrArray[i10];
                        int i11 = i9 + 1;
                        bArr[i9] = (byte) (s8 & 255);
                        i9 += 2;
                        bArr[i11] = (byte) ((s8 >>> 8) & 255);
                    }

                    pcmData = sArrArray;

                    startPlaybackFromBeginning(); // 播放一次 PCM
                    // 可以在这里阻塞直到播放结束，也可以用回调通知播放结束
                    // 播放完成后，如果 loop=true，会继续播放
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                f29f.reset();
            }
        }).start();

    }*/


    // 你实际的采样率与声道数，按你的 PCM 配置填写，比如 16000/1
    private static final int SAMPLE_RATE = 16000;
    private static final int NUM_CHANNELS = 1;

    // 降调系数：0.8f 约等于降 4 个半音；越小音高越低
    private static final float PITCH_FACTOR = 0.68f;

    // 可复用的 Sonic 处理器（也可做成局部变量按需创建）
    private Sonic sonicProcessor;

    public final synchronized void d(final float f3, final float f8, final String str, final g gVar) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 原 d 方法内容
                int iA = a(str, f3, f8);
                if (iA != 0) return;

                // 初始化 Sonic（仅创建一次）
                if (sonicProcessor == null) {
                    sonicProcessor = new Sonic(SAMPLE_RATE, NUM_CHANNELS);
                    sonicProcessor.setSpeed(0.78f);          // 不改变时长
                    sonicProcessor.setPitch(PITCH_FACTOR);   // 降调系数
                    sonicProcessor.setRate(1.0f);           // 保持播放速率
                    sonicProcessor.setQuality(1);           // 可选：更高质量
                }

                a.this.f32b = false;
                int[] iArr = {0};
                short[] sArrArray = a.this.f31a.array();

                // PCM 循环播放标记
                boolean loop = true;

                while (!a.this.f32b) {
                    int iSynthesize = f29f.synthesize(sArrArray, 64000, iArr, 1);
                    if (iSynthesize == -1) {
                        Log.d("SynthesizerNative", "synthesize failed");
                        f29f.reset();
                        return;
                    }

                    int i = iArr[0];
                    if (i <= 0) break;

                    // 将 short[] 转为小端字节
                    int iMin = Math.min(sArrArray.length, i);
                    int i8 = iMin << 1;
                    byte[] inBytes = new byte[i8];
                    int bi = 0;
                    for (int si = 0; si < iMin; si++) {
                        short s = sArrArray[si];
                        inBytes[bi++] = (byte) (s & 0xFF);
                        inBytes[bi++] = (byte) ((s >>> 8) & 0xFF);
                    }

                    // 写入 Sonic 做“降调不变速”
                    sonicProcessor.writeBytesToStream(inBytes, inBytes.length);

                    // 读取处理后的 PCM（可能分多次返回）
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(inBytes.length + 1024);
                    byte[] outBuf = new byte[inBytes.length + 1024];
                    int numWritten;
                    do {
                        numWritten = sonicProcessor.readBytesFromStream(outBuf, outBuf.length);
                        if (numWritten > 0) {
                            baos.write(outBuf, 0, numWritten);
                        }
                    } while (numWritten > 0);

                    byte[] processedBytes = baos.toByteArray();

                    // 将处理后的字节转回 short[]，交给你的播放缓冲
                    short[] processedShorts = bytesLEToShorts(processedBytes);
                    pcmData = processedShorts; // 原来是 sArrArray，这里替换为降调后的数据

                    startPlaybackFromBeginning(); // 播放一次 PCM

                    // 可按你的播放时长/回调改成更稳妥的“等待播放完成”
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                // 结束时把内部残留冲刷出来（如有需要，可读出并播放最后一段）
                sonicProcessor.flushStream();

                f29f.reset();
            }
        }).start();
    }

    // 小端字节转 short[]
    private static short[] bytesLEToShorts(byte[] data) {
        int n = data.length / 2;
        short[] out = new short[n];
        for (int i = 0, j = 0; i < n; i++, j += 2) {
            out[i] = (short) ((data[j] & 0xFF) | ((data[j + 1] & 0xFF) << 8));
        }
        return out;
    }


    @Override // R6.h
    public final synchronized void release() {
        if (f28e.decrementAndGet() == 0) {
            f29f.destroy();
            f29f = null;
        }
    }
}