package com.akashiic.fm.audio.dsp;

import java.util.function.BooleanSupplier;

/**
 * Buffer circular de PCM estéreo 16-bit (L,R intercalados) entre a thread que decodifica (produtora) e a
 * thread principal que alimenta o OpenAL (consumidora). A escrita bloqueia quando enche: o decoder para de
 * ler a rede e o TCP segura o servidor de streaming, sem acumular memória.
 */
public final class PcmRing {

    private final short[] buf;
    private final int capacityFrames;
    private long writePos; // em frames, cresce para sempre
    private long readPos;
    private boolean closed;

    public PcmRing(int capacityFrames) {
        if (capacityFrames <= 0) throw new IllegalArgumentException("capacidade inválida");
        this.capacityFrames = capacityFrames;
        this.buf = new short[capacityFrames * 2];
    }

    public int capacityFrames() {
        return capacityFrames;
    }

    public synchronized int available() {
        return (int) (writePos - readPos);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /** Fechado e vazio: não vai chegar mais nada. */
    public synchronized boolean isFinished() {
        return closed && writePos == readPos;
    }

    /**
     * Escreve {@code frames} frames de {@code src} (intercalado). Bloqueia enquanto não há espaço.
     * Devolve false se o ring foi fechado ou {@code cancelled} ficou verdadeiro antes de terminar.
     */
    public boolean write(short[] src, int frames, BooleanSupplier cancelled) throws InterruptedException {
        int done = 0;
        while (done < frames) {
            synchronized (this) {
                while (!closed && writePos - readPos >= capacityFrames) {
                    if (cancelled.getAsBoolean()) return false;
                    wait(50);
                }
                if (closed || cancelled.getAsBoolean()) return false;
                int space = capacityFrames - (int) (writePos - readPos);
                int n = Math.min(space, frames - done);
                copyIn(src, done, n);
                writePos += n;
                done += n;
                notifyAll();
            }
        }
        return true;
    }

    private void copyIn(short[] src, int srcFrame, int n) {
        int start = (int) (writePos % capacityFrames);
        int first = Math.min(n, capacityFrames - start);
        System.arraycopy(src, srcFrame * 2, buf, start * 2, first * 2);
        if (n > first) System.arraycopy(src, (srcFrame + first) * 2, buf, 0, (n - first) * 2);
    }

    /** Lê até {@code frames} frames para {@code dst} (intercalado). Nunca bloqueia. Devolve quantos leu. */
    public synchronized int read(short[] dst, int frames) {
        int n = Math.min(frames, (int) (writePos - readPos));
        if (n <= 0) return 0;
        int start = (int) (readPos % capacityFrames);
        int first = Math.min(n, capacityFrames - start);
        System.arraycopy(buf, start * 2, dst, 0, first * 2);
        if (n > first) System.arraycopy(buf, 0, dst, first * 2, (n - first) * 2);
        readPos += n;
        notifyAll();
        return n;
    }

    /** Descarta o que está guardado (ex.: depois de uma pausa longa). */
    public synchronized void clear() {
        readPos = writePos;
        notifyAll();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
