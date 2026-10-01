package com.akashiic.fm.audio.decode;

import java.io.IOException;
import java.io.InputStream;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import net.sourceforge.jaad.adts.ADTSDemultiplexer;

/** AAC (ADTS) via JAAD chamado direto. HE-AAC (SBR) sai com a taxa dobrada, o resampler cuida. */
final class AacSource implements PcmSource {

    private static final int MAX_BAD_FRAMES_IN_A_ROW = 50;

    private final InputStream raw;
    private final ADTSDemultiplexer adts;
    private final Decoder decoder;
    private final SampleBuffer sb = new SampleBuffer();
    private short[] pending = new short[0];
    private int pendingOff;
    private int pendingLen;
    private int sampleRate;
    private int channels;

    AacSource(InputStream in) throws IOException {
        this.raw = in;
        this.adts = new ADTSDemultiplexer(in);
        this.decoder = new Decoder(adts.getDecoderSpecificInfo());
        sb.setBigEndian(false);
        if (!decodeNextFrame()) throw new IOException("nenhum frame AAC válido");
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
        return "aac";
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
            byte[] frame;
            try {
                frame = adts.readNextFrame();
            } catch (java.io.EOFException e) {
                return false;
            }
            try {
                decoder.decodeFrame(frame, sb);
            } catch (Exception e) {
                if (++bad > MAX_BAD_FRAMES_IN_A_ROW) throw new IOException("AAC corrompido demais", e);
                continue;
            }
            byte[] data = sb.getData();
            int n = data.length / 2;
            if (n == 0) continue;
            sampleRate = sb.getSampleRate();
            channels = sb.getChannels();
            if (pending.length < n) pending = new short[n];
            for (int i = 0; i < n; i++) pending[i] = (short) ((data[2 * i] & 0xFF) | (data[2 * i + 1] << 8));
            pendingOff = 0;
            pendingLen = n;
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        raw.close();
    }
}
