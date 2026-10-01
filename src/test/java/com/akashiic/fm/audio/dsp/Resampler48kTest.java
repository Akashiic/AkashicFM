package com.akashiic.fm.audio.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Resampler48kTest {

    private static int run(int inRate, int inChannels, int seconds, short[][] lastOut) {
        Resampler48k rs = new Resampler48k(inRate, inChannels);
        int chunk = 4410 * inChannels;
        short[] in = new short[chunk];
        short[] out = new short[rs.maxOut(chunk)];
        int totalFrames = inRate * seconds;
        int produced = 0;
        for (int done = 0; done < totalFrames; done += chunk / inChannels) {
            int frames = Math.min(chunk / inChannels, totalFrames - done);
            for (int i = 0; i < frames; i++) {
                short v = (short) (10000 * Math.sin(2 * Math.PI * 1000 * (done + i) / (double) inRate));
                for (int c = 0; c < inChannels; c++) in[i * inChannels + c] = c == 0 ? v : (short) -v;
            }
            int n = rs.process(in, frames * inChannels, out);
            assertTrue(n <= out.length, "maxOut() subestimou o buffer de saída");
            produced += n;
            lastOut[0] = out;
        }
        return produced / 2; // frames estéreo
    }

    @Test
    void converteTaxasComunsPara48k() {
        short[][] last = new short[1][];
        for (int rate : new int[] { 22050, 32000, 44100, 48000, 96000 }) {
            int frames = run(rate, 2, 2, last);
            assertEquals(96000, frames, 2.0, "2 s em " + rate + " Hz devem virar ~96000 frames a 48 kHz");
        }
    }

    @Test
    void monoViraEstereoDuplicado() {
        Resampler48k rs = new Resampler48k(48000, 1);
        short[] in = { 0, 100, 200, 300, 400 };
        short[] out = new short[rs.maxOut(in.length)];
        int n = rs.process(in, in.length, out);
        assertTrue(n >= 2);
        for (int i = 0; i < n; i += 2) assertEquals(out[i], out[i + 1]);
    }
}
