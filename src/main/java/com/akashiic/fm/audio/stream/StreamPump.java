package com.akashiic.fm.audio.stream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.decode.PcmSource;
import com.akashiic.fm.audio.decode.StreamFormat;
import com.akashiic.fm.audio.dsp.Resampler48k;
import com.akashiic.fm.audio.http.IcyHttpClient;
import com.akashiic.fm.audio.http.ResumableInputStream;
import com.akashiic.fm.audio.http.UrlPolicy;

/**
 * Baixa e decodifica um stream de rádio numa thread própria e entrega PCM 48 kHz estéreo a um {@link Sink}.
 * Usado pelo modo direto (cliente) e pelas estações do relay (servidor), com a política de URL de cada lado.
 * <ul>
 * <li>stream ao vivo que cai: reconecta com backoff de 1/2/4/8/15 s;</li>
 * <li>o backoff só zera depois de {@link #HEALTHY_FRAMES} de áudio: um stream que cai a cada segundo não vira
 * uma conexão por segundo para sempre;</li>
 * <li>arquivo (com tamanho e sem cabeçalhos ICY) que termina: fim; que cai no meio: se o servidor aceita
 * {@code Range}, retoma do byte exato ({@link ResumableInputStream}, sem emenda audível); senão, erro, sem
 * recomeçar do início;</li>
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
    private final MediaLocator locator;
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
        this(url, MediaLocator.fixed(url), policy, sink, threadName);
    }

    /**
     * @param name    nome para logs e para {@link #url()} (a URL da rádio, ou a chave da estação do iPod)
     * @param locator de onde baixar de fato
     */
    public StreamPump(String name, MediaLocator locator, UrlPolicy policy, Sink sink, String threadName) {
        this.url = name;
        this.locator = locator;
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
        IcyHttpClient client = IcyHttpClient.fromSystemProperties(policy);
        String target = locator.url();
        IcyHttpClient.Response r;
        try {
            r = client.open(target);
        } catch (IcyHttpClient.HttpStatusException e) {
            // URL assinada que expirou antes de tocar (iPod): renova uma vez. Uma URL fixa (rádio) não muda e não
            // repete o pedido aqui.
            if (e.code != 403 && e.code != 404 && e.code != 410) throw e;
            String renewed = locator.refresh();
            if (renewed == null || renewed.equals(target)) throw e;
            target = renewed;
            r = client.open(target);
        }
        current.set(r);
        if (closed) {
            closeResponse();
            return false;
        }
        boolean live = r.header("content-length") == null || r.header("icy-name") != null
            || r.header("icy-metaint") != null;
        currentLive = live;
        InputStream raw = r.body;
        long length = live ? -1 : parseLength(r.header("content-length"));
        if (length > 0 && "bytes".equalsIgnoreCase(trim(r.header("accept-ranges")))) {
            raw = new ResumableInputStream(r.body, length, new Reopener(client, target));
        }
        BufferedInputStream body = new BufferedInputStream(raw, 64 * 1024);
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

    /** Reabre o arquivo a partir de um byte; renova a URL se o servidor a recusou (URL assinada que expirou). */
    private final class Reopener implements ResumableInputStream.Opener {

        private final IcyHttpClient client;
        private String target;

        Reopener(IcyHttpClient client, String target) {
            this.client = client;
            this.target = target;
        }

        @Override
        public InputStream open(long offset) throws IOException {
            if (closed) throw new IOException("fechado"); // close() derrubou o socket: não reabre
            IcyHttpClient.Response r;
            try {
                r = openAt(offset);
            } catch (IcyHttpClient.HttpStatusException e) {
                if (e.code != 403 && e.code != 404 && e.code != 410) throw e;
                String renewed = locator.refresh();
                if (renewed == null || renewed.equals(target)) throw e; // URL fixa: repetir não muda nada
                target = renewed;
                r = openAt(offset);
            }
            current.set(r);
            if (closed) {
                closeResponse();
                throw new IOException("fechado");
            }
            AkashicFM.LOG.debug("Retomando {} a partir do byte {}", url, offset);
            return r.body;
        }

        private IcyHttpClient.Response openAt(long offset) throws IOException {
            try {
                return client.open(target, offset);
            } catch (UrlPolicy.PolicyException e) {
                throw new IOException(e.getMessage());
            }
        }
    }

    private static long parseLength(String v) {
        try {
            return v == null ? -1 : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
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
