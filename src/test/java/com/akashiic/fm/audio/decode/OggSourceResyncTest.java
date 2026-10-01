package com.akashiic.fm.audio.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;

/**
 * Stream OGG/Opus sintético com os casos que derrubam decoders ingênuos na troca de música:
 * A normal, B sem cabeçalhos (serial novo sem BOS), C com OpusHead corrompido, D normal.
 * O decoder precisa pular B e C e continuar tocando D.
 */
class OggSourceResyncTest {

    private static final int FRAME = 960; // 20 ms a 48 kHz
    private static final int PRE_SKIP = 312;

    @Test
    void pulaStreamsRuinsEContinuaTocando() throws Exception {
        ByteArrayOutputStream ogg = new ByteArrayOutputStream();
        writeStream(ogg, 1111, 50, true, true); // A
        writeStream(ogg, 2222, 50, false, true); // B
        writeStream(ogg, 3333, 50, true, false); // C
        writeStream(ogg, 4444, 50, true, true); // D

        OggSource src = new OggSource(new ByteArrayInputStream(ogg.toByteArray()), true);
        short[] buf = new short[4096];
        long samples = 0;
        int n;
        while ((n = src.read(buf, 0, buf.length)) != -1) samples += n;

        long expectedPerChannel = 2L * (50 * FRAME - PRE_SKIP); // só A e D tocam
        assertEquals(expectedPerChannel * 2, samples);
        assertEquals(48000, src.sampleRate());
        assertEquals(2, src.channels());
        assertTrue(
            src.codec()
                .contains("2 ressincronizações"),
            src.codec());
    }

    @Test
    void streamsEncadeadosNormaisTocamInteiros() throws Exception {
        ByteArrayOutputStream ogg = new ByteArrayOutputStream();
        for (int s = 0; s < 5; s++) writeStream(ogg, 100 + s, 10, true, true);
        OggSource src = new OggSource(new ByteArrayInputStream(ogg.toByteArray()), true);
        short[] buf = new short[4096];
        long samples = 0;
        int n;
        while ((n = src.read(buf, 0, buf.length)) != -1) samples += n;
        assertEquals(5L * (10 * FRAME - PRE_SKIP) * 2, samples);
        assertTrue(
            src.codec()
                .contains("5 streams encadeados"),
            src.codec());
    }

    static void writeStream(ByteArrayOutputStream out, int serial, int frames, boolean withHeaders, boolean validHead)
        throws Exception {
        int seq = 0;
        long granule = 0;
        if (withHeaders) {
            byte[] head = new byte[19];
            byte[] magic = (validHead ? "OpusHead" : "XpusHead").getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(magic, 0, head, 0, 8);
            head[8] = 1;
            head[9] = 2;
            head[10] = (byte) PRE_SKIP;
            head[11] = (byte) (PRE_SKIP >> 8);
            int rate = 48000;
            for (int i = 0; i < 4; i++) head[12 + i] = (byte) (rate >>> (8 * i));
            writePage(out, serial, seq++, 0, 0x02, head);
            writePage(out, serial, seq++, 0, 0, "OpusTags\0\0\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        } else {
            seq = 7; // continuação de um stream que nunca começou para nós
        }
        OpusEncoder enc = new OpusEncoder(48000, 2, OpusApplication.OPUS_APPLICATION_AUDIO);
        enc.setBitrate(64000);
        short[] pcm = new short[FRAME * 2];
        byte[] pkt = new byte[1275];
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < FRAME; i++) {
                short v = (short) (8000 * Math.sin(2 * Math.PI * 440 * (f * FRAME + i) / 48000.0));
                pcm[2 * i] = v;
                pcm[2 * i + 1] = v;
            }
            int len = enc.encode(pcm, 0, FRAME, pkt, 0, pkt.length);
            granule += FRAME;
            writePage(out, serial, seq++, granule, f == frames - 1 ? 0x04 : 0, Arrays.copyOf(pkt, len));
        }
    }

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
            CRC_TABLE[i] = r;
        }
    }

    /** CRC-32 do OGG: polinômio 0x04c11db7, MSB primeiro, valor inicial 0, sem XOR final. */
    private static int crc(int crc, byte[] data) {
        for (byte b : data) crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
        return crc;
    }

    /** Página OGG com um único pacote. */
    private static void writePage(ByteArrayOutputStream out, int serial, int seq, long granule, int type, byte[] data) {
        int segs = data.length / 255 + 1;
        byte[] header = new byte[27 + segs];
        header[0] = 'O';
        header[1] = 'g';
        header[2] = 'g';
        header[3] = 'S';
        header[5] = (byte) type;
        for (int i = 0; i < 8; i++) header[6 + i] = (byte) (granule >>> (8 * i));
        for (int i = 0; i < 4; i++) header[14 + i] = (byte) (serial >>> (8 * i));
        for (int i = 0; i < 4; i++) header[18 + i] = (byte) (seq >>> (8 * i));
        header[26] = (byte) segs;
        int remaining = data.length;
        for (int s = 0; s < segs; s++) {
            int lace = Math.min(255, remaining);
            header[27 + s] = (byte) lace;
            remaining -= lace;
        }
        int c = crc(crc(0, header), data);
        for (int i = 0; i < 4; i++) header[22 + i] = (byte) (c >>> (8 * i));
        out.write(header, 0, header.length);
        out.write(data, 0, data.length);
    }
}
