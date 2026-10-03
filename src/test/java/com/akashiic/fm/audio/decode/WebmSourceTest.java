package com.akashiic.fm.audio.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;

class WebmSourceTest {

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WebmSourceTest.class.getResourceAsStream("/audio/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Todas as amostras (intercaladas). */
    private static short[] decodeAll(PcmSource src) throws IOException {
        List<short[]> parts = new ArrayList<>();
        int total = 0;
        short[] buf = new short[4096];
        int n;
        while ((n = src.read(buf, 0, buf.length)) != -1) {
            short[] p = new short[n];
            System.arraycopy(buf, 0, p, 0, n);
            parts.add(p);
            total += n;
        }
        short[] all = new short[total];
        int at = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        return all;
    }

    /** Frequência dominante do canal {@code ch}, pelos cruzamentos de zero no trecho do meio. */
    private static double frequency(short[] pcm, int channels, int ch) {
        int frames = pcm.length / channels;
        int from = frames / 4, to = frames * 3 / 4, crossings = 0;
        for (int i = from + 1; i < to; i++) {
            if ((pcm[(i - 1) * channels + ch] < 0) != (pcm[i * channels + ch] < 0)) crossings++;
        }
        return crossings / 2.0 / ((to - from) / 48000.0);
    }

    @Test
    void decodificaOWebmDoFfmpegEstereo() throws IOException {
        WebmSource src = new WebmSource(new ByteArrayInputStream(resource("sine1k-stereo.webm")));
        assertEquals(2, src.channels());
        assertEquals(48000, src.sampleRate());
        short[] pcm = decodeAll(src);
        int frames = pcm.length / 2;
        assertTrue(Math.abs(frames - 48000) <= 960, "~1 s de áudio, veio " + frames + " amostras");
        double f = frequency(pcm, 2, 0);
        assertTrue(Math.abs(f - 1000) < 20, "1 kHz, veio " + f);
    }

    @Test
    void decodificaOWebmDoFfmpegMono() throws IOException {
        WebmSource src = new WebmSource(new ByteArrayInputStream(resource("sine440-mono.webm")));
        assertEquals(1, src.channels());
        short[] pcm = decodeAll(src);
        double f = frequency(pcm, 1, 0);
        assertTrue(Math.abs(f - 440) < 10, "440 Hz, veio " + f);
    }

    @Test
    void detectaWebmOpusERecusaOutroCodec() throws IOException {
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(resource("sine1k-stereo.webm")));
        assertEquals(StreamFormat.WEBM_OPUS, StreamFormat.detect("audio/webm", in));
        assertEquals(0x1A, in.read(), "detect() devolve os bytes ao stream");
        byte[] vorbis = new Webm().header()
            .tracks(1, "A_VORBIS", new byte[30])
            .bytes();
        assertEquals(
            StreamFormat.UNSUPPORTED,
            StreamFormat.detect("audio/webm", new BufferedInputStream(new ByteArrayInputStream(vorbis))));
    }

    // ---- WebM montado no teste: tamanhos desconhecidos, elementos estranhos e as três lacings ----

    private static List<byte[]> opusFrames(int count) throws Exception {
        OpusEncoder enc = new OpusEncoder(48000, 2, OpusApplication.OPUS_APPLICATION_AUDIO);
        enc.setBitrate(64000);
        short[] pcm = new short[960 * 2];
        List<byte[]> out = new ArrayList<>();
        int t = 0;
        for (int f = 0; f < count; f++) {
            for (int i = 0; i < 960; i++, t++) {
                short v = (short) (8000 * Math.sin(2 * Math.PI * 600 * t / 48000.0));
                pcm[i * 2] = v;
                pcm[i * 2 + 1] = v;
            }
            byte[] pkt = new byte[1275];
            int n = enc.encode(pcm, 0, 960, pkt, 0, pkt.length);
            byte[] exact = new byte[n];
            System.arraycopy(pkt, 0, exact, 0, n);
            out.add(exact);
        }
        return out;
    }

    private static byte[] opusHead(int channels, int preSkip) {
        byte[] h = new byte[19];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.ISO_8859_1), 0, h, 0, 8);
        h[8] = 1;
        h[9] = (byte) channels;
        h[10] = (byte) preSkip;
        h[11] = (byte) (preSkip >> 8);
        return h;
    }

    @Test
    void lacingsXiphFixaEEbmlComTamanhosDesconhecidos() throws Exception {
        List<byte[]> f = opusFrames(12);
        Webm w = new Webm().header()
            .unknownSegment()
            .voidElement(37)
            .tracks(1, "A_OPUS", opusHead(2, 312))
            .unknownCluster();
        w.simpleBlock(1, 0, f.get(0)); // sem lacing
        w.xiph(1, f.subList(1, 4));
        w.ebml(1, f.subList(4, 8));
        byte[] same = f.get(8);
        w.fixed(1, java.util.Arrays.asList(same, same, same)); // fixa: quadros do mesmo tamanho
        w.simpleBlock(2, 0, new byte[] { 1, 2, 3 }); // outra trilha: ignorada
        w.simpleBlock(2, 0, new byte[WebmSource.MAX_ELEMENT + 16]); // quadro de vídeo maior que o limite: pulado
        w.simpleBlock(1, 0, f.get(11));
        WebmSource src = new WebmSource(new ByteArrayInputStream(w.bytes()));
        short[] pcm = decodeAll(src);
        int frames = pcm.length / 2;
        assertEquals(12 * 960 - 312, frames, "todos os quadros, menos o pre-skip");
        double freq = frequency(pcm, 2, 0);
        assertTrue(Math.abs(freq - 600) < 30, "600 Hz, veio " + freq);
    }

    @Test
    void semTrilhaOpusFalhaNaAbertura() {
        byte[] noOpus = new Webm().header()
            .tracks(1, "A_VORBIS", new byte[30])
            .bytes();
        assertThrows(IOException.class, () -> new WebmSource(new ByteArrayInputStream(noOpus)));
        assertThrows(IOException.class, () -> new WebmSource(new ByteArrayInputStream("OggS....".getBytes())));
    }

    @Test
    void signedVintDaLacingEbml() throws IOException {
        // 1 byte: valor bruto 63 = 0, 62 = -1, 64 = +1 (alcance -63..63).
        assertEquals(0, WebmSource.signedVint(new byte[] { (byte) (0x80 | 63) }, new int[] { 0 }));
        assertEquals(-1, WebmSource.signedVint(new byte[] { (byte) (0x80 | 62) }, new int[] { 0 }));
        assertEquals(1, WebmSource.signedVint(new byte[] { (byte) (0x80 | 64) }, new int[] { 0 }));
    }

    /** Escritor EBML mínimo para os testes. */
    private static final class Webm {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private static byte[] id(int id) {
            if (id > 0xFFFFFF) return new byte[] { (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id };
            if (id > 0xFFFF) return new byte[] { (byte) (id >> 16), (byte) (id >> 8), (byte) id };
            if (id > 0xFF) return new byte[] { (byte) (id >> 8), (byte) id };
            return new byte[] { (byte) id };
        }

        private static byte[] size(long v) {
            // 4 bytes sempre (0x10 + 28 bits): simples e válido.
            return new byte[] { (byte) (0x10 | (v >> 24)), (byte) (v >> 16), (byte) (v >> 8), (byte) v };
        }

        private static byte[] element(int id, byte[] data) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(id(id), 0, id(id).length);
            byte[] s = size(data.length);
            b.write(s, 0, s.length);
            b.write(data, 0, data.length);
            return b.toByteArray();
        }

        private static byte[] concat(byte[]... parts) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (byte[] p : parts) b.write(p, 0, p.length);
            return b.toByteArray();
        }

        private void raw(byte[] b) {
            out.write(b, 0, b.length);
        }

        Webm header() {
            raw(element(WebmSource.EBML, element(WebmSource.DOC_TYPE, "webm".getBytes(StandardCharsets.US_ASCII))));
            return this;
        }

        Webm unknownSegment() {
            raw(concat(id(WebmSource.SEGMENT), new byte[] { 0x01, -1, -1, -1, -1, -1, -1, -1 }));
            return this;
        }

        Webm unknownCluster() {
            raw(concat(id(WebmSource.CLUSTER), new byte[] { (byte) 0xFF }, element(0xE7, new byte[] { 0 })));
            return this;
        }

        Webm voidElement(int n) {
            raw(element(0xEC, new byte[n]));
            return this;
        }

        Webm tracks(int number, String codec, byte[] priv) {
            byte[] entry = concat(
                element(WebmSource.TRACK_NUMBER, new byte[] { (byte) number }),
                element(0x83, new byte[] { 2 }),
                element(WebmSource.CODEC_ID, codec.getBytes(StandardCharsets.US_ASCII)),
                element(WebmSource.CODEC_PRIVATE, priv),
                element(WebmSource.AUDIO, element(0x9F, new byte[] { 2 })));
            raw(element(WebmSource.TRACKS, element(WebmSource.TRACK_ENTRY, entry)));
            return this;
        }

        private byte[] blockHead(int track, int lacingFlags) {
            return new byte[] { (byte) (0x80 | track), 0, 0, (byte) (0x80 | lacingFlags) };
        }

        void simpleBlock(int track, int unused, byte[] frame) {
            raw(element(WebmSource.SIMPLE_BLOCK, concat(blockHead(track, 0), frame)));
        }

        void xiph(int track, List<byte[]> frames) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] h = blockHead(track, 0x02);
            b.write(h, 0, h.length);
            b.write(frames.size() - 1);
            for (int i = 0; i < frames.size() - 1; i++) {
                int s = frames.get(i).length;
                while (s >= 255) {
                    b.write(255);
                    s -= 255;
                }
                b.write(s);
            }
            for (byte[] f : frames) b.write(f, 0, f.length);
            // Dentro de um BlockGroup, como Block (o outro caminho do mesmo código).
            raw(element(WebmSource.BLOCK_GROUP, element(WebmSource.BLOCK, b.toByteArray())));
        }

        void ebml(int track, List<byte[]> frames) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] h = blockHead(track, 0x06);
            b.write(h, 0, h.length);
            b.write(frames.size() - 1);
            // Primeiro tamanho como VINT de 2 bytes; depois diferenças com sinal em 2 bytes (alcance ±8191).
            int first = frames.get(0).length;
            b.write(0x40 | (first >> 8));
            b.write(first & 0xFF);
            for (int i = 1; i < frames.size() - 1; i++) {
                int diff = frames.get(i).length - frames.get(i - 1).length;
                int raw = diff + 8191;
                b.write(0x40 | (raw >> 8));
                b.write(raw & 0xFF);
            }
            for (byte[] f : frames) b.write(f, 0, f.length);
            raw(element(WebmSource.SIMPLE_BLOCK, b.toByteArray()));
        }

        void fixed(int track, List<byte[]> frames) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] h = blockHead(track, 0x04);
            b.write(h, 0, h.length);
            b.write(frames.size() - 1);
            for (byte[] f : frames) b.write(f, 0, f.length);
            raw(element(WebmSource.SIMPLE_BLOCK, b.toByteArray()));
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
