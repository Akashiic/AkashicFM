package com.akashiic.fm.client;

import com.akashiic.fm.network.S2CIPodStatus;

/**
 * O último status do iPod deste jogador ({@link S2CIPodStatus}, a cada meio segundo enquanto ele toca). A posição é
 * interpolada entre um pacote e outro; um status que deixou de chegar por {@link #EXPIRE_MS} é esquecido. Thread
 * principal do cliente.
 */
public final class ClientIPod {

    static final long EXPIRE_MS = 3000;

    private static S2CIPodStatus last;
    private static long receivedAtMs;

    private ClientIPod() {}

    public static void update(S2CIPodStatus message, long nowMs) {
        last = message;
        receivedAtMs = nowMs;
    }

    /** O status do iPod com esta identidade, ou null se não há um recente. */
    public static S2CIPodStatus status(long itemId, long nowMs) {
        S2CIPodStatus s = last;
        if (s == null || s.itemId != itemId || nowMs - receivedAtMs > EXPIRE_MS) return null;
        return s;
    }

    /** Posição agora: a do pacote mais o tempo desde ele (só tocando), sem passar da duração. */
    public static long positionMs(S2CIPodStatus s, long nowMs) {
        long p = s.positionMs;
        if (s.phase == S2CIPodStatus.Phase.PLAYING) p += Math.max(0, nowMs - receivedAtMs);
        return s.durationMs > 0 ? Math.min(p, s.durationMs) : p;
    }

    public static void clear() {
        last = null;
        receivedAtMs = 0;
    }
}
