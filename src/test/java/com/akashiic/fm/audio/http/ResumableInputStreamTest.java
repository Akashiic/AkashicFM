package com.akashiic.fm.audio.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ResumableInputStreamTest {

    private static final long[] NO_WAIT = { 0 };

    private static byte[] file(int size) {
        byte[] b = new byte[size];
        new Random(42).nextBytes(b);
        return b;
    }

    /** Entrega {@code data} a partir de {@code from} e cai (exceção ou EOF) depois de {@code cutAfter} bytes. */
    private static InputStream flaky(byte[] data, long from, int cutAfter, boolean eof) {
        return new InputStream() {

            int served;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) <= 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (served >= cutAfter) {
                    if (eof) return -1;
                    throw new IOException("Connection reset");
                }
                int left = (int) Math.min(data.length - from - served, Math.min(len, cutAfter - served));
                if (left <= 0) return -1;
                System.arraycopy(data, (int) (from + served), b, off, left);
                served += left;
                return left;
            }
        };
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[777];
        int n;
        while ((n = in.read(buf, 0, buf.length)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Test
    void semQuedaLeOArquivoInteiro() throws IOException {
        byte[] data = file(10_000);
        ResumableInputStream in = new ResumableInputStream(
            new ByteArrayInputStream(data),
            data.length,
            off -> { throw new AssertionError("não devia reabrir"); },
            NO_WAIT);
        assertArrayEquals(data, readAll(in));
        assertEquals(0, in.resumes());
    }

    @Test
    void retomaDoByteExatoDepoisDeQuedasEFimPrematuro() throws IOException {
        byte[] data = file(50_000);
        List<Long> offsets = new ArrayList<>();
        // Cai com exceção em 12.345, termina cedo (EOF) em 30.000 e depois vai até o fim.
        ResumableInputStream in = new ResumableInputStream(flaky(data, 0, 12_345, false), data.length, off -> {
            offsets.add(off);
            return off < 30_000 ? flaky(data, off, (int) (30_000 - off), true)
                : flaky(data, off, Integer.MAX_VALUE, true);
        }, NO_WAIT);
        assertArrayEquals(data, readAll(in), "o fluxo emendado é igual ao arquivo");
        assertEquals(2, in.resumes());
        assertEquals(12_345L, offsets.get(0));
        assertEquals(30_000L, offsets.get(1));
    }

    @Test
    void desisteDepoisDoLimiteDeRetomadas() {
        byte[] data = file(20_000);
        ResumableInputStream in = new ResumableInputStream(
            flaky(data, 0, 100, false),
            data.length,
            off -> flaky(data, off, 100, false),
            NO_WAIT);
        assertThrows(IOException.class, () -> readAll(in));
        assertEquals(ResumableInputStream.MAX_RESUMES, in.resumes());
    }

    @Test
    void fechadoNaoReabre() throws IOException {
        byte[] data = file(1000);
        ResumableInputStream in = new ResumableInputStream(
            flaky(data, 0, 10, false),
            data.length,
            off -> { throw new AssertionError("fechado não reabre"); },
            NO_WAIT);
        in.close();
        assertThrows(IOException.class, () -> in.read(new byte[10], 0, 10));
    }

    @Test
    void reaberturaQueFalhaTentaDeNovo() throws IOException {
        byte[] data = file(10_000);
        int[] calls = { 0 };
        ResumableInputStream in = new ResumableInputStream(flaky(data, 0, 4000, false), data.length, off -> {
            if (calls[0]++ < 2) throw new IOException("rede fora");
            return flaky(data, off, Integer.MAX_VALUE, true);
        }, NO_WAIT);
        assertArrayEquals(data, readAll(in));
        assertEquals(3, calls[0]);
        assertEquals(1, in.resumes());
    }

    @Test
    void progressoZeraAContaDeTentativas() throws IOException {
        // Uma faixa longa pausada muitas vezes: cai a cada 300 KB, bem mais que MAX_RESUMES vezes no total.
        byte[] data = file(3_000_000);
        int every = 300_000;
        ResumableInputStream in = new ResumableInputStream(
            flaky(data, 0, every, false),
            data.length,
            off -> flaky(data, off, every, false),
            NO_WAIT);
        assertArrayEquals(data, readAll(in));
        assertEquals(9, in.resumes());
    }

    @Test
    void reaberturaSempreFalhandoDesisteNoLimite() {
        byte[] data = file(10_000);
        int[] calls = { 0 };
        ResumableInputStream in = new ResumableInputStream(flaky(data, 0, 10, false), data.length, off -> {
            calls[0]++;
            throw new IOException("rede fora");
        }, NO_WAIT);
        IOException e = assertThrows(IOException.class, () -> readAll(in));
        assertEquals("rede fora", e.getMessage());
        assertEquals(ResumableInputStream.MAX_RESUMES, calls[0]);
    }
}
