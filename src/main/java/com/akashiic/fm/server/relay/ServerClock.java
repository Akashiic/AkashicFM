package com.akashiic.fm.server.relay;

/**
 * Relógio do relay: monotônico (não anda para trás com ajuste de hora do sistema). PTS dos frames e as trocas
 * de sincronia usam esta mesma base.
 */
public final class ServerClock {

    private ServerClock() {}

    public static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    public static long nowMicros() {
        return System.nanoTime() / 1_000L;
    }
}
