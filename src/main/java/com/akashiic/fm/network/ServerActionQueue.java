package com.akashiic.fm.network;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.RateLimiter;
import com.akashiic.fm.server.RadioActionHandler;

/**
 * Fila entre a thread de rede (que recebe os pacotes) e a thread principal do servidor (que mexe no mundo).
 * O 1.7.10 não tem agendamento de tarefas na thread principal; mexer em TE/chunk na thread de rede corrompe
 * o mundo, então tudo passa por aqui e é processado em {@link #drain()} no tick.
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

    private ServerActionQueue() {}

    /** Thread de rede. Descarta se o jogador estourou o limite de ações ou se a fila está cheia. */
    static void offer(EntityPlayerMP player, C2SRadioAction action) {
        if (player == null) return;
        UUID id = player.getUniqueID();
        if (!LIMITER.tryAcquire(id, FmConfig.Limits.actionsPerSecond)) return;
        if (SIZE.incrementAndGet() > MAX_PENDING) {
            SIZE.decrementAndGet();
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
            RadioActionHandler.handle(p.player, p.action);
        }
    }

    public static void clear() {
        QUEUE.clear();
        SIZE.set(0);
        LIMITER.clear();
    }
}
