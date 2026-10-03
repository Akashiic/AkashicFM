package com.akashiic.fm.audio.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class FrameRingTest {

    private final AtomicLong clock = new AtomicLong(10_000);
    private final byte[] frame = { 1, 2, 3, 4 };

    private boolean add(FrameRing r) throws InterruptedException {
        return r.add(frame, frame.length, 2000, 500, () -> false);
    }

    @Test
    void ptsComecaNoRelogioEAndaVinteMs() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        for (int i = 0; i < 5; i++) add(r);
        List<FrameRing.Frame> out = new ArrayList<>();
        assertEquals(5, r.collect(-1, Long.MAX_VALUE, 100, out));
        for (int i = 0; i < 5; i++) {
            assertEquals(i, out.get(i).seq);
            assertEquals(10_000 + 20L * i, out.get(i).ptsMs);
        }
    }

    @Test
    void fimDoAudioEhOFimDoUltimoFrame() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        assertEquals(-1, r.endPtsMs()); // nada ainda
        assertEquals(-1, r.lastSeq());
        for (int i = 0; i < 3; i++) add(r);
        assertEquals(10_000 + 3 * 20L, r.endPtsMs()); // o terceiro começa em +40 e dura 20
        assertEquals(2, r.lastSeq());
    }

    @Test
    void entradaAtrasadaRebaseiaParaORelogio() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        add(r);
        add(r); // pts 10020
        clock.addAndGet(3000); // a rádio travou 3 s
        add(r);
        List<FrameRing.Frame> out = new ArrayList<>();
        r.collect(-1, Long.MAX_VALUE, 100, out);
        assertEquals(13_000, out.get(2).ptsMs);
        assertEquals(1, r.rebases());
    }

    @Test
    void atrasoPequenoNaoRebaseia() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        add(r);
        clock.addAndGet(400); // dentro de rebaseLag (500)
        add(r);
        List<FrameRing.Frame> out = new ArrayList<>();
        r.collect(-1, Long.MAX_VALUE, 100, out);
        assertEquals(10_020, out.get(1).ptsMs);
        assertEquals(0, r.rebases());
    }

    @Test
    void collectRespeitaSequenciaEPtsMaximo() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        for (int i = 0; i < 10; i++) add(r);
        List<FrameRing.Frame> out = new ArrayList<>();
        assertEquals(3, r.collect(4, 10_000 + 20 * 7, 100, out)); // seqs 5,6,7
        assertEquals(5, out.get(0).seq);
        assertEquals(7, out.get(2).seq);
        out.clear();
        assertEquals(2, r.collect(-1, Long.MAX_VALUE, 2, out));
    }

    @Test
    void cheioDescartaOMaisAntigo() throws Exception {
        FrameRing r = new FrameRing(4, clock::get);
        for (int i = 0; i < 6; i++) add(r);
        assertEquals(4, r.size());
        List<FrameRing.Frame> out = new ArrayList<>();
        r.collect(-1, Long.MAX_VALUE, 100, out);
        assertEquals(2, out.get(0).seq);
        assertEquals(5, r.lastSeq());
    }

    @Test
    void ouvinteNovoComecaPeloPts() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        for (int i = 0; i < 10; i++) add(r); // pts 10000..10180
        assertEquals(4, r.seqBeforePts(10_100)); // começa no seq 5 (pts 10100)
        assertEquals(-1, r.seqBeforePts(0));
        assertEquals(9, r.seqBeforePts(99_999));
        assertEquals(-1, new FrameRing(4, clock::get).seqBeforePts(0));
    }

    @Test
    void framesSaoCopias() throws Exception {
        FrameRing r = new FrameRing(10, clock::get);
        byte[] b = { 9, 9 };
        r.add(b, 2, 2000, 500, () -> false);
        b[0] = 0;
        List<FrameRing.Frame> out = new ArrayList<>();
        r.collect(-1, Long.MAX_VALUE, 10, out);
        assertEquals(9, out.get(0).data[0]);
    }

    @Test
    @Timeout(5)
    void escritaBloqueiaQuandoAdiantadaEDestravaComORelogio() throws Exception {
        FrameRing r = new FrameRing(1000, clock::get);
        // Frames 0..101 cabem (o último fica exatamente 2000 ms à frente); o 103º espera o relógio andar.
        for (int i = 0; i < 102; i++) assertTrue(r.add(frame, 4, 2000, 500, () -> false));
        AtomicBoolean done = new AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                done.set(r.add(frame, 4, 2000, 500, () -> false));
            } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(150);
        assertFalse(done.get());
        clock.addAndGet(100);
        t.join(2000);
        assertTrue(done.get());
    }

    @Test
    @Timeout(5)
    void fecharOuCancelarDestravaQuemEscreve() throws Exception {
        FrameRing r = new FrameRing(1000, clock::get);
        for (int i = 0; i < 102; i++) add(r);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            try {
                result.set(add(r));
            } catch (InterruptedException ignored) {}
        });
        t.start();
        Thread.sleep(100);
        r.close();
        t.join(2000);
        assertFalse(t.isAlive());
        assertFalse(result.get());
        assertTrue(r.isClosed());
    }
}
