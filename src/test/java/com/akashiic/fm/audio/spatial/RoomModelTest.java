package com.akashiic.fm.audio.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class RoomModelTest {

    private static final double STONE = 0.15, WOOL = 0.9, IRON = 0.08, WOOD = 0.35;

    private static RoomModel.Params probe(int hits, int total, double dist, double damping) {
        double[] d = new double[total], a = new double[total];
        Arrays.fill(d, -1);
        for (int i = 0; i < hits; i++) {
            d[i] = dist;
            a[i] = damping;
        }
        return RoomModel.fromProbe(d, a);
    }

    @Test
    void campoAbertoSemReverb() {
        // Metade dos raios bate no chão, metade vai para o céu: fechamento 0,5, nada de sala.
        assertEquals(0, probe(8, 16, 3, 0.55).wet, 1e-6);
        assertEquals(0, probe(6, 16, 3, 0.55).wet, 1e-6);
    }

    @Test
    void semImpactoNenhumEhSeco() {
        double[] d = new double[16];
        Arrays.fill(d, -1);
        assertSame(RoomModel.DRY, RoomModel.fromProbe(d, new double[16]));
        assertSame(RoomModel.DRY, RoomModel.fromProbe(new double[0], new double[0]));
    }

    @Test
    void distanciaInvalidaNaoConta() {
        double[] d = new double[16];
        Arrays.fill(d, Double.NaN);
        assertSame(RoomModel.DRY, RoomModel.fromProbe(d, new double[16]));
    }

    @Test
    void salaPequenaDePedra() {
        RoomModel.Params p = probe(16, 16, 3, STONE);
        assertEquals(0.8 * (1 - 0.5 * STONE), p.wet, 1e-6);
        // Sabine: 0,161/3 · 3 / 0,15 ≈ 1,07 s.
        assertEquals(0.161 / 3 * 3 / STONE, p.decayTime, 1e-4);
        assertEquals(2 * 3 / 343.0, p.reflectionsDelay, 1e-6);
    }

    @Test
    void fechamentoParcialDaPoucoReverb() {
        RoomModel.Params porta = probe(14, 16, 3, STONE); // sala com a porta aberta
        RoomModel.Params fechada = probe(16, 16, 3, STONE);
        assertTrue(porta.wet > 0 && porta.wet < fechada.wet);
        assertEquals(fechada.decayTime, porta.decayTime, 1e-6);
    }

    @Test
    void cavernaGrandeDecaiMaisDevagar() {
        RoomModel.Params small = probe(16, 16, 3, STONE);
        RoomModel.Params cave = probe(16, 16, 25, STONE);
        assertTrue(cave.decayTime > small.decayTime * 2);
        assertTrue(cave.reflectionsDelay > small.reflectionsDelay);
        assertEquals(4.0f, cave.decayTime, 1e-6); // teto
        assertTrue(cave.lateReverbDelay <= 0.1f);
    }

    @Test
    void laApagaOsAgudosEEncurtaOReverb() {
        RoomModel.Params wool = probe(16, 16, 4, WOOL);
        RoomModel.Params wood = probe(16, 16, 4, WOOD);
        RoomModel.Params stone = probe(16, 16, 4, STONE);
        RoomModel.Params iron = probe(16, 16, 4, IRON);
        assertTrue(wool.decayHfRatio < wood.decayHfRatio);
        assertTrue(wood.decayHfRatio < stone.decayHfRatio);
        assertTrue(stone.decayHfRatio < iron.decayHfRatio);
        assertTrue(wool.decayTime < wood.decayTime && wood.decayTime < stone.decayTime);
        assertTrue(wool.wet < stone.wet);
        assertEquals(0.2f, probe(16, 16, 1, WOOL).decayTime, 1e-6); // piso
    }

    @Test
    void faixasDoEfxSempreRespeitadas() {
        for (double dist : new double[] { 0, 0.3, 5, 40, 1e6 }) {
            for (double damp : new double[] { 0, 0.5, 1, 7 }) {
                RoomModel.Params p = probe(16, 16, dist, damp);
                assertTrue(p.wet >= 0 && p.wet <= 1, p.toString());
                assertTrue(p.decayTime >= 0.1f && p.decayTime <= 20f, p.toString());
                assertTrue(p.decayHfRatio >= 0.1f && p.decayHfRatio <= 2f, p.toString());
                assertTrue(p.reflectionsDelay >= 0f && p.reflectionsDelay <= 0.3f, p.toString());
                assertTrue(p.lateReverbDelay >= 0f && p.lateReverbDelay <= 0.1f, p.toString());
            }
        }
    }

    @Test
    void transicaoSuaveEDeteccaoDeMudanca() {
        RoomModel.Params a = RoomModel.DRY, b = probe(16, 16, 10, STONE);
        RoomModel.Params mid = RoomModel.blend(a, b, 0.5);
        assertEquals((a.wet + b.wet) / 2, mid.wet, 1e-6);
        assertEquals((a.lateReverbDelay + b.lateReverbDelay) / 2, mid.lateReverbDelay, 1e-6);
        assertSame(b, RoomModel.blend(null, b, 0.5));
        assertTrue(b.differsFrom(a));
        assertTrue(b.differsFrom(null));
        assertFalse(b.differsFrom(b));
        assertFalse(b.differsFrom(RoomModel.blend(b, a, 0.001)));
    }

    @Test
    void direcoesUnitariasEMetadeParaBaixo() {
        double[][] dirs = RoomModel.directions(16, 1.234);
        int down = 0;
        for (double[] d : dirs) {
            assertEquals(1, d[0] * d[0] + d[1] * d[1] + d[2] * d[2], 1e-9);
            if (d[1] < 0) down++;
        }
        assertEquals(8, down);
    }
}
