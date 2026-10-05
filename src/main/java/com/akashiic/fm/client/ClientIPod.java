package com.akashiic.fm.client;

import com.akashiic.fm.common.Pos;
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

    /** Os status dos blocos do iPod por perto, por posição (com a hora em que chegaram). */
    private static final java.util.Map<Pos, S2CIPodStatus> BLOCKS = new java.util.HashMap<>();
    private static final java.util.Map<Pos, Long> BLOCK_AT = new java.util.HashMap<>();

    public static void update(S2CIPodStatus message, long nowMs) {
        if (message.block) {
            Pos p = new Pos(message.x, message.y, message.z);
            BLOCKS.put(p, message);
            BLOCK_AT.put(p, nowMs);
            if (BLOCKS.size() > 64) { // esquece os velhos (o jogador andou por muitos blocos)
                BLOCK_AT.entrySet()
                    .removeIf(e -> nowMs - e.getValue() > EXPIRE_MS);
                BLOCKS.keySet()
                    .retainAll(BLOCK_AT.keySet());
            }
            return;
        }
        last = message;
        receivedAtMs = nowMs;
    }

    /** O status do bloco do iPod em (x, y, z), ou null se não há um recente. */
    public static S2CIPodStatus blockStatus(int x, int y, int z, long nowMs) {
        Pos p = new Pos(x, y, z);
        S2CIPodStatus s = BLOCKS.get(p);
        Long at = BLOCK_AT.get(p);
        return s == null || at == null || nowMs - at > EXPIRE_MS ? null : s;
    }

    /** Posição agora de um status de bloco (interpolada como a do item). */
    public static long blockPositionMs(S2CIPodStatus s, int x, int y, int z, long nowMs) {
        Long at = BLOCK_AT.get(new Pos(x, y, z));
        long p = s.positionMs;
        if (s.phase == S2CIPodStatus.Phase.PLAYING && at != null) p += Math.max(0, nowMs - at);
        return s.durationMs > 0 ? Math.min(p, s.durationMs) : p;
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
        BLOCKS.clear();
        BLOCK_AT.clear();
        lastSearch = null;
        flags = 0;
    }
}
