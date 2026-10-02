package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final RateLimiter limiter = new RateLimiter(now::get);
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void rajadaDeDuasVezesATaxa() {
        for (int i = 0; i < 20; i++) assertTrue(limiter.tryAcquire(a, 10), "ficha " + i);
        assertFalse(limiter.tryAcquire(a, 10));
    }

    @Test
    void recarregaComOTempo() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire(a, 10);
        assertFalse(limiter.tryAcquire(a, 10));
        now.addAndGet(100_000_000L); // 0,1 s = 1 ficha
        assertTrue(limiter.tryAcquire(a, 10));
        assertFalse(limiter.tryAcquire(a, 10));
        now.addAndGet(60_000_000_000L); // muito tempo: volta só até a capacidade
        for (int i = 0; i < 20; i++) assertTrue(limiter.tryAcquire(a, 10));
        assertFalse(limiter.tryAcquire(a, 10));
    }

    @Test
    void jogadoresSaoIndependentes() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire(a, 10);
        assertFalse(limiter.tryAcquire(a, 10));
        assertTrue(limiter.tryAcquire(b, 10));
    }

    @Test
    void esquecerZeraOBalde() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire(a, 10);
        limiter.forget(a);
        assertTrue(limiter.tryAcquire(a, 10));
    }

    @Test
    void relogioQueVoltaNaoDaFichas() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire(a, 10);
        now.addAndGet(-5_000_000_000L);
        assertFalse(limiter.tryAcquire(a, 10));
    }

    @Test
    void semJogadorRecusa() {
        assertFalse(limiter.tryAcquire(null, 10));
    }
}
