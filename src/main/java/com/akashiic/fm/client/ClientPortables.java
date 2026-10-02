package com.akashiic.fm.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.akashiic.fm.network.S2CPortableSources;

/**
 * Os rádios portáteis que o servidor diz que este jogador ouve (o dele e os de quem está perto). A lista chega
 * inteira e é renovada a cada 2 s; sem renovação por {@link #EXPIRE_MS} ela é esquecida (o servidor caiu, o
 * jogador mudou de mundo...). Thread principal do cliente.
 */
public final class ClientPortables {

    static final long EXPIRE_MS = 5000;

    private static List<S2CPortableSources.Entry> entries = Collections.emptyList();
    private static long receivedAtMs;

    private ClientPortables() {}

    public static void update(S2CPortableSources message, long nowMs) {
        entries = Collections.unmodifiableList(new ArrayList<>(message.entries));
        receivedAtMs = nowMs;
    }

    /** A lista atual, ou vazia se ficou velha. */
    public static List<S2CPortableSources.Entry> current(long nowMs) {
        if (entries.isEmpty() || nowMs - receivedAtMs > EXPIRE_MS) return Collections.emptyList();
        return entries;
    }

    /** A fonte do portátil deste jogador (entidade dele), ou null. */
    public static S2CPortableSources.Entry own(int entityId, long nowMs) {
        for (S2CPortableSources.Entry e : current(nowMs)) if (e.entityId == entityId) return e;
        return null;
    }

    public static void clear() {
        entries = Collections.emptyList();
        receivedAtMs = 0;
    }
}
