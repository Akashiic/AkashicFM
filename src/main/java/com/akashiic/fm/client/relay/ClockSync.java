package com.akashiic.fm.client.relay;

import net.minecraft.client.Minecraft;

import com.akashiic.fm.audio.relay.ClockMath;
import com.akashiic.fm.network.C2SClockPing;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CClockPong;

/**
 * Relógio do servidor visto pelo cliente. Pinga a cada 250 ms até ter 4 amostras e depois a cada 5 s; a
 * estimativa é a mediana do offset das 8 trocas de menor RTT entre as últimas 16 (erro máximo = RTT/2 da
 * melhor). Pongs chegam pela thread de rede; o resto roda na thread principal.
 */
public final class ClockSync {

    private static final ClockMath.Estimator ESTIMATOR = new ClockMath.Estimator(16, 8);
    private static final long FAST_INTERVAL_NANOS = 250_000_000L;
    private static final long SLOW_INTERVAL_NANOS = 5_000_000_000L;
    private static final int MIN_SAMPLES = 3;
    private static long lastPingNanos;
    private static boolean pinged;

    private ClockSync() {}

    /** Thread principal, todo tick do cliente. */
    public static void tick(Minecraft mc) {
        if (mc.theWorld == null || mc.getNetHandler() == null) {
            reset();
            return;
        }
        long now = System.nanoTime();
        long interval = ESTIMATOR.samples() < 4 ? FAST_INTERVAL_NANOS : SLOW_INTERVAL_NANOS;
        if (!pinged || now - lastPingNanos >= interval) {
            pinged = true;
            lastPingNanos = now;
            FmNetwork.sendToServer(new C2SClockPing(now / 1000));
        }
    }

    /** Thread de rede. */
    public static void onPong(S2CClockPong pong, long t3) {
        ESTIMATOR.add(ClockMath.offset(pong.t0, pong.t1, pong.t2, t3), ClockMath.rtt(pong.t0, pong.t1, pong.t2, t3));
    }

    public static boolean ready() {
        return ESTIMATOR.ready(MIN_SAMPLES);
    }

    /** Agora, no relógio do servidor (ms, com fração). */
    public static double serverNowMs() {
        return (System.nanoTime() / 1000 + ESTIMATOR.offsetMicros()) / 1000.0;
    }

    /** Menor RTT visto (ms), para diagnóstico; -1 sem amostras. */
    public static double bestRttMs() {
        long r = ESTIMATOR.bestRttMicros();
        return r < 0 ? -1 : r / 1000.0;
    }

    public static void reset() {
        ESTIMATOR.clear();
        pinged = false;
    }
}
