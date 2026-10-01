package com.akashiic.fm.tools;

import java.util.Random;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * Benchmark manual do Concentus (Opus em Java puro). Uso: java ... com.akashiic.fm.tools.OpusBench [segundos]
 * Mede encode (servidor, por estação) e decode (cliente, por estação ouvida)
 * em 48 kHz estéreo, frames de 20 ms, sinal sintético com conteúdo "musical".
 */
public final class OpusBench {

    static final int FS = 48000;
    static final int CH = 2;
    static final int FRAME = FS / 50; // 20 ms = 960 amostras por canal

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        short[] pcm = synth(seconds);

        System.out.println(
            "java " + System.getProperty("java.version") + ", " + seconds + " s de audio, 48 kHz estereo, 20 ms");
        System.out.println("bitrate  complex  encode(ms/s audio)  %core-enc  decode(ms/s audio)  %core-dec  bytes/s");
        for (int bitrate : new int[] { 64000, 96000 }) {
            for (int complexity : new int[] { 5, 10 }) {
                run(pcm, seconds, bitrate, complexity, true); // aquecimento do JIT
                run(pcm, seconds, bitrate, complexity, false);
            }
        }
    }

    static void run(short[] pcm, int seconds, int bitrate, int complexity, boolean warmup) throws Exception {
        OpusEncoder enc = new OpusEncoder(FS, CH, OpusApplication.OPUS_APPLICATION_AUDIO);
        enc.setBitrate(bitrate);
        enc.setComplexity(complexity);
        enc.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
        enc.setUseVBR(true);
        enc.setUseConstrainedVBR(true);
        OpusDecoder dec = new OpusDecoder(FS, CH);

        int frames = pcm.length / (FRAME * CH);
        byte[][] packets = new byte[frames][];
        byte[] out = new byte[1275];
        long totalBytes = 0;

        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            int n = enc.encode(pcm, f * FRAME * CH, FRAME, out, 0, out.length);
            packets[f] = java.util.Arrays.copyOf(out, n);
            totalBytes += n;
        }
        long t1 = System.nanoTime();

        short[] pcmOut = new short[FRAME * CH];
        long decodedSamples = 0;
        for (int f = 0; f < frames; f++) {
            decodedSamples += dec.decode(packets[f], 0, packets[f].length, pcmOut, 0, FRAME, false);
        }
        long t2 = System.nanoTime();

        if (warmup) return;
        if (decodedSamples != (long) frames * FRAME) throw new IllegalStateException("decode perdeu amostras");

        double encMsPerSec = (t1 - t0) / 1e6 / seconds;
        double decMsPerSec = (t2 - t1) / 1e6 / seconds;
        System.out.printf(
            "%7d  %7d  %18.2f  %8.2f%%  %18.2f  %8.2f%%  %7d%n",
            bitrate,
            complexity,
            encMsPerSec,
            encMsPerSec / 10.0,
            decMsPerSec,
            decMsPerSec / 10.0,
            totalBytes / seconds);
    }

    /** Acordes + varredura + ruído filtrado + transientes: força o encoder a trabalhar como em música real. */
    static short[] synth(int seconds) {
        int n = FS * seconds;
        short[] pcm = new short[n * CH];
        Random rnd = new Random(42);
        double[] chord = { 220.0, 277.18, 329.63, 440.0, 554.37 };
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / FS;
            double s = 0;
            for (double f : chord) s += Math.sin(2 * Math.PI * f * t * (1 + 0.002 * Math.sin(2 * Math.PI * 0.3 * t)));
            s /= chord.length;
            s += 0.3 * Math.sin(2 * Math.PI * (200 + 4000 * ((t % 4) / 4)) * t);
            lp += 0.2 * (rnd.nextGaussian() - lp);
            s += 0.15 * lp;
            if ((i % (FS / 2)) < 400) s += 0.6 * rnd.nextGaussian() * (1 - (i % (FS / 2)) / 400.0);
            double l = s * (0.8 + 0.2 * Math.sin(2 * Math.PI * 0.1 * t));
            double r = s * (0.8 + 0.2 * Math.cos(2 * Math.PI * 0.1 * t));
            pcm[2 * i] = (short) Math.max(-32768, Math.min(32767, l * 12000));
            pcm[2 * i + 1] = (short) Math.max(-32768, Math.min(32767, r * 12000));
        }
        return pcm;
    }
}
