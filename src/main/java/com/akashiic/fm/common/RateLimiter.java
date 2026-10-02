package com.akashiic.fm.common;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Token bucket por jogador: {@code ratePerSecond} fichas por segundo, com rajada de até 2x.
 * Thread-safe: é consultado pelo handler de rede, antes de a ação entrar na fila do tick.
 */
public final class RateLimiter {

    private static final class Bucket {

        double tokens;
        long lastNanos;
    }

    private final Map<UUID, Bucket> buckets = new HashMap<>();
    private final LongSupplier clock;

    public RateLimiter() {
        this(System::nanoTime);
    }

    /** Relógio injetável para testes. */
    public RateLimiter(LongSupplier nanoClock) {
        this.clock = nanoClock;
    }

    /** Consome uma ficha. Devolve false se o jogador estourou o limite. */
    public synchronized boolean tryAcquire(UUID player, int ratePerSecond) {
        if (player == null) return false;
        double rate = Math.max(1, ratePerSecond);
        double capacity = rate * 2;
        long now = clock.getAsLong();
        Bucket b = buckets.get(player);
        if (b == null) {
            b = new Bucket();
            b.tokens = capacity;
            b.lastNanos = now;
            buckets.put(player, b);
        } else {
            double elapsed = Math.max(0, now - b.lastNanos) / 1e9;
            b.tokens = Math.min(capacity, b.tokens + elapsed * rate);
            b.lastNanos = now;
        }
        if (b.tokens < 1) return false;
        b.tokens -= 1;
        return true;
    }

    public synchronized void forget(UUID player) {
        buckets.remove(player);
    }

    public synchronized void clear() {
        buckets.clear();
    }
}
