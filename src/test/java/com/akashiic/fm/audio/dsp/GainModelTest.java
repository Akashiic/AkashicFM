package com.akashiic.fm.audio.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GainModelTest {

    private static final double EPS = 1e-9;

    @Test
    void volumeCheioPertoEZeroNoAlcance() {
        assertEquals(1, GainModel.distanceAttenuation(0, 24), EPS);
        assertEquals(1, GainModel.distanceAttenuation(GainModel.NEAR_DISTANCE, 24), EPS);
        assertEquals(0, GainModel.distanceAttenuation(24, 24), EPS);
        assertEquals(0, GainModel.distanceAttenuation(1000, 24), EPS);
    }

    @Test
    void decresceSemSaltos() {
        double prev = 1;
        for (double d = 0; d <= 30; d += 0.01) {
            double a = GainModel.distanceAttenuation(d, 24);
            assertTrue(a <= prev + EPS, "não pode subir em d=" + d);
            assertTrue(prev - a < 0.002, "salto em d=" + d);
            prev = a;
        }
    }

    @Test
    void metadeDoCaminhoDaUmQuarto() {
        // (1-x)²: no meio entre NEAR e o alcance o ganho é 0,25 (-12 dB).
        double mid = (GainModel.NEAR_DISTANCE + 24) / 2;
        assertEquals(0.25, GainModel.distanceAttenuation(mid, 24), EPS);
    }

    @Test
    void entradasInvalidasDaoZero() {
        assertEquals(0, GainModel.distanceAttenuation(Double.NaN, 24), EPS);
        assertEquals(0, GainModel.distanceAttenuation(-1, 24), EPS);
        assertEquals(0, GainModel.distanceAttenuation(1, 0), EPS);
        assertEquals(0, GainModel.distanceAttenuation(1, Double.NaN), EPS);
    }

    @Test
    void alcanceMenorQueAZonaCheia() {
        assertEquals(1, GainModel.distanceAttenuation(1.5, 1.5), EPS);
        assertEquals(0, GainModel.distanceAttenuation(1.6, 1.5), EPS);
    }

    @Test
    void curvaDoVolume() {
        assertEquals(0, GainModel.volumeCurve(0), EPS);
        assertEquals(0.25, GainModel.volumeCurve(50), EPS);
        assertEquals(1, GainModel.volumeCurve(100), EPS);
        assertEquals(1, GainModel.volumeCurve(500), EPS);
        assertEquals(0, GainModel.volumeCurve(-5), EPS);
    }

    @Test
    void ganhoFinalMultiplicaEPrende() {
        assertEquals(1f, GainModel.sourceGain(1f, 100, 100, 0, 24, 1), 1e-6);
        assertEquals(0.5f * 0.5f * 0.25f, GainModel.sourceGain(0.5f, 50, 50, 1, 24, 1), 1e-6);
        assertEquals(0f, GainModel.sourceGain(1f, 100, 100, 30, 24, 1), 1e-6);
        assertEquals(1f, GainModel.sourceGain(2f, 200, 100, 0, 24, 5), 1e-6);
        assertEquals(0f, GainModel.sourceGain(-1f, 100, 100, 0, 24, 1), 1e-6);
        assertEquals(0.5f, GainModel.sourceGain(1f, 100, 100, 0, 24, 0.5), 1e-6);
    }

    @Test
    void suavizacaoConvergeEDtZeroEncaixa() {
        float g = 0;
        for (int i = 0; i < 200; i++) g = GainModel.smooth(g, 1f, 0.016, 0.08);
        assertEquals(1f, g, 1e-4);
        assertEquals(0.7f, GainModel.smooth(0.1f, 0.7f, 0, 0.08), 1e-6);
        // Uma constante de tempo leva ~63% do caminho.
        assertEquals(1 - Math.exp(-1), GainModel.smooth(0f, 1f, 0.08, 0.08), 1e-6);
    }
}
