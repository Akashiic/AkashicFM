package com.akashiic.fm.audio.relay;

import java.util.function.BooleanSupplier;

import com.akashiic.fm.audio.dsp.TimedPcmRing;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

/**
 * Decodifica os frames Opus de uma estação para o {@link TimedPcmRing}, mantendo a linha do tempo contínua:
 * <ul>
 * <li>frame repetido ou velho: ignorado;</li>
 * <li>lacuna de até {@link #MAX_FILL_MS}: preenchida (PLC do Opus nos primeiros frames, depois silêncio),
 * para a sincronia não se perder com um pacote atrasado ou um soluço da rádio;</li>
 * <li>lacuna maior ou PTS voltando: segmento novo (quem toca ressincroniza).</li>
 * </ul>
 * Uma instância por estação, usada por uma thread só.
 */
public final class RelayDecoder {

    public static final int MAX_FILL_MS = 1000;
    static final int PLC_FRAMES = 3;
    private static final int FRAME_SAMPLES = 960; // 20 ms a 48 kHz
    private static final int SAMPLES_PER_MS = 48;

    private final OpusDecoder decoder;
    private final short[] pcm = new short[FRAME_SAMPLES * 6 * 2]; // até 120 ms (maior frame do Opus)
    private final short[] silence = new short[FRAME_SAMPLES * 2];
    private long lastSeq = -1;
    private long expectedPts;
    private boolean hasExpected;
    private long filledMs, segmentsStarted, corrupt;

    public RelayDecoder() {
        try {
            decoder = new OpusDecoder(TimedPcmRing.SAMPLE_RATE, 2);
        } catch (OpusException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Devolve false se o ring fechou ou {@code cancelled} ficou verdadeiro. */
    public boolean accept(long seq, long ptsMs, byte[] data, TimedPcmRing ring, BooleanSupplier cancelled)
        throws InterruptedException {
        if (lastSeq >= 0 && seq <= lastSeq) return true;
        boolean newSegment = !hasExpected;
        if (hasExpected) {
            long gap = ptsMs - expectedPts;
            if (gap < 0 || gap > MAX_FILL_MS) {
                newSegment = true;
                decoder.resetState();
            } else if (gap > 0 && !fill(expectedPts, gap, ring, cancelled)) {
                return false;
            }
        }
        if (newSegment) segmentsStarted++;
        int n = decode(data);
        if (!ring.write(pcm, n, ptsMs, newSegment, cancelled)) return false;
        lastSeq = seq;
        expectedPts = ptsMs + n / SAMPLES_PER_MS;
        hasExpected = true;
        return true;
    }

    private boolean fill(long fromPts, long gapMs, TimedPcmRing ring, BooleanSupplier cancelled)
        throws InterruptedException {
        filledMs += gapMs;
        long frames = gapMs / 20;
        for (int i = 0; i < frames; i++) {
            short[] src = silence;
            int n = FRAME_SAMPLES;
            if (i < PLC_FRAMES) {
                try {
                    n = decoder.decode(null, 0, 0, pcm, 0, FRAME_SAMPLES, false);
                    src = pcm;
                } catch (OpusException e) {
                    n = FRAME_SAMPLES;
                }
            }
            if (!ring.write(src, n, fromPts + 20L * i, false, cancelled)) return false;
        }
        int rest = (int) (gapMs % 20) * SAMPLES_PER_MS;
        return rest <= 0 || ring.write(silence, rest, fromPts + 20L * frames, false, cancelled);
    }

    private int decode(byte[] data) {
        try {
            return decoder.decode(data, 0, data.length, pcm, 0, FRAME_SAMPLES * 6, false);
        } catch (OpusException | RuntimeException e) {
            corrupt++;
            try {
                return decoder.decode(null, 0, 0, pcm, 0, FRAME_SAMPLES, false);
            } catch (OpusException e2) {
                java.util.Arrays.fill(pcm, 0, FRAME_SAMPLES * 2, (short) 0);
                return FRAME_SAMPLES;
            }
        }
    }

    public long filledMs() {
        return filledMs;
    }

    public long segmentsStarted() {
        return segmentsStarted;
    }

    public long corruptFrames() {
        return corrupt;
    }
}
