package com.akashiic.fm.common;

import java.util.List;

/**
 * Qual transmissor uma rádio sintonizada ouve: entre os ativos da mesma frequência (e da mesma dimensão, que quem
 * chama já filtrou), o de sinal mais forte, com sinal = 1 − distância/alcance. Para não alternar na fronteira de
 * dois transmissores, o atual só perde se sair da cobertura ou se outro tiver pelo menos {@link #SWITCH_MARGIN} de
 * sinal a mais. Funções puras, testadas em FrequencyResolverTest.
 */
public final class FrequencyResolver {

    static final double SWITCH_MARGIN = 0.1;

    /** Um transmissor visto pela resolução. */
    public static final class Transmitter {

        public final Pos pos;
        public final int frequency;
        public final int range;
        public final String url;
        public final String name;
        public final boolean active;

        public Transmitter(Pos pos, int frequency, int range, String url, String name, boolean active) {
            this.pos = pos;
            this.frequency = frequency;
            this.range = range;
            this.url = url == null ? "" : url;
            this.name = name == null ? "" : name;
            this.active = active;
        }
    }

    /** Resultado: o transmissor ouvido e o sinal (0..1]. */
    public static final class Tuning {

        public final Transmitter transmitter;
        public final double signal;

        Tuning(Transmitter transmitter, double signal) {
            this.transmitter = transmitter;
            this.signal = signal;
        }
    }

    private FrequencyResolver() {}

    /** 1 no transmissor, 0 na borda do alcance (ou fora dele). */
    public static double signal(Transmitter t, double x, double y, double z) {
        if (t.range <= 0) return 0;
        double d = Math.sqrt(t.pos.distanceSqTo(x, y, z));
        double s = 1 - d / t.range;
        return s > 0 ? s : 0;
    }

    /**
     * @param current posição do transmissor ouvido até agora (histerese), ou null
     * @return o transmissor ouvido, ou null se nenhum cobre o ponto nessa frequência
     */
    public static Tuning resolve(List<Transmitter> transmitters, int frequency, double x, double y, double z,
        Pos current) {
        Transmitter best = null, kept = null;
        double bestSignal = 0, keptSignal = 0, bestDistSq = Double.MAX_VALUE;
        for (Transmitter t : transmitters) {
            if (!t.active || t.frequency != frequency || t.url.isEmpty()) continue;
            double s = signal(t, x, y, z);
            if (s <= 0) continue;
            double distSq = t.pos.distanceSqTo(x, y, z);
            if (best == null || s > bestSignal || (s == bestSignal && before(t, distSq, best, bestDistSq))) {
                best = t;
                bestSignal = s;
                bestDistSq = distSq;
            }
            if (current != null && current.equals(t.pos)) {
                kept = t;
                keptSignal = s;
            }
        }
        if (best == null) return null;
        if (kept != null && bestSignal - keptSignal < SWITCH_MARGIN) return new Tuning(kept, keptSignal);
        return new Tuning(best, bestSignal);
    }

    /** Desempate determinístico: mais perto, depois menor posição (x, z, y). */
    private static boolean before(Transmitter a, double distA, Transmitter b, double distB) {
        if (distA != distB) return distA < distB;
        if (a.pos.x != b.pos.x) return a.pos.x < b.pos.x;
        if (a.pos.z != b.pos.z) return a.pos.z < b.pos.z;
        return a.pos.y < b.pos.y;
    }
}
