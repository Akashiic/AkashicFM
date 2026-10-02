package com.akashiic.fm.audio.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PcmRingTest {

    private static short[] frames(int start, int n) {
        short[] s = new short[n * 2];
        for (int i = 0; i < n; i++) {
            s[2 * i] = (short) (start + i);
            s[2 * i + 1] = (short) -(start + i);
        }
        return s;
    }

    @Test
    void dadosAtravessamAVoltaDoBuffer() throws Exception {
        PcmRing ring = new PcmRing(10);
        short[] out = new short[20];
        int next = 0, expect = 0;
        for (int round = 0; round < 50; round++) {
            int n = 1 + round % 7;
            assertTrue(ring.write(frames(next, n), n, () -> false));
            next += n;
            int got = ring.read(out, 10);
            for (int i = 0; i < got; i++) {
                assertEquals((short) expect, out[2 * i]);
                assertEquals((short) -expect, out[2 * i + 1]);
                expect++;
            }
        }
        assertEquals(next, expect);
        assertEquals(0, ring.available());
    }

    @Test
    void leituraNuncaBloqueia() {
        PcmRing ring = new PcmRing(8);
        assertEquals(0, ring.read(new short[16], 8));
    }

    @Test
    @Timeout(5)
    void escritaBloqueiaAteLiberarEspaco() throws Exception {
        PcmRing ring = new PcmRing(4);
        assertTrue(ring.write(frames(0, 4), 4, () -> false));
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                ok.set(ring.write(frames(4, 2), 2, () -> false));
            } catch (InterruptedException ignored) {}
            done.countDown();
        });
        t.start();
        assertFalse(done.await(200, TimeUnit.MILLISECONDS), "deveria estar bloqueada com o ring cheio");
        short[] out = new short[8];
        assertEquals(2, ring.read(out, 2));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(ok.get());
        assertEquals(4, ring.available());
    }

    @Test
    @Timeout(5)
    void fecharDestravaQuemEscreve() throws Exception {
        PcmRing ring = new PcmRing(2);
        ring.write(frames(0, 2), 2, () -> false);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            try {
                result.set(ring.write(frames(2, 2), 2, () -> false));
            } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(100);
        ring.close();
        t.join(2000);
        assertFalse(t.isAlive());
        assertFalse(result.get());
    }

    @Test
    @Timeout(5)
    void cancelamentoDestravaQuemEscreve() throws Exception {
        PcmRing ring = new PcmRing(2);
        ring.write(frames(0, 2), 2, () -> false);
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicBoolean result = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            try {
                result.set(ring.write(frames(2, 2), 2, cancel::get));
            } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(100);
        cancel.set(true);
        t.join(2000);
        assertFalse(t.isAlive());
        assertFalse(result.get());
    }

    @Test
    void fimSoDepoisDeLerTudo() throws Exception {
        PcmRing ring = new PcmRing(8);
        ring.write(frames(0, 3), 3, () -> false);
        ring.close();
        assertTrue(ring.isClosed());
        assertFalse(ring.isFinished());
        assertEquals(3, ring.read(new short[16], 8));
        assertTrue(ring.isFinished());
        assertFalse(ring.write(frames(0, 1), 1, () -> false));
    }

    @Test
    void limparDescarta() throws Exception {
        PcmRing ring = new PcmRing(8);
        ring.write(frames(0, 5), 5, () -> false);
        ring.clear();
        assertEquals(0, ring.available());
    }
}
