package com.akashiic.fm.audio.spatial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Quando recalcular a oclusão de cada emissor, dentro de um orçamento de blocos visitados por tick. Lógica pura
 * (o traçado entra por {@link Tracer}), testada em OcclusionSchedulerTest.
 * <ul>
 * <li>emissor novo: calculado já (até 4× o orçamento; o excedente fica para o tick seguinte, primeiro da fila);</li>
 * <li>valor vence depois de {@link #REFRESH_TICKS} ticks (mais 1 a cada 16 blocos de distância: longe, a
 * oclusão muda devagar) ou se o ouvinte andou mais de 1 bloco desde o cálculo;</li>
 * <li>vencidos são recalculados do mais velho para o mais novo até o orçamento acabar; o resto usa o valor
 * anterior e fica mais velho, então nenhum emissor passa fome;</li>
 * <li>entrada sem uso por {@link #EVICT_TICKS} ticks é esquecida.</li>
 * </ul>
 */
public final class OcclusionScheduler {

    /** Traçado de um emissor a partir do ouvinte do tick; soma os blocos visitados em {@code steps[0]}. */
    public interface Tracer {

        double occlusion(double ex, double ey, double ez, int[] steps);
    }

    public static final int REFRESH_TICKS = 4;
    public static final int EVICT_TICKS = 40;
    /** Emissores novos podem passar do orçamento até este fator (evita um tick de som sem oclusão). */
    static final int NEW_ENTRY_BUDGET_FACTOR = 4;
    private static final double MOVED_SQ = 1.0;

    private static final class Key {

        final long x, y, z;

        Key(double x, double y, double z) {
            this.x = Math.round(x * 1000);
            this.y = Math.round(y * 1000);
            this.z = Math.round(z * 1000);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return k.x == x && k.y == y && k.z == z;
        }

        @Override
        public int hashCode() {
            long h = x * 31_000_003L + y * 1_009L + z;
            return (int) (h ^ (h >>> 32));
        }
    }

    private static final class Entry {

        boolean known;
        double value;
        long computedTick;
        long usedTick;
        double lx, ly, lz;
        /** Prioridade neste tick (maior primeiro); NaN = em dia. */
        double priority;
    }

    private final Map<Key, Entry> entries = new HashMap<>();
    private long tick;

    /** Diagnóstico do último tick: blocos visitados, emissores traçados e vencidos que ficaram para depois. */
    public int lastSteps, lastTraced, lastDeferred;

    /**
     * Oclusão de cada emissor ({@code xyz} em trios), para o ouvinte em L. Devolve um valor por emissor, na mesma
     * ordem.
     */
    public double[] resolve(double[] xyz, double lx, double ly, double lz, int budget, Tracer tracer) {
        tick++;
        int n = xyz.length / 3;
        Entry[] byIndex = new Entry[n];
        List<Entry> stale = new ArrayList<>();
        List<double[]> stalePos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = xyz[3 * i], y = xyz[3 * i + 1], z = xyz[3 * i + 2];
            Key key = new Key(x, y, z);
            Entry e = entries.get(key);
            if (e == null) {
                e = new Entry();
                entries.put(key, e);
            }
            byIndex[i] = e;
            if (e.usedTick == tick) continue; // mesmo emissor duas vezes na lista
            e.usedTick = tick;
            e.priority = priority(e, x, y, z, lx, ly, lz);
            if (!Double.isNaN(e.priority)) {
                stale.add(e);
                stalePos.add(new double[] { x, y, z });
            }
        }
        Integer[] order = new Integer[stale.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final List<Entry> s = stale;
        Arrays.sort(order, (a, b) -> Double.compare(s.get(b).priority, s.get(a).priority));

        int[] steps = { 0 };
        int traced = 0, deferred = 0;
        for (int idx : order) {
            Entry e = stale.get(idx);
            double[] p = stalePos.get(idx);
            int cost = OcclusionTracer.worstCaseSteps(lx, ly, lz, p[0], p[1], p[2]);
            int limit = e.known ? budget : budget * NEW_ENTRY_BUDGET_FACTOR;
            if (steps[0] + cost > limit) {
                deferred++;
                continue;
            }
            e.value = tracer.occlusion(p[0], p[1], p[2], steps);
            e.known = true;
            e.computedTick = tick;
            e.lx = lx;
            e.ly = ly;
            e.lz = lz;
            traced++;
        }
        lastSteps = steps[0];
        lastTraced = traced;
        lastDeferred = deferred;

        Iterator<Entry> it = entries.values()
            .iterator();
        while (it.hasNext()) if (tick - it.next().usedTick > EVICT_TICKS) it.remove();

        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = byIndex[i].known ? byIndex[i].value : 0;
        return out;
    }

    /** NaN se o valor está em dia; senão, quanto maior, mais urgente. */
    private double priority(Entry e, double x, double y, double z, double lx, double ly, double lz) {
        if (!e.known) return Double.MAX_VALUE;
        double age = tick - e.computedTick;
        double mx = lx - e.lx, my = ly - e.ly, mz = lz - e.lz;
        boolean moved = mx * mx + my * my + mz * mz > MOVED_SQ;
        double dx = x - lx, dy = y - ly, dz = z - lz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int refresh = REFRESH_TICKS + (int) (dist / 16);
        if (!moved && age < refresh) return Double.NaN;
        return moved ? 1e6 + age : age;
    }

    /** Esquece tudo (mundo trocado, oclusão desligada). */
    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
