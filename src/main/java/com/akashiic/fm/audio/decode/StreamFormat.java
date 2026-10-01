package com.akashiic.fm.audio.decode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Descobre o codec pelo Content-Type e confirma olhando os primeiros bytes. */
public enum StreamFormat {

    MP3,
    AAC_ADTS,
    OGG_VORBIS,
    OGG_OPUS,
    UNSUPPORTED;

    private static final int SNIFF = 8192;

    /** {@code in} precisa suportar mark/reset; os bytes lidos são devolvidos ao stream. */
    public static StreamFormat detect(String contentType, BufferedInputStream in) throws IOException {
        in.mark(SNIFF);
        byte[] head = new byte[SNIFF];
        int n = 0;
        while (n < SNIFF) {
            int r = in.read(head, n, SNIFF - n);
            if (r == -1) break;
            n += r;
        }
        in.reset();

        if (n >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
            String page = new String(head, 0, n, StandardCharsets.ISO_8859_1);
            if (page.contains("OpusHead")) return OGG_OPUS;
            if (page.contains("\u0001vorbis")) return OGG_VORBIS;
            return UNSUPPORTED; // FLAC/Speex em OGG ficam fora do MVP
        }

        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int start = 0;
        if (n >= 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            // pula a tag ID3v2: tamanho em 4 bytes "synchsafe" (7 bits cada)
            start = 10 + ((head[6] & 0x7F) << 21 | (head[7] & 0x7F) << 14 | (head[8] & 0x7F) << 7 | (head[9] & 0x7F));
        }
        for (int i = start; i + 1 < n; i++) {
            if ((head[i] & 0xFF) != 0xFF) continue;
            int b1 = head[i + 1] & 0xFF;
            if ((b1 & 0xF6) == 0xF0) return AAC_ADTS; // sync de 12 bits + layer 00
            if ((b1 & 0xE0) == 0xE0 && ((b1 >> 1) & 3) != 0 && !ct.contains("aac")) return MP3; // layer I/II/III
        }
        if (ct.contains("mpeg") || ct.contains("mp3")) return MP3;
        if (ct.contains("aac")) return AAC_ADTS;
        return UNSUPPORTED;
    }

    public static PcmSource open(StreamFormat format, InputStream in) throws IOException {
        switch (format) {
            case MP3:
                return new Mp3Source(in);
            case AAC_ADTS:
                return new AacSource(in);
            case OGG_VORBIS:
                return new OggSource(in, false);
            case OGG_OPUS:
                return new OggSource(in, true);
            default:
                throw new IOException("formato não suportado");
        }
    }
}
