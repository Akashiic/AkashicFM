package com.akashiic.fm.audio.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Remove os blocos de metadata ICY intercalados no áudio e guarda o último StreamTitle.
 * Formato: a cada {@code metaint} bytes de áudio vem 1 byte N, seguido de N*16 bytes
 * de texto "StreamTitle='Artista - Música';StreamUrl='...';" preenchido com zeros.
 */
public final class IcyMetadataInputStream extends FilterInputStream {

    private final int metaint;
    private int untilMeta;
    private volatile String streamTitle;
    private volatile long titleChanges;

    public IcyMetadataInputStream(InputStream in, int metaint) {
        super(in);
        if (metaint <= 0) throw new IllegalArgumentException("icy-metaint inválido: " + metaint);
        this.metaint = metaint;
        this.untilMeta = metaint;
    }

    public String streamTitle() {
        return streamTitle;
    }

    public long titleChanges() {
        return titleChanges;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (untilMeta == 0) {
            readMetadataBlock();
            untilMeta = metaint;
        }
        int n = in.read(b, off, Math.min(len, untilMeta));
        if (n > 0) untilMeta -= n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        byte[] tmp = new byte[(int) Math.min(n, 4096)];
        int r = read(tmp, 0, tmp.length);
        return Math.max(r, 0);
    }

    @Override
    public int available() throws IOException {
        return Math.min(in.available(), untilMeta);
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void readMetadataBlock() throws IOException {
        int lenByte = in.read();
        if (lenByte == -1) return;
        int len = lenByte * 16;
        if (len == 0) return;
        byte[] meta = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(meta, read, len - read);
            if (n == -1) throw new IOException("stream terminou no meio da metadata ICY");
            read += n;
        }
        String title = parseStreamTitle(new String(meta, StandardCharsets.UTF_8));
        if (title != null && !title.equals(streamTitle)) {
            streamTitle = title;
            titleChanges++;
        }
    }

    static String parseStreamTitle(String meta) {
        int start = meta.indexOf("StreamTitle='");
        if (start < 0) return null;
        start += "StreamTitle='".length();
        int end = meta.indexOf("';", start);
        if (end < 0) end = meta.lastIndexOf('\'');
        if (end < start) return null;
        String t = meta.substring(start, end)
            .trim();
        return t.length() > 256 ? t.substring(0, 256) : t;
    }
}
