package com.akashiic.fm.audio.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class StreamFormatTest {

    private static StreamFormat detect(String contentType, byte[] head) throws IOException {
        byte[] data = new byte[4096];
        System.arraycopy(head, 0, data, 0, head.length);
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(data), 16384);
        StreamFormat f = StreamFormat.detect(contentType, in);
        assertEquals(head[0], (byte) in.read(), "detect() precisa devolver os bytes lidos ao stream");
        return f;
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    void mp3PeloSyncDoFrame() throws IOException {
        assertEquals(StreamFormat.MP3, detect("audio/mpeg", bytes(0xFF, 0xFB, 0x90, 0x64)));
        assertEquals(StreamFormat.MP3, detect(null, bytes(0xFF, 0xFB, 0x90, 0x64)));
    }

    @Test
    void mp3DepoisDeTagId3() throws IOException {
        byte[] head = new byte[30];
        head[0] = 'I';
        head[1] = 'D';
        head[2] = '3';
        head[3] = 3;
        head[9] = 10; // tag de 10 bytes depois do cabeçalho de 10
        head[20] = (byte) 0xFF;
        head[21] = (byte) 0xFB;
        head[22] = (byte) 0x90;
        assertEquals(StreamFormat.MP3, detect("audio/mpeg", head));
    }

    @Test
    void aacAdtsPeloLayerZero() throws IOException {
        assertEquals(StreamFormat.AAC_ADTS, detect("audio/aac", bytes(0xFF, 0xF1, 0x50, 0x80)));
        assertEquals(StreamFormat.AAC_ADTS, detect("audio/aacp", bytes(0xFF, 0xF9, 0x50, 0x80)));
        assertEquals(StreamFormat.AAC_ADTS, detect(null, bytes(0xFF, 0xF1, 0x50, 0x80)));
    }

    @Test
    void oggPeloCodecDoPrimeiroPacote() throws IOException {
        assertEquals(
            StreamFormat.OGG_OPUS,
            detect("application/ogg", "OggS\0\2.......OpusHead".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(
            StreamFormat.OGG_VORBIS,
            detect("audio/ogg", "OggS\0\2.......\u0001vorbis".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(
            StreamFormat.UNSUPPORTED,
            detect("audio/ogg", "OggS\0\2.......\u007fFLAC".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void htmlNaoEAudio() throws IOException {
        assertEquals(
            StreamFormat.UNSUPPORTED,
            detect("text/html", "<html><body>".getBytes(StandardCharsets.ISO_8859_1)));
    }
}
