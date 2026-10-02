package com.akashiic.fm.server.relay;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.server.ServerPolicy;

/**
 * Estações do relay, uma por URL. Só a thread principal do servidor mexe aqui. Uma estação sem ouvintes
 * continua viva por {@link #GRACE_MS} (quem sai e volta logo não espera reconectar) e depois é fechada.
 */
final class StationHub {

    static final long GRACE_MS = 10_000;

    private final Map<String, Station> byUrl = new HashMap<>();
    private final Map<Integer, Station> byId = new HashMap<>();
    private int nextId = 1;

    /** Estação da URL, criando se cabe no limite do config. Null se não cabe. */
    Station acquire(String url, long nowMs) {
        Station s = byUrl.get(url);
        if (s == null) {
            if (byUrl.size() >= Math.max(1, FmConfig.Relay.maxStations)) return null;
            s = new Station(nextId++, url, ServerPolicy.urlPolicy(), FmConfig.Relay.opusBitrateKbps).start();
            byUrl.put(url, s);
            byId.put(s.id, s);
            AkashicFM.LOG.info("Relay: estação {} aberta para {}", s.id, url);
        }
        s.lastWantedMs = nowMs;
        return s;
    }

    Station get(String url) {
        return byUrl.get(url);
    }

    Station byId(int id) {
        return byId.get(id);
    }

    boolean has(String url) {
        return byUrl.containsKey(url);
    }

    int size() {
        return byUrl.size();
    }

    Collection<Station> all() {
        return byUrl.values();
    }

    /** Fecha as estações sem ouvintes há mais que a carência. */
    void expire(long nowMs) {
        Iterator<Station> it = byUrl.values()
            .iterator();
        while (it.hasNext()) {
            Station s = it.next();
            if (nowMs - s.lastWantedMs > GRACE_MS) {
                close(s);
                it.remove();
            }
        }
    }

    /** Fecha e esquece a estação da URL (próximo pedido abre outra: usado para tentar de novo após erro). */
    void restart(String url) {
        Station s = byUrl.remove(url);
        if (s != null) close(s);
    }

    private void close(Station s) {
        s.close();
        byId.remove(s.id);
        AkashicFM.LOG.info("Relay: estação {} fechada ({})", s.id, s.url);
    }

    void closeAll() {
        for (Station s : byUrl.values()) s.close();
        byUrl.clear();
        byId.clear();
    }
}
