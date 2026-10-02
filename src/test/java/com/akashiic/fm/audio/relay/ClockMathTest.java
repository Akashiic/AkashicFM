package com.akashiic.fm.audio.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class ClockMathTest {

    @Test
    void trocaSimetrica() {
        // Servidor 5 s à frente; 10 ms de ida, 10 ms de volta, 30 ms segurando a resposta (fila do tick).
        long t0 = 1_000_000, offset = 5_000_000;
        long t1 = t0 + 10_000 + offset, t2 = t1 + 30_000, t3 = t2 - offset + 10_000;
        assertEquals(offset, ClockMath.offset(t0, t1, t2, t3));
        assertEquals(20_000, ClockMath.rtt(t0, t1, t2, t3));
    }

    @Test
    void trocaAssimetricaErraNoMaximoMetadeDoRtt() {
        long t0 = 0, offset = 777_000;
        long t1 = t0 + 2_000 + offset, t2 = t1, t3 = t2 - offset + 18_000; // ida 2 ms, volta 18 ms
        long rtt = ClockMath.rtt(t0, t1, t2, t3);
        assertTrue(Math.abs(ClockMath.offset(t0, t1, t2, t3) - offset) <= rtt / 2);
    }

    @Test
    void estimadorUsaAsDeMenorRtt() {
        ClockMath.Estimator e = new ClockMath.Estimator(16, 8);
        assertFalse(e.ready(3));
        Random rnd = new Random(42);
        long trueOffset = 123_456;
        for (int i = 0; i < 16; i++) {
            boolean congested = i % 2 == 0; // metade com fila e assimetria grandes
            long rtt = congested ? 80_000 + rnd.nextInt(40_000) : 1_000 + rnd.nextInt(500);
            long err = congested ? 30_000 : rnd.nextInt(300) - 150;
            e.add(trueOffset + err, rtt);
        }
        assertTrue(e.ready(3));
        assertTrue(Math.abs(e.offsetMicros() - trueOffset) < 200, "offset=" + e.offsetMicros());
        assertTrue(e.bestRttMicros() < 1_500);
    }

    @Test
    void janelaDescartaAsAntigas() {
        ClockMath.Estimator e = new ClockMath.Estimator(4, 2);
        for (int i = 0; i < 4; i++) e.add(1_000_000, 100); // relógio antigo
        for (int i = 0; i < 4; i++) e.add(2_000_000, 200); // ajuste do relógio
        assertEquals(2_000_000, e.offsetMicros());
        assertEquals(4, e.samples());
    }

    @Test
    void rttNegativoEIgnorado() {
        ClockMath.Estimator e = new ClockMath.Estimator(4, 2);
        e.add(5, -1);
        assertEquals(0, e.samples());
    }
}
