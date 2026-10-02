package com.akashiic.fm.audio.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class OcclusionSchedulerTest {

    /**
     * Traçador falso: oclusão = x/100, custo = pior caso do traçado real a partir de (0.5, 64.5, 0.5), registra
     * quem foi traçado.
     */
    private static final class FakeTracer implements OcclusionScheduler.Tracer {

        final List<Double> traced = new ArrayList<>();

        @Override
        public double occlusion(double ex, double ey, double ez, int[] steps) {
            traced.add(ex);
            steps[0] += OcclusionTracer.worstCaseSteps(0.5, 64.5, 0.5, ex, ey, ez);
            return ex / 100;
        }
    }

    /** {@code n} emissores à mesma distância L1 (8,5) do ouvinte em (0.5, 64.5, 0.5): mesmo custo de pior caso. */
    private static double[] sameCost(int n) {
        double[] out = new double[n * 3];
        for (int k = 0; k < n; k++) {
            out[3 * k] = 9.0 - k;
            out[3 * k + 1] = 64.5;
            out[3 * k + 2] = 0.5 + k;
        }
        return out;
    }

    private static final int COST_EACH = OcclusionTracer.worstCaseSteps(0.5, 64.5, 0.5, 9.0, 64.5, 0.5);

    private static double[] row(double... xs) {
        double[] out = new double[xs.length * 3];
        for (int i = 0; i < xs.length; i++) {
            out[3 * i] = xs[i];
            out[3 * i + 1] = 64.5;
            out[3 * i + 2] = 0.5;
        }
        return out;
    }

    @Test
    void novosSaoCalculadosNaHoraEReaproveitados() {
        OcclusionScheduler s = new OcclusionScheduler();
        FakeTracer t = new FakeTracer();
        double[] em = row(10, 15); // a menos de 16 blocos: vencem em 4 ticks
        assertArrayEquals(new double[] { 0.1, 0.15 }, s.resolve(em, 0.5, 64.5, 0.5, 8192, t), 1e-12);
        assertEquals(2, t.traced.size());
        // Nos próximos ticks (antes de vencer) não traça de novo.
        for (int i = 1; i < OcclusionScheduler.REFRESH_TICKS; i++) {
            assertArrayEquals(new double[] { 0.1, 0.15 }, s.resolve(em, 0.5, 64.5, 0.5, 8192, t), 1e-12);
        }
        assertEquals(2, t.traced.size());
        // Venceu: recalcula.
        s.resolve(em, 0.5, 64.5, 0.5, 8192, t);
        assertEquals(4, t.traced.size());
    }

    @Test
    void ouvinteQueAndouInvalidaNaHora() {
        OcclusionScheduler s = new OcclusionScheduler();
        FakeTracer t = new FakeTracer();
        double[] em = row(10);
        s.resolve(em, 0.5, 64.5, 0.5, 8192, t);
        s.resolve(em, 0.9, 64.5, 0.5, 8192, t); // andou 0,4: não conta
        assertEquals(1, t.traced.size());
        s.resolve(em, 2.0, 64.5, 0.5, 8192, t); // andou 1,5 desde o cálculo
        assertEquals(2, t.traced.size());
    }

    @Test
    void longeRecalculaMaisDevagar() {
        OcclusionScheduler s = new OcclusionScheduler();
        FakeTracer t = new FakeTracer();
        double[] em = row(100.5); // 100 blocos: vence em 4 + 6 ticks
        s.resolve(em, 0.5, 64.5, 0.5, 1 << 20, t);
        for (int i = 1; i < 10; i++) s.resolve(em, 0.5, 64.5, 0.5, 1 << 20, t);
        assertEquals(1, t.traced.size());
        s.resolve(em, 0.5, 64.5, 0.5, 1 << 20, t);
        assertEquals(2, t.traced.size());
    }

    @Test
    void orcamentoRespeitadoENinguemPassaFome() {
        OcclusionScheduler s = new OcclusionScheduler();
        FakeTracer t = new FakeTracer();
        double[] em = sameCost(8);
        int budget = COST_EACH * 2; // cabem 2 recálculos por tick
        // Primeiro tick: todos novos, cabem até 4× o orçamento (8 emissores = 4× o orçamento exato).
        s.resolve(em, 0.5, 64.5, 0.5, budget, t);
        assertEquals(8, t.traced.size());
        // Todos vencem juntos; com orçamento para 2 por tick, em 4 ticks cada um foi recalculado uma vez.
        for (int i = 1; i < OcclusionScheduler.REFRESH_TICKS; i++) s.resolve(em, 0.5, 64.5, 0.5, budget, t);
        assertEquals(8, t.traced.size());
        t.traced.clear();
        for (int i = 0; i < 4; i++) {
            s.resolve(em, 0.5, 64.5, 0.5, budget, t);
            assertEquals(2, s.lastTraced);
            assertTrue(s.lastSteps <= budget, "orçamento: " + s.lastSteps);
        }
        assertEquals(8, t.traced.size());
        assertEquals(
            8,
            t.traced.stream()
                .distinct()
                .count(),
            "cada emissor exatamente uma vez: " + t.traced);
    }

    @Test
    void novosAlemDoLimiteFicamParaOProximoTick() {
        OcclusionScheduler s = new OcclusionScheduler();
        FakeTracer t = new FakeTracer();
        double[] em = sameCost(8);
        int budget = COST_EACH; // novos: até 4 por tick
        double[] first = s.resolve(em, 0.5, 64.5, 0.5, budget, t);
        assertEquals(4, s.lastTraced);
        assertEquals(4, s.lastDeferred);
        int unknown = 0;
        for (double v : first) if (v == 0) unknown++;
        assertEquals(4, unknown);
        s.resolve(em, 0.5, 64.5, 0.5, budget, t);
        assertEquals(8, t.traced.size());
        assertEquals(0, s.lastDeferred);
    }

    @Test
    void repetidoNaListaEhTracadoUmaVez() {
        OcclusionScheduler s = new OcclusionScheduler();
        FakeTracer t = new FakeTracer();
        double[] out = s.resolve(row(10, 10, 10), 0.5, 64.5, 0.5, 8192, t);
        assertEquals(1, t.traced.size());
        assertArrayEquals(new double[] { 0.1, 0.1, 0.1 }, out, 1e-12);
    }

    @Test
    void entradasSemUsoSaoEsquecidas() {
        OcclusionScheduler s = new OcclusionScheduler();
        FakeTracer t = new FakeTracer();
        s.resolve(row(10, 20), 0.5, 64.5, 0.5, 8192, t);
        assertEquals(2, s.size());
        for (int i = 0; i <= OcclusionScheduler.EVICT_TICKS; i++) s.resolve(row(10), 0.5, 64.5, 0.5, 8192, t);
        assertEquals(1, s.size());
        s.clear();
        assertEquals(0, s.size());
    }
}
