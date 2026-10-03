package com.akashiic.fm.audio.relay;

import java.util.ArrayDeque;
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
 * <p>
 * Pausa (iPod): {@link #pause} tira do ring os frames que ainda não foram mandados (PTS no futuro) e os guarda; a
 * escrita fica bloqueada até {@link #resume}, e a primeira escrita depois disso devolve os guardados antes do frame
 * novo, com as mesmas sequências. Para os clientes, a pausa é só uma lacuna de PTS (como uma rádio que travou).
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
    private boolean paused;
    /** Frames tirados por {@link #pause}, na ordem: voltam antes do próximo frame novo. */
    private final ArrayDeque<byte[]> held = new ArrayDeque<>();

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
            while (!closed && (paused || hasLast && lastPts - clockMs.getAsLong() > maxAheadMs)) {
                if (cancelled.getAsBoolean()) return false;
                wait(FRAME_MS);
            }
            if (closed || cancelled.getAsBoolean()) return false;
            while (!held.isEmpty()) append(held.pollFirst(), rebaseLagMs);
            append(copy, rebaseLagMs);
            notifyAll();
            return true;
        }
    }

    private void append(byte[] copy, long rebaseLagMs) {
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
    }

    /**
     * Pausa: a escrita bloqueia até {@link #resume}, e os frames com PTS depois de {@code keepUntilPts} (os que
     * ninguém recebeu ainda: quem chama passa o limite de envio do relay, na mesma thread que envia) saem do ring e
     * ficam guardados, com as sequências deles livres para quando voltarem. Devolve quantos foram guardados.
     */
    public synchronized int pause(long keepUntilPts) {
        if (paused || closed) return 0;
        paused = true;
        int removed = 0;
        long firstRemovedPts = -1;
        while (count > 0) {
            int idx = (head + count - 1) % capacity;
            if (pts[idx] <= keepUntilPts) break;
            held.addFirst(data[idx]);
            nextSeq = seqs[idx];
            firstRemovedPts = pts[idx];
            data[idx] = null;
            count--;
            removed++;
        }
        if (removed > 0) lastPts = count > 0 ? pts[(head + count - 1) % capacity] : firstRemovedPts - FRAME_MS;
        notifyAll();
        return removed;
    }

    /** Volta a aceitar escrita (os guardados entram primeiro). */
    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Frames guardados pela pausa. */
    public synchronized int heldCount() {
        return held.size();
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

    /** PTS em que termina o último frame guardado (o fim do áudio até agora), ou -1 se o ring nunca recebeu nada. */
    public synchronized long endPtsMs() {
        return hasLast ? lastPts + FRAME_MS : -1;
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
