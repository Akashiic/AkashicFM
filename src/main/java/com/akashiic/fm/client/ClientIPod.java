package com.akashiic.fm.client;

import com.akashiic.fm.network.S2CIPodSearchResults;
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

    // ---- Busca ----

    private static S2CIPodSearchResults lastSearch;
    private static int flags;
    private static int nextRequest = 1;

    /** Um número novo para o próximo pedido de busca (a resposta de um pedido velho é ignorada). */
    public static int newRequest() {
        nextRequest = nextRequest >= 0x7FFFFFF ? 1 : nextRequest + 1;
        return nextRequest;
    }

    public static void searchResults(S2CIPodSearchResults message) {
        flags = message.flags;
        if (message.requestId != 0) lastSearch = message;
    }

    /** O que a tela pode oferecer (flags de {@link S2CIPodSearchResults}), da última resposta do servidor. */
    public static int flags() {
        return flags;
    }

    /** Os resultados do pedido {@code requestId}, ou null se ainda não chegaram. */
    public static S2CIPodSearchResults results(int requestId) {
        S2CIPodSearchResults r = lastSearch;
        return r != null && requestId != 0 && r.requestId == requestId ? r : null;
    }

    public static void clear() {
        last = null;
        receivedAtMs = 0;
        lastSearch = null;
        flags = 0;
    }
}
