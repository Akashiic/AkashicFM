package com.akashiic.fm.audio.dsp;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/**
 * Ring de PCM estéreo 16-bit a 48 kHz que sabe o PTS (tempo do servidor, em ms) da posição de leitura.
 * O PCM é guardado em segmentos contínuos: cada segmento marca o PTS do seu primeiro frame e os seguintes
 * valem +1/48 ms cada. Lacunas pequenas são preenchidas por quem escreve (o decoder); um segmento novo
 * marca uma descontinuidade de verdade (rebase do servidor), e quem lê ressincroniza.
 */
public final class TimedPcmRing {

    public static final int SAMPLE_RATE = 48000;
    private static final double MS_PER_FRAME = 1000.0 / SAMPLE_RATE;

    private static final class Segment {

        final long startFrame; // posição absoluta (frames) do primeiro frame do segmento
        final double ptsMs;

        Segment(long startFrame, double ptsMs) {
            this.startFrame = startFrame;
            this.ptsMs = ptsMs;
        }
    }

    private final short[] buf;
    private final int capacityFrames;
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private long writePos;
    private long readPos;
    private boolean closed;

    public TimedPcmRing(int capacityFrames) {
        if (capacityFrames <= 0) throw new IllegalArgumentException("capacidade inválida");
        this.capacityFrames = capacityFrames;
        this.buf = new short[capacityFrames * 2];
    }

    public synchronized int available() {
        return (int) (writePos - readPos);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized boolean isFinished() {
        return closed && writePos == readPos;
    }

    /**
     * Escreve {@code frames} frames. Com {@code newSegment} (ou no primeiro write), {@code ptsMs} é o PTS do
     * primeiro deles; senão continuam o segmento atual e o PTS passado é ignorado. Bloqueia enquanto não há
     * espaço. Devolve false se fechou ou {@code cancelled} ficou verdadeiro.
     */
    public boolean write(short[] src, int frames, double ptsMs, boolean newSegment, BooleanSupplier cancelled)
        throws InterruptedException {
        int done = 0;
        boolean marked = false;
        while (done < frames) {
            synchronized (this) {
                while (!closed && writePos - readPos >= capacityFrames) {
                    if (cancelled.getAsBoolean()) return false;
                    wait(50);
                }
                if (closed || cancelled.getAsBoolean()) return false;
                if (!marked) {
                    if (newSegment || segments.isEmpty()) segments.addLast(new Segment(writePos, ptsMs));
                    marked = true;
                }
                int space = capacityFrames - (int) (writePos - readPos);
                int n = Math.min(space, frames - done);
                int start = (int) (writePos % capacityFrames);
                int first = Math.min(n, capacityFrames - start);
                System.arraycopy(src, done * 2, buf, start * 2, first * 2);
                if (n > first) System.arraycopy(src, (done + first) * 2, buf, 0, (n - first) * 2);
                writePos += n;
                done += n;
                notifyAll();
            }
        }
        return true;
    }

    /** Lê até {@code frames} frames. Nunca bloqueia. */
    public synchronized int read(short[] dst, int frames) {
        int n = Math.min(frames, (int) (writePos - readPos));
        if (n <= 0) return 0;
        int start = (int) (readPos % capacityFrames);
        int first = Math.min(n, capacityFrames - start);
        System.arraycopy(buf, start * 2, dst, 0, first * 2);
        if (n > first) System.arraycopy(buf, 0, dst, first * 2, (n - first) * 2);
        readPos += n;
        dropConsumedSegments();
        notifyAll();
        return n;
    }

    /** Descarta até {@code frames} frames sem ler (para alcançar o PTS devido). Devolve quantos descartou. */
    public synchronized int skip(int frames) {
        int n = Math.max(0, Math.min(frames, (int) (writePos - readPos)));
        readPos += n;
        dropConsumedSegments();
        notifyAll();
        return n;
    }

    /** PTS (ms, tempo do servidor) do próximo frame a ser lido, ou NaN se não há nada guardado. */
    public synchronized double ptsAtRead() {
        if (writePos == readPos || segments.isEmpty()) return Double.NaN;
        Segment s = segments.peekFirst();
        return s.ptsMs + (readPos - s.startFrame) * MS_PER_FRAME;
    }

    /** Frames até o fim do segmento atual (até a próxima descontinuidade). */
    public synchronized int framesUntilDiscontinuity() {
        if (segments.size() < 2) return (int) (writePos - readPos);
        Segment next = segments.toArray(new Segment[0])[1];
        return (int) (next.startFrame - readPos);
    }

    private void dropConsumedSegments() {
        while (segments.size() > 1) {
            Segment first = segments.pollFirst();
            if (segments.peekFirst().startFrame > readPos) {
                segments.addFirst(first);
                break;
            }
        }
    }

    public synchronized void clear() {
        readPos = writePos;
        segments.clear();
        notifyAll();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
