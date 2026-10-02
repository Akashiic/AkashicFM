package com.akashiic.fm.client;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.akashiic.fm.AkashicFM;

/** Trabalho vindo dos handlers de rede (ou da thread do netty), executado na thread principal (ClientTickEvent). */
public final class ClientTaskQueue {

    private static final int MAX_PER_TICK = 256;
    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();

    private ClientTaskQueue() {}

    public static void add(Runnable task) {
        if (task != null) QUEUE.add(task);
    }

    public static void drain() {
        for (int i = 0; i < MAX_PER_TICK; i++) {
            Runnable r = QUEUE.poll();
            if (r == null) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                AkashicFM.LOG.error("AkashicFM: tarefa do cliente falhou", e);
            }
        }
    }
}
