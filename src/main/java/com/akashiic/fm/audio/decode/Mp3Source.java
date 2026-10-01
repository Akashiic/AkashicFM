package com.akashiic.fm.audio.decode;

import java.io.IOException;
import java.io.InputStream;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/** MP3 via JLayer, chamado direto (sem o SPI do Java Sound). Frames corrompidos são pulados. */
final class Mp3Source implements PcmSource {

    private static final int MAX_BAD_FRAMES_IN_A_ROW = 50;

    private final InputStream raw;
    private final Bitstream bitstream;
    private final Decoder decoder = new Decoder();
    private short[] pending = new short[0];
    private int pendingOff;
    private int pendingLen;
    private int sampleRate;
    private int channels;

    Mp3Source(InputStream in) throws IOException {
        this.raw = in;
        this.bitstream = new Bitstream(in);
        if (!decodeNextFrame()) throw new IOException("nenhum frame MP3 válido");
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public String codec() {
        return "mp3";
    }

    @Override
    public int read(short[] out, int off, int len) throws IOException {
        if (pendingLen == 0 && !decodeNextFrame()) return -1;
        int n = Math.min(len, pendingLen);
        System.arraycopy(pending, pendingOff, out, off, n);
        pendingOff += n;
        pendingLen -= n;
        return n;
    }

    private boolean decodeNextFrame() throws IOException {
        int bad = 0;
        while (true) {
            Header h;
            try {
                h = bitstream.readFrame();
            } catch (BitstreamException e) {
                throw new IOException("erro no bitstream MP3", e);
            }
            if (h == null) return false;
            try {
                SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                sampleRate = sb.getSampleFrequency();
                channels = sb.getChannelCount();
                int n = sb.getBufferLength();
                if (pending.length < n) pending = new short[n];
                System.arraycopy(sb.getBuffer(), 0, pending, 0, n);
                pendingOff = 0;
                pendingLen = n;
                return true;
            } catch (DecoderException | RuntimeException e) {
                if (++bad > MAX_BAD_FRAMES_IN_A_ROW) throw new IOException("MP3 corrompido demais", e);
            } finally {
                bitstream.closeFrame();
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            bitstream.close();
        } catch (BitstreamException ignored) {}
        raw.close();
    }
}
