package com.akashiic.fm.audio.http;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Decodifica Transfer-Encoding: chunked (alguns CDNs de rádio usam em HTTP/1.1). */
public final class ChunkedInputStream extends FilterInputStream {

    private int remaining;
    private boolean eof;

    public ChunkedInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (eof) return -1;
        if (remaining == 0) {
            remaining = nextChunkSize();
            if (remaining == 0) {
                eof = true;
                return -1;
            }
        }
        int n = in.read(b, off, Math.min(len, remaining));
        if (n == -1) throw new IOException("stream terminou no meio de um chunk");
        remaining -= n;
        if (remaining == 0) readLine(); // CRLF depois dos dados do chunk
        return n;
    }

    @Override
    public int available() throws IOException {
        return eof ? 0 : Math.min(in.available(), remaining);
    }

    private int nextChunkSize() throws IOException {
        String line = readLine();
        int semi = line.indexOf(';');
        if (semi >= 0) line = line.substring(0, semi);
        int size = Integer.parseInt(line.trim(), 16);
        if (size < 0) throw new IOException("tamanho de chunk inválido");
        return size;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(16);
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') buf.write(c);
            if (buf.size() > 1024) throw new IOException("linha de chunk grande demais");
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
