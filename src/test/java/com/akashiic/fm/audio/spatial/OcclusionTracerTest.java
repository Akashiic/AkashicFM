package com.akashiic.fm.audio.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OcclusionTracerTest {

    private static final double WOOL = 0.9, GLASS = 0.1, STONE = 0.6, WOOD = 0.4;

    /** Mundo sintético: só os blocos postos, o resto é ar. */
    private static final class Grid implements OcclusionTracer.BlockAbsorption {

        final Map<Long, Double> blocks = new HashMap<>();

        Grid put(int x, int y, int z, double a) {
            blocks.put(key(x, y, z), a);
            return this;
        }

        /** Parede no plano x = wx, de y0..y1 e z0..z1. */
        Grid wallX(int wx, int y0, int y1, int z0, int z1, double a) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) put(wx, y, z, a);
            return this;
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
        }

        @Override
        public double absorption(int x, int y, int z) {
            Double a = blocks.get(key(x, y, z));
            return a == null ? 0 : a;
        }

        /** Como superfícies para a sondagem: todo bloco posto reflete, com o valor como amortecimento. */
        OcclusionTracer.SurfaceDamping surfaces() {
            return (x, y, z) -> {
                Double a = blocks.get(key(x, y, z));
                return a == null ? -1 : a;
            };
        }
    }

    private static double occ(Grid g, double lx, double ex) {
        return OcclusionTracer.occlusion(g, lx, 64.5, 0.5, ex, 64.5, 0.5, 512, null);
    }

    @Test
    void campoAbertoNaoOclui() {
        assertEquals(0, occ(new Grid(), 0.5, 10.5), 1e-9);
    }

    @Test
    void laAbafaMaisQueVidro() {
        // Os 4 raios deslocados atravessam a parede um pouco inclinados (caminho 0,08% maior).
        double wool = occ(new Grid().wallX(5, 60, 70, -5, 5, WOOL), 0.5, 10.5);
        double glass = occ(new Grid().wallX(5, 60, 70, -5, 5, GLASS), 0.5, 10.5);
        assertEquals(WOOL, wool, 2e-3);
        assertEquals(GLASS, glass, 2e-3);
        assertTrue(wool > glass);
    }

    @Test
    void paredeGrossaAbafaMais() {
        double one = occ(new Grid().wallX(5, 60, 70, -5, 5, STONE), 0.5, 10.5);
        double two = occ(
            new Grid().wallX(5, 60, 70, -5, 5, STONE)
                .wallX(6, 60, 70, -5, 5, STONE),
            0.5,
            10.5);
        assertEquals(1 - 0.4 * 0.4, two, 3e-3);
        assertTrue(two > one);
    }

    @Test
    void blocosDoOuvinteEDaFonteNaoContam() {
        Grid g = new Grid().put(0, 64, 0, STONE)
            .put(10, 64, 0, STONE);
        assertEquals(0, occ(g, 0.5, 10.5), 1e-9);
    }

    @Test
    void pontosDeslocadosFicamNoBlocoDaFonte() {
        // Fonte perto da quina do próprio bloco (ex.: lado direito do par estéreo da rádio), com vizinhos sólidos.
        // Os raios deslocados não podem terminar num vizinho e contar o bloco da própria fonte no caminho.
        Grid g = new Grid().put(10, 64, 0, WOOD)
            .put(10, 65, 0, STONE)
            .put(10, 64, 1, STONE)
            .put(10, 65, 1, STONE)
            .put(11, 64, 0, STONE)
            .put(11, 65, 1, STONE);
        double o = OcclusionTracer.occlusion(g, 0.5, 64.5, 0.5, 10.95, 64.95, 0.95, 512, null);
        assertEquals(0, o, 1e-9);
    }

    @Test
    void quinaDaTransicaoSuave() {
        // Parede que termina em z = 0 (blocos z <= -1): a fonte desliza em z e a oclusão cai aos poucos.
        Grid g = new Grid().wallX(5, 60, 70, -10, -1, WOOL);
        double prev = Double.MAX_VALUE;
        int distinct = 0;
        double last = -1, first = Double.NaN;
        for (int i = 0; i <= 60; i++) {
            double ez = -3 + i * 0.1;
            double o = OcclusionTracer.occlusion(g, 0.5, 64.5, 0.5, 10.5, 64.5, ez, 512, null);
            if (i == 0) first = o;
            assertTrue(o <= prev + 1e-9, "a oclusão não pode subir saindo de trás da parede (z=" + ez + ")");
            if (Math.abs(o - last) > 1e-6) distinct++;
            last = o;
            prev = o;
        }
        assertTrue(first > 0.85, "atrás da parede: " + first);
        assertEquals(0, last, 1e-9);
        assertTrue(distinct >= 10, "transição em degraus suaves, não liga/desliga: " + distinct + " níveis");
    }

    @Test
    void andarAoLongoDaParedeNaoOscila() {
        // Regressão: contando voxels inteiros, um raio oblíquo passava por 1 ou 2 voxels da mesma parede de 1
        // bloco e a oclusão pulava entre 0,9 e 0,99. Pelo comprimento do caminho a variação é contínua.
        Grid g = new Grid().wallX(5, 50, 80, -40, 40, WOOL);
        double prev = Double.NaN;
        for (int i = 0; i <= 100; i++) {
            double ez = -10 + i * 0.2;
            double o = OcclusionTracer.occlusion(g, 0.5, 64.5, 0.5, 10.5, 64.5, ez, 512, null);
            if (!Double.isNaN(prev)) assertEquals(prev, o, 0.01, "salto de oclusão em z=" + ez);
            // 1 bloco de lã: 0,9 de frente; a 45° o caminho é √2 bloco e transmite 0,1^1,41 (oclusão 0,96).
            assertTrue(o > 0.89 && o < 0.97, "z=" + ez + " o=" + o);
            prev = o;
        }
    }

    @Test
    void raioRasanteNaQuinaAtenuaPouco() {
        // O raio corta só ~0,05 bloco da quina da lã: atenua ~10%, não 90%.
        Grid g = new Grid().put(5, 64, 0, WOOL);
        double t = OcclusionTracer.transmission(g, 0.5, 64.5, 0.5, 10.5, 64.5, 1.6, 512, null);
        assertTrue(t > 0.85 && t < 0.95, "t=" + t);
    }

    @Test
    void raioDiagonalAtravessaQuinasSemPularBloco() {
        // Linha de (0.5,0.5) a (10.5,10.5): passa por todos os blocos da diagonal; um muro na diagonal oclui.
        Grid g = new Grid();
        for (int i = 1; i < 10; i++) g.put(i, 64, i, WOOL);
        double t = OcclusionTracer.transmission(g, 0.5, 64.5, 0.5, 10.5, 64.5, 10.5, 512, null);
        assertEquals(0, t, 1e-9);
    }

    @Test
    void absorcaoTotalNaoCortaRaioRasante() {
        Grid g = new Grid().put(5, 64, 0, 1.0);
        // Mesmo raio rasante (~0,05 bloco dentro da quina): absorção 1 não vira corte seco.
        double t = OcclusionTracer.transmission(g, 0.5, 64.5, 0.5, 10.5, 64.5, 1.6, 512, null);
        assertTrue(t > 0.5, "t=" + t);
    }

    @Test
    void entradaInvalidaNaoTravaNemExplode() {
        Grid g = new Grid();
        assertEquals(1, OcclusionTracer.transmission(g, 0.5, 64.5, 0.5, 0.5, 64.5, 0.5, 512, null), 1e-12);
        assertEquals(1, OcclusionTracer.transmission(g, Double.NaN, 64.5, 0.5, 9, 64, 0, 512, null), 1e-12);
        assertEquals(0, OcclusionTracer.occlusion(g, 1, 2, 3, 1, 2, 3, 512, null), 1e-12);
        int[] steps = { 0 };
        OcclusionTracer.transmission(g, 0.5, 64.5, 0.5, Double.POSITIVE_INFINITY, 64.5, 0.5, 50, steps);
        assertTrue(steps[0] <= 50);
    }

    @Test
    void orcamentoDePassosEhContado() {
        int[] steps = { 0 };
        OcclusionTracer.transmission(new Grid(), 0.5, 64.5, 0.5, 20.5, 64.5, 0.5, 512, steps);
        assertEquals(19, steps[0]); // blocos 1..19 (sem o do ouvinte e o da fonte)
        int[] limited = { 0 };
        OcclusionTracer.transmission(new Grid(), 0.5, 64.5, 0.5, 20.5, 64.5, 0.5, 5, limited);
        assertTrue(limited[0] <= 5);
        // A estimativa de pior caso cobre os 5 raios.
        double[][] cases = { { 0.5, 64.5, 0.5, 20.5, 64.5, 0.5 }, { 0.3, 70.2, -4.1, 13.9, 61.7, 9.8 },
            { -7.5, 64.6, 3.3, -30.1, 80.2, -22.7 } };
        for (double[] c : cases) {
            int[] all = { 0 };
            OcclusionTracer.occlusion(new Grid(), c[0], c[1], c[2], c[3], c[4], c[5], 512, all);
            assertTrue(all[0] <= OcclusionTracer.worstCaseSteps(c[0], c[1], c[2], c[3], c[4], c[5]));
        }
    }

    @Test
    void mapeamentoDeGanhos() {
        assertEquals(1, OcclusionTracer.directGain(0), 1e-9);
        assertEquals(0.3, OcclusionTracer.directGain(1), 1e-9);
        assertEquals(1, OcclusionTracer.highFrequencyGain(0), 1e-9);
        assertEquals(0.02, OcclusionTracer.highFrequencyGain(1), 1e-9);
        assertTrue(OcclusionTracer.highFrequencyGain(0.5) < OcclusionTracer.directGain(0.5));
        assertEquals(0.15, OcclusionTracer.gainOnly(1), 1e-9);
        // O reverb é menos abafado que o caminho direto.
        for (double o = 0.1; o <= 1; o += 0.1) {
            assertTrue(OcclusionTracer.sendGain(o) > OcclusionTracer.directGain(o));
            assertTrue(OcclusionTracer.sendHighFrequencyGain(o) > OcclusionTracer.highFrequencyGain(o));
        }
        assertEquals(1, OcclusionTracer.sendGain(0), 1e-9);
        assertEquals(1, OcclusionTracer.sendHighFrequencyGain(-3), 1e-9);
    }

    @Test
    void primeiroImpactoDaSondagem() {
        Grid g = new Grid().wallX(4, 60, 70, -5, 5, STONE);
        double[] damp = { 0 };
        double d = OcclusionTracer.firstHit(g.surfaces(), 0.5, 64.5, 0.5, 1, 0, 0, 32, damp);
        assertEquals(3.5, d, 1e-9);
        assertEquals(STONE, damp[0], 1e-9);
        assertEquals(-1, OcclusionTracer.firstHit(g.surfaces(), 0.5, 64.5, 0.5, -1, 0, 0, 32, null), 1e-9);
        // Além do limite não conta.
        assertEquals(-1, OcclusionTracer.firstHit(g.surfaces(), 0.5, 64.5, 0.5, 1, 0, 0, 3, null), 1e-9);
        // Direção nula ou NaN termina.
        assertEquals(-1, OcclusionTracer.firstHit(g.surfaces(), 0.5, 64.5, 0.5, 0, 0, 0, 32, null), 1e-9);
        assertEquals(-1, OcclusionTracer.firstHit(g.surfaces(), 0.5, 64.5, 0.5, Double.NaN, 0, 0, 32, null), 1e-9);
        // O bloco do próprio ouvinte não conta.
        Grid inside = new Grid().put(0, 64, 0, STONE);
        assertEquals(-1, OcclusionTracer.firstHit(inside.surfaces(), 0.5, 64.5, 0.5, 1, 0, 0, 32, null), 1e-9);
    }
}
