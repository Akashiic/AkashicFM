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

    // ---- Pausa (iPod) ----

    private static byte[] numbered(int i) {
        return new byte[] { (byte) i, (byte) (i >> 8) };
    }

    private static int number(FrameRing.Frame f) {
        return (f.data[0] & 0xFF) | (f.data[1] & 0xFF) << 8;
    }

    private Thread writer(FrameRing r, int number, AtomicBoolean result) {
        Thread t = new Thread(() -> {
            try {
                byte[] b = numbered(number);
                result.set(r.add(b, b.length, 2000, 500, () -> false));
            } catch (InterruptedException ignored) {}
        });
        t.start();
        return t;
    }

    @Test
    @Timeout(5)
    void pausaGuardaOsFramesNaoEnviadosEDevolveNaOrdemComAsMesmasSequencias() throws Exception {
        FrameRing r = new FrameRing(1000, clock::get);
        for (int i = 0; i < 50; i++) assertTrue(r.add(numbered(i), 2, 2000, 500, () -> false)); // PTS 10000..10980
        // Enviados até 10100 (seqs 0..5); o resto sai do ring.
        assertEquals(44, r.pause(10_100));
        assertTrue(r.isPaused());
        assertEquals(6, r.size());
        assertEquals(44, r.heldCount());
        assertEquals(5, r.lastSeq());
        assertEquals(10_120, r.endPtsMs());
        assertEquals(0, r.pause(10_100)); // já pausado

        AtomicBoolean done = new AtomicBoolean();
        Thread t = writer(r, 50, done);
        Thread.sleep(150);
        assertFalse(done.get()); // pausado: a escrita espera mesmo sem estar adiantada
        clock.addAndGet(5000); // pausa longa
        r.resume();
        t.join(2000);
        assertTrue(done.get());
        assertEquals(0, r.heldCount());

        List<FrameRing.Frame> out = new ArrayList<>();
        assertEquals(51, r.collect(-1, Long.MAX_VALUE, 100, out));
        for (int i = 0; i < 51; i++) {
            assertEquals(i, out.get(i).seq);
            assertEquals(i, number(out.get(i)));
        }
        assertEquals(10_100, out.get(5).ptsMs);
        assertEquals(15_000, out.get(6).ptsMs); // a volta rebaseia para o relógio: lacuna para os clientes
        assertEquals(15_000 + 20L * 44, out.get(50).ptsMs);
    }

    @Test
    @Timeout(5)
    void pausaCurtaContinuaSemLacuna() throws Exception {
        FrameRing r = new FrameRing(1000, clock::get);
        for (int i = 0; i < 20; i++) add(r);
        assertEquals(14, r.pause(10_100));
        clock.addAndGet(100);
        r.resume();
        add(r);
        List<FrameRing.Frame> out = new ArrayList<>();
        r.collect(5, Long.MAX_VALUE, 100, out);
        assertEquals(15, out.size());
        assertEquals(10_120, out.get(0).ptsMs);
        assertEquals(0, r.rebases());
    }

    @Test
    @Timeout(5)
    void escritorPresoPorEstarAdiantadoNaoPassaNaFrenteDosGuardados() throws Exception {
        FrameRing r = new FrameRing(1000, clock::get);
        for (int i = 0; i < 102; i++) assertTrue(r.add(numbered(i), 2, 2000, 500, () -> false));
        AtomicBoolean done = new AtomicBoolean();
        Thread t = writer(r, 102, done); // espera o relógio (adiantado 2 s)
        Thread.sleep(100);
        assertEquals(96, r.pause(10_100)); // agora não está mais adiantado, mas está pausado
        Thread.sleep(100);
        assertFalse(done.get());
        r.resume();
        t.join(2000);
        assertTrue(done.get());
        List<FrameRing.Frame> out = new ArrayList<>();
        assertEquals(103, r.collect(-1, Long.MAX_VALUE, 200, out));
        for (int i = 0; i < 103; i++) assertEquals(i, number(out.get(i)));
    }

    @Test
    @Timeout(5)
    void fecharDuranteAPausaDestravaQuemEscreve() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        add(r);
        r.pause(Long.MAX_VALUE); // nada a guardar
        assertEquals(0, r.heldCount());
        AtomicBoolean result = new AtomicBoolean(true);
        Thread t = writer(r, 1, result);
        Thread.sleep(100);
        r.close();
        t.join(2000);
        assertFalse(t.isAlive());
        assertFalse(result.get());
        assertEquals(0, r.pause(0)); // fechado: não pausa
    }

    @Test
    void pausaQueEsvaziaORingMantemOFimNoUltimoEnviado() throws Exception {
        FrameRing r = new FrameRing(100, clock::get);
        for (int i = 0; i < 5; i++) add(r); // 10000..10080
        assertEquals(5, r.pause(9_000)); // nenhum foi enviado
        assertEquals(0, r.size());
        assertEquals(-1, r.lastSeq());
        assertEquals(10_000, r.endPtsMs());
    }
}
