package com.akashiic.fm.audio.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.audio.dsp.TimedPcmRing;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;

class RelayDecoderTest {

    /** Frames Opus reais de um tom de 440 Hz (20 ms cada). */
    private static byte[][] encodeTone(int count) throws Exception {
        OpusEncoder enc = new OpusEncoder(48000, 2, OpusApplication.OPUS_APPLICATION_AUDIO);
        enc.setBitrate(64000);
        byte[][] out = new byte[count][];
        short[] pcm = new short[960 * 2];
        byte[] pkt = new byte[1275];
        int t = 0;
        for (int f = 0; f < count; f++) {
            for (int i = 0; i < 960; i++, t++) {
                short v = (short) (8000 * Math.sin(2 * Math.PI * 440 * t / 48000.0));
                pcm[2 * i] = v;
                pcm[2 * i + 1] = v;
            }
            int n = enc.encode(pcm, 0, 960, pkt, 0, pkt.length);
            out[f] = java.util.Arrays.copyOf(pkt, n);
        }
        return out;
    }

    @Test
    void sequenciaNormalViraPcmContinuoComPts() throws Exception {
        byte[][] frames = encodeTone(10);
        TimedPcmRing ring = new TimedPcmRing(48000);
        RelayDecoder d = new RelayDecoder();
        for (int i = 0; i < 10; i++) assertTrue(d.accept(i, 1000 + 20L * i, frames[i], ring, () -> false));
        assertEquals(9600, ring.available());
        assertEquals(1000.0, ring.ptsAtRead(), 1e-9);
        // Há áudio de verdade (o tom), não silêncio.
        short[] buf = new short[9600 * 2];
        ring.read(buf, 9600);
        long energy = 0;
        for (int i = 4800; i < buf.length; i++) energy += Math.abs(buf[i]);
        assertTrue(energy / (buf.length - 4800) > 1000, "energia " + energy);
    }

    @Test
    void lacunaPequenaEPreenchidaMantendoALinhaDoTempo() throws Exception {
        byte[][] frames = encodeTone(5);
        TimedPcmRing ring = new TimedPcmRing(48000);
        RelayDecoder d = new RelayDecoder();
        d.accept(0, 1000, frames[0], ring, () -> false);
        d.accept(1, 1020, frames[1], ring, () -> false);
        d.accept(4, 1100, frames[4], ring, () -> false); // faltaram 60 ms (3 frames)
        assertEquals(960 * 6, ring.available()); // 2 + 3 preenchidos + 1
        assertEquals(60, d.filledMs());
        ring.skip(960 * 5);
        assertEquals(1100.0, ring.ptsAtRead(), 1e-9); // o frame 4 cai exatamente no PTS dele
    }

    @Test
    void lacunaQuebradaPreencheComSilencioOResto() throws Exception {
        byte[][] frames = encodeTone(3);
        TimedPcmRing ring = new TimedPcmRing(48000);
        RelayDecoder d = new RelayDecoder();
        d.accept(0, 1000, frames[0], ring, () -> false);
        d.accept(1, 1053, frames[1], ring, () -> false); // rebase não alinhado: 33 ms de lacuna
        assertEquals(960 + 960 + 13 * 48 + 960, ring.available());
        ring.skip(960 + 960 + 13 * 48);
        assertEquals(1053.0, ring.ptsAtRead(), 1e-9);
    }

    @Test
    void lacunaGrandeAbreSegmentoNovo() throws Exception {
        byte[][] frames = encodeTone(3);
        TimedPcmRing ring = new TimedPcmRing(96000);
        RelayDecoder d = new RelayDecoder();
        d.accept(0, 1000, frames[0], ring, () -> false);
        d.accept(1, 9000, frames[1], ring, () -> false); // 8 s: descontinuidade
        assertEquals(1920, ring.available());
        assertEquals(960, ring.framesUntilDiscontinuity());
        assertEquals(2, d.segmentsStarted());
        ring.skip(960);
        assertEquals(9000.0, ring.ptsAtRead(), 1e-9);
    }

    @Test
    void repetidoOuVelhoEIgnoradoEcorrompidoNaoDerruba() throws Exception {
        byte[][] frames = encodeTone(3);
        TimedPcmRing ring = new TimedPcmRing(48000);
        RelayDecoder d = new RelayDecoder();
        d.accept(5, 1000, frames[0], ring, () -> false);
        d.accept(5, 1000, frames[0], ring, () -> false);
        d.accept(3, 960, frames[1], ring, () -> false);
        assertEquals(960, ring.available());
        d.accept(6, 1020, new byte[] { (byte) 0xFF, 0x00, 0x13 }, ring, () -> false);
        assertEquals(1920, ring.available()); // vira PLC, a linha do tempo segue
    }
}
