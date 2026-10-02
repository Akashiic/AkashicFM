package com.akashiic.fm.network;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.RateLimiter;
import com.akashiic.fm.server.RadioActionHandler;

/**
 * Fila entre a chegada das ações (handler de rede) e o processamento no início do tick do servidor, com rate
 * limit por jogador e orçamento por tick. No 1.7.10 o handler já roda na thread principal (fila do vanilla),
 * mas o código não depende disso: mexer em TE/chunk fora dela corromperia o mundo, então tudo passa por aqui
 * e é aplicado em {@link #drain()}.
 */
public final class ServerActionQueue {

    private static final int MAX_PENDING = 4096;
    private static final int MAX_PER_TICK = 512;

    private static final class Pending {

        final EntityPlayerMP player;
        final C2SRadioAction action;

        Pending(EntityPlayerMP player, C2SRadioAction action) {
            this.player = player;
            this.action = action;
        }
    }

    private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    public static final RateLimiter LIMITER = new RateLimiter();
    /** Contadores para diagnóstico (comando de admin e testes E2E). */
    private static final AtomicLong PROCESSED = new AtomicLong(), RATE_LIMITED = new AtomicLong(),
        OVERFLOW = new AtomicLong();

    private ServerActionQueue() {}

    /** Chamado pelo handler (qualquer thread). Descarta se o jogador estourou o limite ou se a fila está cheia. */
    static void offer(EntityPlayerMP player, C2SRadioAction action) {
        if (player == null) return;
        UUID id = player.getUniqueID();
        if (!LIMITER.tryAcquire(id, FmConfig.Limits.actionsPerSecond)) {
            RATE_LIMITED.incrementAndGet();
            return;
        }
        if (SIZE.incrementAndGet() > MAX_PENDING) {
            SIZE.decrementAndGet();
            OVERFLOW.incrementAndGet();
            return;
        }
        QUEUE.add(new Pending(player, action));
    }

    /** Thread principal do servidor, uma vez por tick. */
    public static void drain() {
        for (int i = 0; i < MAX_PER_TICK; i++) {
            Pending p = QUEUE.poll();
            if (p == null) return;
            SIZE.decrementAndGet();
            PROCESSED.incrementAndGet();
            RadioActionHandler.handle(p.player, p.action);
        }
    }

    public static String stats() {
        return "processed=" + PROCESSED
            .get() + " rateLimited=" + RATE_LIMITED.get() + " overflow=" + OVERFLOW.get() + " pending=" + SIZE.get();
    }

    public static void clear() {
        QUEUE.clear();
        SIZE.set(0);
        LIMITER.clear();
    }
}
