package com.akashiic.fm.audio.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class IcyStreamsTest {

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[7]; // buffer pequeno de propósito: força leituras que cruzam os blocos de metadata
        int n;
        while ((n = in.read(buf, 0, buf.length)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void writeMeta(ByteArrayOutputStream out, String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        int blocks = (t.length + 15) / 16;
        out.write(blocks);
        out.write(t, 0, t.length);
        for (int i = t.length; i < blocks * 16; i++) out.write(0);
    }

    @Test
    void removeMetadataEGuardaOTitulo() throws IOException {
        int metaint = 16;
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        for (int block = 0; block < 4; block++) {
            for (int i = 0; i < metaint; i++) {
                int b = (block * metaint + i) & 0xFF;
                wire.write(b);
                audio.write(b);
            }
            if (block == 1) writeMeta(wire, "StreamTitle='Artista - It's Música';StreamUrl='';");
            else if (block == 2) writeMeta(wire, "StreamTitle='Outra Faixa';");
            else wire.write(0); // bloco vazio
        }
        IcyMetadataInputStream icy = new IcyMetadataInputStream(new ByteArrayInputStream(wire.toByteArray()), metaint);
        assertArrayEquals(audio.toByteArray(), readAll(icy));
        assertEquals("Outra Faixa", icy.streamTitle());
        assertEquals(2, icy.titleChanges());
    }

    @Test
    void parserDoStreamTitle() {
        assertEquals("A - B", IcyMetadataInputStream.parseStreamTitle("StreamTitle='A - B';StreamUrl='x';"));
        assertEquals("It's", IcyMetadataInputStream.parseStreamTitle("StreamTitle='It's';"));
        assertNull(IcyMetadataInputStream.parseStreamTitle("StreamUrl='x';"));
    }

    @Test
    void chunkedJuntaOsPedacos() throws IOException {
        String wire = "4\r\nWiki\r\n5;ext=1\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\n\r\n";
        InputStream in = new ChunkedInputStream(new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("Wikipedia in\r\n\r\nchunks.", new String(readAll(in), StandardCharsets.ISO_8859_1));
    }

    @Test
    void chunkedTruncadoDaErro() {
        InputStream in = new ChunkedInputStream(
            new ByteArrayInputStream("A\r\nabc".getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, () -> readAll(in));
    }
}
