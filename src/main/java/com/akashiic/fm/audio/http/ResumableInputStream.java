package com.akashiic.fm.audio.http;

import java.io.IOException;
import java.io.InputStream;

/**
 * Corpo de um arquivo HTTP que sobrevive a quedas: conta os bytes já entregues e, se a conexão cai antes do fim
 * (rede, ou o servidor fechando um socket parado durante a pausa do iPod), reabre com {@code Range} exatamente a
 * partir dali. Quem lê vê um fluxo contínuo, byte a byte igual ao arquivo, então o decodificador não percebe a emenda
 * (vale para qualquer formato). Só para arquivos com tamanho conhecido e {@code Accept-Ranges: bytes}.
 * <p>
 * Uma thread só (a do {@code StreamPump}); {@link #close()} pode vir de outra.
 */
public final class ResumableInputStream extends InputStream {

    /** Reabre o arquivo a partir do byte {@code offset} (resposta 206 com esse começo). */
    public interface Opener {

        InputStream open(long offset) throws IOException;
    }

    /** Tentativas seguidas de reabrir sem progresso; depois de {@link #RESET_AFTER_BYTES} lidos, a conta zera. */
    static final int MAX_RESUMES = 4;
    /**
     * Progresso que zera a conta de tentativas: uma faixa longa pausada várias vezes (cada pausa longa derruba a
     * conexão) não se esgota, mas um servidor que cai a cada poucos bytes sim.
     */
    static final long RESET_AFTER_BYTES = 256 * 1024;
    private static final long[] BACKOFF_MS = { 500, 1000, 2000, 4000 };

    private final Opener opener;
    private final long[] backoffMs;
    private final long length;
    private InputStream in;
    private long position;
    private long resumedAt;
    private int attempts;
    private int resumes;
    private volatile boolean closed;

    /**
     * @param first  o corpo da primeira resposta (byte 0 em diante)
     * @param length tamanho total do arquivo ({@code Content-Length} da primeira resposta)
     */
    public ResumableInputStream(InputStream first, long length, Opener opener) {
        this(first, length, opener, BACKOFF_MS);
    }

    /** Testes: sem esperar o backoff de verdade. */
    ResumableInputStream(InputStream first, long length, Opener opener, long[] backoffMs) {
        this.in = first;
        this.length = length;
        this.opener = opener;
        this.backoffMs = backoffMs;
    }

    /** Bytes já entregues a quem lê. */
    public long position() {
        return position;
    }

    /** Vezes que reabriu com sucesso (diagnóstico). */
    public int resumes() {
        return resumes;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (true) {
            if (closed) throw new IOException("fechado");
            if (position >= length) return -1;
            int want = (int) Math.min(len, length - position);
            IOException failure;
            try {
                int n = in.read(b, off, want);
                if (n > 0) {
                    position += n;
                    if (attempts > 0 && position - resumedAt >= RESET_AFTER_BYTES) attempts = 0;
                    return n;
                }
                // EOF antes do tamanho anunciado: a conexão acabou no meio.
                failure = new IOException("conexão terminou em " + position + " de " + length + " bytes");
            } catch (IOException e) {
                failure = e;
            }
            resume(failure);
        }
    }

    /** Reabre em {@link #position}, tentando de novo (com espera) se a reabertura também falhar. */
    private void resume(IOException cause) throws IOException {
        closeQuietly(in);
        IOException last = cause;
        while (true) {
            if (closed || attempts >= MAX_RESUMES) throw last;
            try {
                Thread.sleep(backoffMs[Math.min(attempts, backoffMs.length - 1)]);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                throw last;
            }
            attempts++;
            if (closed) throw last;
            try {
                in = opener.open(position);
                resumes++;
                resumedAt = position;
                return;
            } catch (IOException e) {
                last = e;
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        closeQuietly(in);
    }

    private static void closeQuietly(InputStream s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {}
    }
}
