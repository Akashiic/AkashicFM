package com.akashiic.fm.audio.stream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.decode.PcmSource;
import com.akashiic.fm.audio.decode.StreamFormat;
import com.akashiic.fm.audio.dsp.Resampler48k;
import com.akashiic.fm.audio.http.IcyHttpClient;
import com.akashiic.fm.audio.http.UrlPolicy;

/**
 * Baixa e decodifica um stream de rádio numa thread própria e entrega PCM 48 kHz estéreo a um {@link Sink}.
 * Usado pelo modo direto (cliente) e pelas estações do relay (servidor), com a política de URL de cada lado.
 * <ul>
 * <li>stream ao vivo que cai: reconecta com backoff de 1/2/4/8/15 s;</li>
 * <li>o backoff só zera depois de {@link #HEALTHY_FRAMES} de áudio: um stream que cai a cada segundo não vira
 * uma conexão por segundo para sempre;</li>
 * <li>arquivo (com tamanho e sem cabeçalhos ICY) que termina: fim; que falha no meio: erro, sem recomeçar do
 * início;</li>
 * <li>{@link #close()} derruba o socket, então a thread sai mesmo presa num read().</li>
 * </ul>
 */
public final class StreamPump implements Runnable {

    /** Destino do PCM. Pode bloquear (contrapressão); devolve false para parar. */
    public interface Sink {

        boolean write(short[] stereo48k, int frames) throws InterruptedException;

        /** Não vai chegar mais nada (fim, erro definitivo ou fechamento). */
        void end();
    }

    public enum Status {
        CONNECTING,
        RECONNECTING,
        PLAYING,
        ENDED,
        ERROR
    }

    public static final int SAMPLE_RATE = 48000;
    private static final int[] BACKOFF_MS = { 1000, 2000, 4000, 8000, 15000 };
    static final long HEALTHY_FRAMES = SAMPLE_RATE * 5L;

    private final String url;
    private final UrlPolicy policy;
    private final Sink sink;
    private final Thread thread;
    private final AtomicReference<IcyHttpClient.Response> current = new AtomicReference<>();
    private volatile boolean closed;
    private volatile Status status = Status.CONNECTING;
    private volatile String detail = "";
    private volatile String streamTitle = "";
    private long producedFrames; // só a thread do pump
    private boolean currentLive;

    public StreamPump(String url, UrlPolicy policy, Sink sink, String threadName) {
        this.url = url;
        this.policy = policy;
        this.sink = sink;
        this.thread = new Thread(this, threadName);
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    public StreamPump start() {
        thread.start();
        return this;
    }

    public Status status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    /** Último StreamTitle (ICY) do stream, ou vazio. */
    public String streamTitle() {
        return streamTitle;
    }

    public String url() {
        return url;
    }

    @Override
    public void run() {
        int failures = 0;
        try {
            while (!closed) {
                status = failures == 0 ? Status.CONNECTING : Status.RECONNECTING;
                producedFrames = 0;
                currentLive = true;
                try {
                    boolean live = streamOnce();
                    if (closed) break;
                    if (!live) { // arquivo terminou normalmente
                        status = Status.ENDED;
                        break;
                    }
                    if (producedFrames == 0) detail = "stream sem áudio";
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
                    // Arquivo que falhou no meio não recomeça do início (repetiria até o mesmo ponto para sempre).
                    if (producedFrames > 0 && !currentLive) {
                        fail(detail);
                        break;
                    }
                }
                if (producedFrames >= HEALTHY_FRAMES) failures = 0;
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
            sink.end();
        }
    }

    /**
     * Uma conexão completa. Devolve true se era stream ao vivo (EOF = queda, deve reconectar) e false se era
     * arquivo que terminou.
     */
    private boolean streamOnce()
        throws IOException, UrlPolicy.PolicyException, InterruptedException, UnsupportedFormatException {
        IcyHttpClient.Response r = IcyHttpClient.fromSystemProperties(policy)
            .open(url);
        current.set(r);
        if (closed) {
            closeResponse();
            return false;
        }
        boolean live = r.header("content-length") == null || r.header("icy-name") != null
            || r.header("icy-metaint") != null;
        currentLive = live;
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
                if (r.metadata != null) {
                    String t = r.metadata.streamTitle();
                    if (t != null && !t.equals(streamTitle)) streamTitle = t;
                }
                int m = rs.process(in, n, out);
                if (m > 0) {
                    if (!sink.write(out, m / 2)) return live;
                    producedFrames += m / 2;
                }
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
        AkashicFM.LOG.warn("Stream parou: {} [{}]", detail, url);
    }

    private void closeResponse() {
        IcyHttpClient.Response r = current.getAndSet(null);
        if (r != null) {
            try {
                r.close();
            } catch (IOException ignored) {}
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Para tudo. Idempotente e seguro de qualquer thread. */
    public void close() {
        if (closed) return;
        closed = true;
        closeResponse();
        thread.interrupt();
    }

    private static final class UnsupportedFormatException extends Exception {

        UnsupportedFormatException() {
            super(null, null, false, false);
        }
    }
}
