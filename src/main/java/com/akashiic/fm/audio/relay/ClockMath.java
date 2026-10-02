package com.akashiic.fm.audio.relay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sincronia de relógio no estilo NTP. Numa troca, o cliente manda t0 (relógio dele), o servidor carimba t1
 * ao receber e t2 ao responder, e o cliente recebe em t3. Todos em microssegundos.
 */
public final class ClockMath {

    private ClockMath() {}

    /** Quanto o relógio do servidor está à frente do cliente. */
    public static long offset(long t0, long t1, long t2, long t3) {
        return ((t1 - t0) + (t2 - t3)) / 2;
    }

    /** Ida e volta só da rede (desconta o tempo que o servidor segurou a resposta). */
    public static long rtt(long t0, long t1, long t2, long t3) {
        return (t3 - t0) - (t2 - t1);
    }

    /**
     * Estimador: guarda as últimas {@code window} trocas e devolve a mediana do offset das {@code best} com
     * menor RTT (as de menor RTT têm o menor erro possível, que é RTT/2). Thread-safe.
     */
    public static final class Estimator {

        private final int window;
        private final int best;
        private final long[] offsets;
        private final long[] rtts;
        private int count;
        private int next;

        public Estimator(int window, int best) {
            this.window = window;
            this.best = best;
            this.offsets = new long[window];
            this.rtts = new long[window];
        }

        public synchronized void add(long offsetMicros, long rttMicros) {
            if (rttMicros < 0) return; // relógio não monotônico ou troca inválida
            offsets[next] = offsetMicros;
            rtts[next] = rttMicros;
            next = (next + 1) % window;
            if (count < window) count++;
        }

        public synchronized int samples() {
            return count;
        }

        /** Pronto para uso com pelo menos {@code minSamples} trocas. */
        public synchronized boolean ready(int minSamples) {
            return count >= minSamples;
        }

        /** Melhor estimativa do offset (µs). 0 sem amostras. */
        public synchronized long offsetMicros() {
            if (count == 0) return 0;
            List<long[]> s = new ArrayList<>(count);
            for (int i = 0; i < count; i++) s.add(new long[] { rtts[i], offsets[i] });
            Collections.sort(s, (a, b) -> Long.compare(a[0], b[0]));
            int k = Math.min(best, s.size());
            long[] chosen = new long[k];
            for (int i = 0; i < k; i++) chosen[i] = s.get(i)[1];
            java.util.Arrays.sort(chosen);
            return k % 2 == 1 ? chosen[k / 2] : (chosen[k / 2 - 1] + chosen[k / 2]) / 2;
        }

        /** Menor RTT visto na janela (µs), ou -1 sem amostras. */
        public synchronized long bestRttMicros() {
            if (count == 0) return -1;
            long m = Long.MAX_VALUE;
            for (int i = 0; i < count; i++) m = Math.min(m, rtts[i]);
            return m;
        }

        public synchronized void clear() {
            count = 0;
            next = 0;
        }
    }
}
