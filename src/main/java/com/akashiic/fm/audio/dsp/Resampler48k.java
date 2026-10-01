package com.akashiic.fm.audio.dsp;

/**
 * Converte PCM intercalado de qualquer taxa/canais para 48 kHz estéreo (o formato do Opus).
 * Interpolação linear: suficiente para o spike. A versão final usa windowed-sinc.
 */
public final class Resampler48k {

    public static final int OUT_RATE = 48000;

    private final int inRate;
    private final int inChannels;
    private final double step; // amostras de entrada por amostra de saída
    private double pos; // posição fracionária relativa a "prev"
    private float prevL, prevR;
    private boolean primed;

    public Resampler48k(int inRate, int inChannels) {
        this.inRate = inRate;
        this.inChannels = inChannels;
        this.step = (double) inRate / OUT_RATE;
    }

    public int inRate() {
        return inRate;
    }

    /** Devolve quantas amostras (intercaladas, estéreo) foram escritas em {@code out}. */
    public int process(short[] in, int inLen, short[] out) {
        int frames = inLen / inChannels;
        int w = 0;
        for (int f = 0; f < frames; f++) {
            float l = in[f * inChannels];
            float r = inChannels > 1 ? in[f * inChannels + 1] : l;
            if (!primed) {
                prevL = l;
                prevR = r;
                primed = true;
                continue;
            }
            while (pos < 1.0) {
                float t = (float) pos;
                out[w++] = (short) (prevL + (l - prevL) * t);
                out[w++] = (short) (prevR + (r - prevR) * t);
                pos += step;
            }
            pos -= 1.0;
            prevL = l;
            prevR = r;
        }
        return w;
    }

    /** Tamanho seguro do buffer de saída para {@code inLen} amostras de entrada. */
    public int maxOut(int inLen) {
        return (int) Math.ceil((inLen / (double) inChannels) / step + 2) * 2;
    }
}
