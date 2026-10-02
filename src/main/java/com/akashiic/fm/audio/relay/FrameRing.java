package com.akashiic.fm.audio.relay;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Frames Opus de uma estação do relay, com número de sequência e PTS (tempo do servidor, em ms, em que o
 * frame é "ao vivo"). A thread da estação escreve; a thread principal do servidor lê para mandar aos ouvintes.
 * <p>
 * PTS: o primeiro frame recebe o relógio atual e cada seguinte +20 ms. Se a entrada atrasar além de
 * {@code rebaseLagMs} (a rádio travou ou reconectou), o próximo frame volta para o relógio atual: um salto
 * para a frente que os clientes tratam como lacuna. A escrita bloqueia enquanto o último PTS estiver mais
 * de {@code maxAheadMs} à frente do relógio: assim o burst inicial dos servidores de streaming vira folga, e
 * o TCP segura o resto, sem acumular memória.
 */
public final class FrameRing {

    public static final int FRAME_MS = 20;

    /** Um frame lido do ring. Os bytes são uma cópia: o chamador pode guardar. */
    public static final class Frame {

        public final long seq;
        public final long ptsMs;
        public final byte[] data;

        public Frame(long seq, long ptsMs, byte[] data) {
            this.seq = seq;
            this.ptsMs = ptsMs;
            this.data = data;
        }
    }

    private final int capacity;
    private final long[] seqs;
    private final long[] pts;
    private final byte[][] data;
    private final LongSupplier clockMs;
    private int head; // índice do mais antigo
    private int count;
    private long nextSeq;
    private long lastPts;
    private boolean hasLast;
    private boolean closed;
    private long rebases;

    public FrameRing(int capacity, LongSupplier clockMs) {
        if (capacity <= 0) throw new IllegalArgumentException("capacidade inválida");
        this.capacity = capacity;
        this.seqs = new long[capacity];
        this.pts = new long[capacity];
        this.data = new byte[capacity][];
        this.clockMs = clockMs;
    }

    /**
     * Thread da estação: guarda um frame (copia {@code len} bytes). Bloqueia enquanto estiver adiantado demais.
     * Devolve false se o ring foi fechado ou {@code cancelled} ficou verdadeiro.
     */
    public boolean add(byte[] frame, int len, long maxAheadMs, long rebaseLagMs, BooleanSupplier cancelled)
        throws InterruptedException {
        byte[] copy = new byte[len];
        System.arraycopy(frame, 0, copy, 0, len);
        synchronized (this) {
            while (!closed && hasLast && lastPts - clockMs.getAsLong() > maxAheadMs) {
                if (cancelled.getAsBoolean()) return false;
                wait(FRAME_MS);
            }
            if (closed || cancelled.getAsBoolean()) return false;
            long now = clockMs.getAsLong();
            long p = hasLast ? lastPts + FRAME_MS : now;
            if (p < now - rebaseLagMs) {
                p = now;
                rebases++;
            }
            int idx;
            if (count == capacity) { // cheio: descarta o mais antigo
                idx = head;
                head = (head + 1) % capacity;
            } else {
                idx = (head + count) % capacity;
                count++;
            }
            seqs[idx] = nextSeq++;
            pts[idx] = p;
            data[idx] = copy;
            lastPts = p;
            hasLast = true;
            notifyAll();
            return true;
        }
    }

    /**
     * Copia para {@code out}, em ordem, os frames com sequência maior que {@code afterSeq} e PTS até
     * {@code maxPts}, no máximo {@code maxFrames}. Devolve quantos copiou.
     */
    public synchronized int collect(long afterSeq, long maxPts, int maxFrames, List<Frame> out) {
        int n = 0;
        for (int i = 0; i < count && n < maxFrames; i++) {
            int idx = (head + i) % capacity;
            if (seqs[idx] <= afterSeq) continue;
            if (pts[idx] > maxPts) break; // PTS só cresce dentro do ring
            out.add(new Frame(seqs[idx], pts[idx], data[idx]));
            n++;
        }
        return n;
    }

    /**
     * Sequência anterior ao primeiro frame com PTS >= {@code ptsMs} (para um ouvinte novo começar dali).
     * Se todos forem mais antigos, devolve a última sequência escrita; se o ring estiver vazio, -1.
     */
    public synchronized long seqBeforePts(long ptsMs) {
        for (int i = 0; i < count; i++) {
            int idx = (head + i) % capacity;
            if (pts[idx] >= ptsMs) return seqs[idx] - 1;
        }
        return nextSeq - 1;
    }

    public synchronized long lastSeq() {
        return nextSeq - 1;
    }

    public synchronized int size() {
        return count;
    }

    /** Quantas vezes o PTS saltou para o relógio por atraso da entrada. */
    public synchronized long rebases() {
        return rebases;
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }
}
