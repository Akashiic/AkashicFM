package com.akashiic.fm.audio.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class SpectrumAnalyzerTest {

    private static short[] sine(double hz, double amp, int frames) {
        short[] s = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            short v = (short) Math.round(amp * 32767 * Math.sin(2 * Math.PI * hz * i / 48000.0));
            s[2 * i] = v;
            s[2 * i + 1] = v;
        }
        return s;
    }

    private static int loudest(float[] bands) {
        int best = 0;
        for (int b = 1; b < bands.length; b++) if (bands[b] > bands[best]) best = b;
        return best;
    }

    @Test
    void senoCaiNaBandaCerta() {
        SpectrumAnalyzer a = new SpectrumAnalyzer(48000);
        float[] bands = new float[SpectrumAnalyzer.BANDS];
        for (int b = 0; b < SpectrumAnalyzer.BANDS; b++) {
            double hz = SpectrumAnalyzer.centerHz(b);
            a.analyze(sine(hz, 0.5, 2048), 2048, bands);
            assertEquals(b, loudest(bands), "seno de " + Math.round(hz) + " Hz");
            // -6 dBFS: 54/60 = 0,9 (tolerância pelo vazamento da janela entre bandas vizinhas).
            assertEquals(0.9, bands[b], 0.03, "nível da banda " + b);
        }
    }

    @Test
    void senoCheioEhZeroDbfs() {
        SpectrumAnalyzer a = new SpectrumAnalyzer(48000);
        float[] bands = new float[SpectrumAnalyzer.BANDS];
        float rms = a.analyze(sine(1000, 1.0, 2048), 2048, bands);
        assertEquals(1.0, bands[4], 0.02);
        assertEquals(1.0, rms, 0.01);
        // Bandas longe do seno ficam muito abaixo (Hann: lóbulos laterais caem rápido).
        assertTrue(bands[0] < 0.2 && bands[7] < 0.2, "vazamento: " + bands[0] + " " + bands[7]);
    }

    @Test
    void silencioEhZero() {
        SpectrumAnalyzer a = new SpectrumAnalyzer(48000);
        float[] bands = new float[SpectrumAnalyzer.BANDS];
        assertEquals(0f, a.analyze(new short[4096], 2048, bands), 0f);
        for (float b : bands) assertEquals(0f, b, 0f);
        assertEquals(0f, a.analyze(new short[0], 0, bands), 0f);
    }

    @Test
    void ruidoBrancoEspalhaPeloEspectro() {
        SpectrumAnalyzer a = new SpectrumAnalyzer(48000);
        float[] bands = new float[SpectrumAnalyzer.BANDS];
        Random r = new Random(7);
        short[] s = new short[4096];
        for (int i = 0; i < s.length; i += 2) {
            short v = (short) ((r.nextDouble() * 2 - 1) * 8000);
            s[i] = v;
            s[i + 1] = v;
        }
        a.analyze(s, 2048, bands);
        for (float b : bands) assertTrue(b > 0.2, "banda vazia com ruído branco");
        // Ruído branco tem a mesma densidade por Hz: bandas mais largas (agudas) somam mais energia.
        assertTrue(bands[7] > bands[0]);
    }

    @Test
    void canaisOpostosSeCancelamNaMistura() {
        SpectrumAnalyzer a = new SpectrumAnalyzer(48000);
        float[] bands = new float[SpectrumAnalyzer.BANDS];
        short[] s = sine(500, 0.5, 2048);
        for (int i = 0; i < 2048; i++) s[2 * i + 1] = (short) -s[2 * i];
        assertEquals(0f, a.analyze(s, 2048, bands), 0f);
    }

    @Test
    void blocoCurtoCompletaComZero() {
        SpectrumAnalyzer a = new SpectrumAnalyzer(48000);
        float[] bands = new float[SpectrumAnalyzer.BANDS];
        float rms = a.analyze(sine(1000, 0.5, 300), 300, bands);
        assertEquals(0.9, rms, 0.02); // RMS só dos frames presentes
        assertEquals(4, loudest(bands));
    }

    @Test
    void fftDeImpulsoEhPlana() {
        double[] re = new double[16], im = new double[16];
        re[0] = 1;
        SpectrumAnalyzer.fft(re, im);
        for (int k = 0; k < 16; k++) {
            assertEquals(1, re[k], 1e-12);
            assertEquals(0, im[k], 1e-12);
        }
    }

    @Test
    void mapeamentoDeNivel() {
        assertEquals(1f, SpectrumAnalyzer.level(0.5), 1e-6);
        assertEquals(0.5f, SpectrumAnalyzer.level(0.5 * Math.pow(10, -3)), 1e-6); // -30 dBFS
        assertEquals(0f, SpectrumAnalyzer.level(1e-12), 0f);
        assertEquals(0f, SpectrumAnalyzer.level(0), 0f);
        assertEquals(0f, SpectrumAnalyzer.level(Double.NaN), 0f);
        assertEquals(1f, SpectrumAnalyzer.level(10), 0f);
    }
}
