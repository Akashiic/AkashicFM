package com.akashiic.fm.audio.decode;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

/**
 * WebM (Matroska) com Opus, o áudio do YouTube: lê os elementos EBML em sequência, sem pular para trás (serve para
 * arquivo e para stream), acha a trilha {@code A_OPUS} e decodifica os blocos dela com o Concentus, em 48 kHz.
 * <p>
 * Os containers que importam (Segment, Cluster, Tracks, TrackEntry, BlockGroup, Audio e o cabeçalho EBML) são
 * percorridos por dentro, mesmo com tamanho desconhecido (stream ao vivo); todo o resto (Cues, Tags, SeekHead, Void,
 * vídeo...) é pulado pelo tamanho. Lacing Xiph, fixa e EBML.
 */
public final class WebmSource implements PcmSource {

    static final int EBML = 0x1A45DFA3, DOC_TYPE = 0x4282, SEGMENT = 0x18538067, CLUSTER = 0x1F43B675,
        TRACKS = 0x1654AE6B, TRACK_ENTRY = 0xAE, TRACK_NUMBER = 0xD7, CODEC_ID = 0x86, CODEC_PRIVATE = 0x63A2,
        AUDIO = 0xE1, BLOCK_GROUP = 0xA0, BLOCK = 0xA1, SIMPLE_BLOCK = 0xA3;
    /** Maior elemento simples que se lê inteiro (CodecPrivate, blocos); maiores são pulados. */
    static final int MAX_ELEMENT = 1 << 20;
    private static final int MAX_FRAME = 5760; // 120 ms a 48 kHz

    private final InputStream in;
    private OpusDecoder decoder;
    private int channels = 2;
    private int preSkip;
    private long opusTrack = -1;
    // Trilha sendo lida (os campos de uma TrackEntry vêm em sequência).
    private long entryNumber = -1;
    private String entryCodec = "";
    private byte[] entryPrivate;

    private short[] pending = new short[MAX_FRAME * 2];
    private int pendingOff, pendingLen;
    private boolean eof;

    public WebmSource(InputStream in) throws IOException {
        this.in = in;
        long id = readId();
        if (id != EBML) throw new IOException("não é WebM/Matroska");
        readSize(); // o cabeçalho é um container: os filhos (DocType etc.) vêm a seguir
        // Os primeiros blocos já exigem a trilha conhecida: lê até achar o Opus (ou desistir).
        while (decoder == null) {
            if (!step()) throw new IOException("WebM sem trilha Opus");
        }
    }

    @Override
    public int sampleRate() {
        return 48000;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public String codec() {
        return "opus/webm";
    }

    @Override
    public int read(short[] out, int off, int len) throws IOException {
        while (pendingLen == 0) {
            if (eof || !step()) {
                eof = true;
                return -1;
            }
        }
        int n = Math.min(len, pendingLen);
        System.arraycopy(pending, pendingOff, out, off, n);
        pendingOff += n;
        pendingLen -= n;
        return n;
    }

    /** Lê o próximo elemento. Devolve false no fim do stream. */
    private boolean step() throws IOException {
        long id;
        try {
            id = readId();
        } catch (EOFException e) {
            return false;
        }
        long size = readSize();
        switch ((int) id) {
            case EBML: // outro cabeçalho (stream encadeado): segue lendo por dentro
            case SEGMENT:
            case CLUSTER:
            case TRACKS:
            case AUDIO:
            case BLOCK_GROUP:
                return true; // container: os filhos vêm a seguir
            case TRACK_ENTRY:
                entryNumber = -1;
                entryCodec = "";
                entryPrivate = null;
                return true;
            case TRACK_NUMBER:
                entryNumber = readUnsigned(size);
                maybeTrackDone();
                return true;
            case CODEC_ID:
                entryCodec = new String(readBytes(size), StandardCharsets.US_ASCII).trim();
                maybeTrackDone();
                return true;
            case CODEC_PRIVATE:
                entryPrivate = readBytes(size);
                maybeTrackDone();
                return true;
            case SIMPLE_BLOCK:
            case BLOCK:
                // Bloco grande demais não é de áudio (um quadro-chave de vídeo): pula sem ler para a memória.
                if (size > MAX_ELEMENT) skip(size);
                else block(readBytes(size));
                return true;
            default:
                skip(size);
                return true;
        }
    }

    /** A TrackEntry de Opus está completa (número, codec e OpusHead): prepara o decoder. */
    private void maybeTrackDone() throws IOException {
        if (decoder != null || entryNumber < 0 || !"A_OPUS".equals(entryCodec) || entryPrivate == null) return;
        byte[] head = entryPrivate;
        if (head.length < 19 || !new String(head, 0, 8, StandardCharsets.ISO_8859_1).equals("OpusHead")) {
            throw new IOException("CodecPrivate sem OpusHead");
        }
        channels = Math.max(1, Math.min(2, head[9] & 0xFF));
        preSkip = (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
        try {
            decoder = new OpusDecoder(48000, channels);
        } catch (OpusException e) {
            throw new IOException("OpusHead inválido");
        }
        opusTrack = entryNumber;
    }

    private void block(byte[] b) throws IOException {
        if (decoder == null) return;
        int[] pos = { 0 };
        long track = vint(b, pos, true);
        if (track != opusTrack || pos[0] + 3 > b.length) return;
        pos[0] += 2; // timecode relativo: a ordem dos blocos basta
        int flags = b[pos[0]++] & 0xFF;
        int lacing = (flags >> 1) & 3;
        if (lacing == 0) {
            decode(b, pos[0], b.length - pos[0]);
            return;
        }
        int frames = (b[pos[0]++] & 0xFF) + 1;
        int[] sizes = new int[frames];
        int known = 0;
        if (lacing == 1) { // Xiph: cada tamanho é uma soma de bytes até um < 255
            for (int i = 0; i < frames - 1; i++) {
                int v, s = 0;
                do {
                    if (pos[0] >= b.length) return;
                    v = b[pos[0]++] & 0xFF;
                    s += v;
                } while (v == 255);
                sizes[i] = s;
                known += s;
            }
        } else if (lacing == 3) { // EBML: o primeiro tamanho e depois diferenças com sinal
            sizes[0] = (int) vint(b, pos, true);
            known = sizes[0];
            for (int i = 1; i < frames - 1; i++) {
                sizes[i] = sizes[i - 1] + (int) signedVint(b, pos);
                known += sizes[i];
            }
        } else { // fixa: todos iguais
            int each = (b.length - pos[0]) / frames;
            for (int i = 0; i < frames; i++) sizes[i] = each;
            known = each * (frames - 1);
        }
        if (lacing != 2) sizes[frames - 1] = b.length - pos[0] - known;
        for (int i = 0; i < frames; i++) {
            if (sizes[i] < 0 || pos[0] + sizes[i] > b.length) return; // bloco corrompido: pula
            decode(b, pos[0], sizes[i]);
            pos[0] += sizes[i];
        }
    }

    private void decode(byte[] data, int off, int len) {
        if (len <= 0) return;
        if (pendingOff > 0) {
            System.arraycopy(pending, pendingOff, pending, 0, pendingLen);
            pendingOff = 0;
        }
        if (pending.length < pendingLen + MAX_FRAME * channels) {
            short[] bigger = new short[Math.max(pending.length * 2, pendingLen + MAX_FRAME * channels)];
            System.arraycopy(pending, 0, bigger, 0, pendingLen);
            pending = bigger;
        }
        int base = pendingLen;
        int decoded;
        try {
            decoded = decoder.decode(data, off, len, pending, base, MAX_FRAME, false);
        } catch (OpusException e) {
            return; // pacote ruim: pula
        }
        int skip = Math.min(preSkip, decoded);
        preSkip -= skip;
        if (skip > 0) System.arraycopy(pending, base + skip * channels, pending, base, (decoded - skip) * channels);
        pendingLen += (decoded - skip) * channels;
    }

    // ---- EBML ----

    /** ID do elemento (com o marcador, como nas especificações: 1 a 4 bytes). */
    private long readId() throws IOException {
        int first = in.read();
        if (first < 0) throw new EOFException();
        int len = Integer.numberOfLeadingZeros(first) - 23; // 1 para 0x80.., 4 para 0x10..
        if (len < 1 || len > 4) throw new IOException("ID EBML inválido");
        long v = first;
        for (int i = 1; i < len; i++) v = (v << 8) | readByte();
        return v;
    }

    /** Tamanho (sem o marcador); -1 = desconhecido (todos os bits 1). */
    private long readSize() throws IOException {
        int first = readByte();
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 8) throw new IOException("tamanho EBML inválido");
        long v = first & (0xFF >> len);
        boolean allOnes = v == (0xFF >> len);
        for (int i = 1; i < len; i++) {
            int b = readByte();
            if (b != 0xFF) allOnes = false;
            v = (v << 8) | b;
        }
        return allOnes ? -1 : v;
    }

    private long readUnsigned(long size) throws IOException {
        if (size < 0 || size > 8) throw new IOException("inteiro EBML inválido");
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | readByte();
        return v;
    }

    private byte[] readBytes(long size) throws IOException {
        if (size < 0 || size > MAX_ELEMENT) throw new IOException("elemento EBML grande demais");
        byte[] b = new byte[(int) size];
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) throw new EOFException();
            got += n;
        }
        return b;
    }

    private void skip(long size) throws IOException {
        if (size < 0) throw new IOException("elemento de tamanho desconhecido fora de um container");
        long left = size;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new EOFException();
            left -= n;
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    /** VINT dentro de um buffer (número da trilha, tamanhos da lacing EBML). */
    static long vint(byte[] b, int[] pos, boolean stripMarker) throws IOException {
        if (pos[0] >= b.length) throw new IOException("VINT fora do bloco");
        int first = b[pos[0]++] & 0xFF;
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 8 || pos[0] + len - 1 > b.length) throw new IOException("VINT inválido");
        long v = stripMarker ? first & (0xFF >> len) : first;
        for (int i = 1; i < len; i++) v = (v << 8) | (b[pos[0]++] & 0xFF);
        return v;
    }

    /** Diferença com sinal da lacing EBML: o valor menos metade do alcance do tamanho. */
    static long signedVint(byte[] b, int[] pos) throws IOException {
        int start = pos[0];
        long raw = vint(b, pos, true);
        int len = pos[0] - start;
        return raw - ((1L << (7 * len - 1)) - 1);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
