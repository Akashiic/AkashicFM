package com.akashiic.fm.audio.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimedPcmRingTest {

    private static short[] frames(int n) {
        return new short[n * 2];
    }

    @Test
    void ptsAndaComALeitura() throws Exception {
        TimedPcmRing r = new TimedPcmRing(48000);
        assertTrue(Double.isNaN(r.ptsAtRead()));
        r.write(frames(960), 960, 1000.0, true, () -> false);
        r.write(frames(960), 960, -1, false, () -> false); // continua o segmento: PTS implícito
        assertEquals(1000.0, r.ptsAtRead(), 1e-9);
        r.read(frames(480), 480);
        assertEquals(1010.0, r.ptsAtRead(), 1e-9);
        r.read(frames(960), 960);
        assertEquals(1030.0, r.ptsAtRead(), 1e-9);
    }

    @Test
    void segmentoNovoTrocaOPtsNaFronteira() throws Exception {
        TimedPcmRing r = new TimedPcmRing(48000);
        r.write(frames(960), 960, 1000.0, true, () -> false);
        r.write(frames(960), 960, 5000.0, true, () -> false); // descontinuidade
        assertEquals(960, r.framesUntilDiscontinuity());
        r.read(frames(900), 900);
        assertEquals(1018.75, r.ptsAtRead(), 1e-9);
        r.read(frames(60), 60); // exatamente na fronteira
        assertEquals(5000.0, r.ptsAtRead(), 1e-9);
        r.read(frames(48), 48);
        assertEquals(5001.0, r.ptsAtRead(), 1e-9);
    }

    @Test
    void pularAlcancaOPtsDevido() throws Exception {
        TimedPcmRing r = new TimedPcmRing(48000);
        r.write(frames(4800), 4800, 2000.0, true, () -> false); // 100 ms
        assertEquals(2400, r.skip(2400));
        assertEquals(2050.0, r.ptsAtRead(), 1e-9);
        assertEquals(2400, r.skip(99_999)); // não passa do que existe
        assertTrue(Double.isNaN(r.ptsAtRead()));
    }

    @Test
    void voltaDoBufferPreservaDados() throws Exception {
        TimedPcmRing r = new TimedPcmRing(1000);
        short[] out = new short[2 * 700];
        int value = 0, expect = 0;
        for (int round = 0; round < 20; round++) {
            short[] in = new short[2 * 600];
            for (int i = 0; i < 600; i++) {
                in[2 * i] = (short) value;
                in[2 * i + 1] = (short) -value;
                value++;
            }
            r.write(in, 600, round * 12.5, round == 0, () -> false);
            int got = r.read(out, 600);
            for (int i = 0; i < got; i++) {
                assertEquals((short) expect, out[2 * i]);
                assertEquals((short) -expect, out[2 * i + 1]);
                expect++;
            }
        }
        assertEquals(value, expect);
    }

    @Test
    void fimSoDepoisDeLerTudo() throws Exception {
        TimedPcmRing r = new TimedPcmRing(100);
        r.write(frames(10), 10, 0, true, () -> false);
        r.close();
        assertTrue(r.isClosed());
        assertEquals(10, r.read(frames(20), 20));
        assertTrue(r.isFinished());
    }
}
