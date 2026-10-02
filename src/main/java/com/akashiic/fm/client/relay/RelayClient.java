package com.akashiic.fm.client.relay;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.akashiic.fm.network.S2CAudio;
import com.akashiic.fm.network.S2CListen;

/**
 * Estações do relay que o servidor está mandando para este cliente. É o servidor quem decide (pela distância);
 * o cliente só acompanha. Thread-safe: início/fim e áudio chegam pela thread de rede, a reprodução lê na
 * thread principal.
 */
public final class RelayClient {

    private static final Map<Integer, RelayFeed> BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, Integer> ID_BY_URL = new ConcurrentHashMap<>();

    private RelayClient() {}

    public static void onListen(S2CListen m) {
        if (m.start) {
            RelayFeed feed = new RelayFeed(m.stationId, m.url, m.latencyMs).start();
            RelayFeed old = BY_ID.put(m.stationId, feed);
            if (old != null) old.shutdown();
            ID_BY_URL.put(m.url, m.stationId);
        } else {
            RelayFeed feed = BY_ID.remove(m.stationId);
            if (feed != null) {
                ID_BY_URL.remove(feed.url(), feed.stationId());
                feed.shutdown();
            }
        }
    }

    public static void onAudio(S2CAudio m) {
        RelayFeed feed = BY_ID.get(m.stationId);
        if (feed != null) feed.offer(m.frames);
    }

    /** Estação que o servidor está mandando para a URL, ou null. */
    public static RelayFeed feedForUrl(String url) {
        Integer id = ID_BY_URL.get(url);
        return id == null ? null : BY_ID.get(id);
    }

    public static int activeStations() {
        return BY_ID.size();
    }

    /** Desconectou: encerra tudo. */
    public static void clear() {
        for (RelayFeed f : BY_ID.values()) f.shutdown();
        BY_ID.clear();
        ID_BY_URL.clear();
    }
}
