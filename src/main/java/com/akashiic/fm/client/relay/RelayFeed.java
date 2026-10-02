package com.akashiic.fm.client.relay;

import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.dsp.TimedPcmRing;
import com.akashiic.fm.audio.relay.FrameRing;
import com.akashiic.fm.audio.relay.RelayDecoder;
import com.akashiic.fm.client.audio.AudioFeed;

/**
 * Uma estação do relay neste cliente: os frames Opus chegam pela thread de rede ({@link #offer}), uma thread
 * própria decodifica ({@link RelayDecoder}) para um {@link TimedPcmRing} e a reprodução lê com o PTS de cada
 * amostra. Quem cria e encerra é o {@link RelayClient}, a mando do servidor: o {@link #close()} da interface
 * (chamado quando a reprodução deste cliente acaba) não fecha a estação, que pode voltar a ser ouvida.
 */
public final class RelayFeed implements AudioFeed, Runnable {

    private static final int RING_SECONDS = 6;
    private static final int MAX_QUEUED_FRAMES = 1024;
    private static final long STALL_NANOS = 3_000_000_000L;

    private final int stationId;
    private final String url;
    private final int latencyMs;
    private final TimedPcmRing ring = new TimedPcmRing(SAMPLE_RATE * RING_SECONDS);
    private final LinkedBlockingDeque<FrameRing.Frame> queue = new LinkedBlockingDeque<>();
    private final RelayDecoder decoder = new RelayDecoder();
    private final Thread thread;
    private volatile boolean shutdown;
    private volatile boolean gotAny;
    private volatile long lastFrameNanos;
    private volatile long droppedFrames;

    RelayFeed(int stationId, String url, int latencyMs) {
        this.stationId = stationId;
        this.url = url;
        this.latencyMs = latencyMs;
        this.thread = new Thread(this, "AkashicFM-RelayFeed-" + stationId);
        this.thread.setDaemon(true);
    }

    RelayFeed start() {
        thread.start();
        return this;
    }

    /** Thread de rede: enfileira para o decoder. Se o decoder ficou para trás, descarta os mais antigos. */
    void offer(List<FrameRing.Frame> frames) {
        if (shutdown) return;
        for (FrameRing.Frame f : frames) {
            while (queue.size() >= MAX_QUEUED_FRAMES) {
                queue.pollFirst();
                droppedFrames++;
            }
            queue.offerLast(f);
        }
        lastFrameNanos = System.nanoTime();
    }

    @Override
    public void run() {
        try {
            while (!shutdown) {
                FrameRing.Frame f = queue.pollFirst(100, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                if (!decoder.accept(f.seq, f.ptsMs, f.data, ring, () -> shutdown)) break;
                gotAny = true;
            }
        } catch (InterruptedException ignored) {
            // encerrando
        } catch (RuntimeException e) {
            AkashicFM.LOG.warn("Relay: decoder da estação {} parou", stationId, e);
        } finally {
            ring.close();
        }
    }

    /** Encerramento de verdade (o servidor parou de mandar esta estação para nós, ou desconectamos). */
    void shutdown() {
        shutdown = true;
        queue.clear();
        ring.close();
        thread.interrupt();
    }

    // ---- AudioFeed ----

    @Override
    public int available() {
        return ring.available();
    }

    @Override
    public int read(short[] dst, int frames) {
        return ring.read(dst, frames);
    }

    @Override
    public boolean isFinished() {
        return ring.isFinished();
    }

    @Override
    public boolean producerDone() {
        return ring.isClosed();
    }

    @Override
    public Status status() {
        if (shutdown) return Status.ENDED;
        if (!gotAny) return Status.CONNECTING;
        return System.nanoTime() - lastFrameNanos > STALL_NANOS ? Status.RECONNECTING : Status.PLAYING;
    }

    @Override
    public String statusDetail() {
        return "";
    }

    /** A reprodução acabou (ex.: o jogador saiu do alcance no cliente): a estação continua viva. */
    @Override
    public void close() {}

    @Override
    public boolean timed() {
        return true;
    }

    @Override
    public double ptsAtReadPosition() {
        return ring.ptsAtRead();
    }

    @Override
    public int framesUntilDiscontinuity() {
        return ring.framesUntilDiscontinuity();
    }

    @Override
    public int skip(int frames) {
        return ring.skip(frames);
    }

    @Override
    public int latencyMs() {
        return latencyMs;
    }

    public int stationId() {
        return stationId;
    }

    public String url() {
        return url;
    }

    public long droppedFrames() {
        return droppedFrames;
    }

    public long filledMs() {
        return decoder.filledMs();
    }
}
