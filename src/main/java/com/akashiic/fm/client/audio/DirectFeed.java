package com.akashiic.fm.client.audio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.decode.PcmSource;
import com.akashiic.fm.audio.decode.StreamFormat;
import com.akashiic.fm.audio.dsp.PcmRing;
import com.akashiic.fm.audio.dsp.Resampler48k;
import com.akashiic.fm.audio.http.IcyHttpClient;
import com.akashiic.fm.audio.http.UrlPolicy;

/**
 * Modo direto: este cliente baixa e decodifica o stream sozinho numa thread própria e entrega PCM 48 kHz
 * estéreo num {@link PcmRing}. Reconecta stream ao vivo com backoff; arquivo com tamanho conhecido termina.
 * {@link #close()} derruba o socket, então a thread sai mesmo presa num read().
 */
public final class DirectFeed implements AudioFeed, Runnable {

    private static final int RING_SECONDS = 8;
    private static final int[] BACKOFF_MS = { 1000, 2000, 4000, 8000, 15000 };
    /** Política do cliente: sem allowlist (o servidor já aplicou a dele), mas nunca endereço interno. */
    private static final UrlPolicy CLIENT_POLICY = new UrlPolicy(new String[0], true);

    private final String url;
    private final PcmRing ring = new PcmRing(SAMPLE_RATE * RING_SECONDS);
    private final AtomicReference<IcyHttpClient.Response> current = new AtomicReference<>();
    private final Thread thread;
    private volatile boolean closed;
    private volatile Status status = Status.CONNECTING;
    private volatile String detail = "";

    public DirectFeed(String url, String threadName) {
        this.url = url;
        this.thread = new Thread(this, threadName);
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    public DirectFeed start() {
        thread.start();
        return this;
    }

    @Override
    public void run() {
        int failures = 0;
        try {
            while (!closed) {
                status = failures == 0 ? Status.CONNECTING : Status.RECONNECTING;
                boolean live;
                try {
                    live = streamOnce();
                    if (closed) break;
                    if (!live) { // arquivo terminou normalmente
                        status = Status.ENDED;
                        break;
                    }
                    failures = 0; // ao vivo caiu depois de tocar: reconecta do começo do backoff
                } catch (UrlPolicy.PolicyException e) {
                    fail(e.getMessage());
                    break;
                } catch (UnsupportedFormatException e) {
                    fail("formato não suportado");
                    break;
                } catch (InterruptedException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    if (closed) break;
                    detail = String.valueOf(e.getMessage());
                }
                if (failures >= BACKOFF_MS.length) {
                    fail(detail.isEmpty() ? "sem conexão" : detail);
                    break;
                }
                status = Status.RECONNECTING;
                try {
                    Thread.sleep(BACKOFF_MS[failures++]);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            closeResponse();
            ring.close();
        }
    }

    /**
     * Uma conexão completa. Devolve true se era stream ao vivo (EOF = queda, deve reconectar) e false se era
     * arquivo que terminou.
     */
    private boolean streamOnce()
        throws IOException, UrlPolicy.PolicyException, InterruptedException, UnsupportedFormatException {
        IcyHttpClient.Response r = IcyHttpClient.fromSystemProperties(CLIENT_POLICY)
            .open(url);
        current.set(r);
        if (closed) {
            closeResponse();
            return false;
        }
        boolean live = r.header("content-length") == null || r.header("icy-name") != null
            || r.header("icy-metaint") != null;
        BufferedInputStream body = new BufferedInputStream(r.body, 64 * 1024);
        StreamFormat fmt = StreamFormat.detect(r.header("content-type"), body);
        if (fmt == StreamFormat.UNSUPPORTED) throw new UnsupportedFormatException();
        PcmSource pcm = StreamFormat.open(fmt, body);
        try {
            int inRate = pcm.sampleRate();
            int inCh = pcm.channels();
            Resampler48k rs = new Resampler48k(inRate, inCh);
            short[] in = new short[8192];
            short[] out = new short[rs.maxOut(in.length)];
            status = Status.PLAYING;
            detail = "";
            while (!closed) {
                int n = pcm.read(in, 0, in.length);
                if (n < 0) return live;
                if (pcm.sampleRate() != inRate || pcm.channels() != inCh) { // stream encadeado mudou de formato
                    inRate = pcm.sampleRate();
                    inCh = pcm.channels();
                    rs = new Resampler48k(inRate, inCh);
                    out = new short[rs.maxOut(in.length)];
                }
                int m = rs.process(in, n, out);
                if (m > 0 && !ring.write(out, m / 2, () -> closed)) return live;
            }
            return live;
        } finally {
            try {
                pcm.close();
            } catch (IOException ignored) {}
            closeResponse();
        }
    }

    private void fail(String message) {
        detail = message == null ? "" : message;
        status = Status.ERROR;
        AkashicFM.LOG.warn("Rádio (modo direto) parou: {} [{}]", detail, url);
    }

    private void closeResponse() {
        IcyHttpClient.Response r = current.getAndSet(null);
        if (r != null) {
            try {
                r.close();
            } catch (IOException ignored) {}
        }
    }

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
        return status;
    }

    @Override
    public String statusDetail() {
        return detail;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeResponse();
        ring.close();
        thread.interrupt();
    }

    private static final class UnsupportedFormatException extends Exception {

        UnsupportedFormatException() {
            super(null, null, false, false);
        }
    }
}
