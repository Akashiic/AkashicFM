package com.akashiic.fm.server.relay;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.audio.stream.MediaLocator;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.server.ServerPolicy;

/**
 * Estações do relay, uma por URL. Só a thread principal do servidor mexe aqui. Uma estação sem ouvintes
 * continua viva por {@link #GRACE_MS} (quem sai e volta logo não espera reconectar) e depois é fechada.
 * <p>
 * Estações com chave ({@link #KEY_PREFIX}, as do iPod): o áudio vem do {@link MediaLocator} registrado para a chave
 * ({@link #register}), nunca de uma URL digitada (a política de URL recusa o esquema {@code ipod:}). Quando uma delas
 * fecha (expirou, terminou ou foi trocada), o registro vai junto: uma estação com chave nunca recomeça a faixa sozinha.
 */
final class StationHub {

    static final long GRACE_MS = 10_000;
    static final String KEY_PREFIX = "ipod:";

    private final Map<String, Station> byUrl = new HashMap<>();
    private final Map<Integer, Station> byId = new HashMap<>();
    private final Map<String, MediaLocator> keyed = new HashMap<>();
    private int nextId = 1;

    static boolean isKey(String url) {
        return url != null && url.startsWith(KEY_PREFIX);
    }

    /** Registra (ou troca) o áudio de uma chave; a estação antiga da chave, se havia, fecha. */
    void register(String key, MediaLocator locator) {
        if (!isKey(key)) throw new IllegalArgumentException("chave inválida: " + key);
        Station old = byUrl.remove(key);
        if (old != null) close(old);
        keyed.put(key, locator);
    }

    /** Estação da URL, criando se cabe no limite do config. Null se não cabe (ou chave sem registro). */
    Station acquire(String url, long nowMs) {
        Station s = byUrl.get(url);
        if (s == null) {
            if (byUrl.size() >= Math.max(1, FmConfig.Relay.maxStations)) return null;
            if (isKey(url)) {
                MediaLocator locator = keyed.get(url);
                if (locator == null) return null;
                s = new Station(nextId++, url, locator, UrlPolicy.resolvedMedia(), FmConfig.Relay.opusBitrateKbps)
                    .start();
            } else {
                s = new Station(nextId++, url, ServerPolicy.urlPolicy(), FmConfig.Relay.opusBitrateKbps).start();
            }
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

    /** Fecha a estação da chave e esquece o registro. */
    void unregister(String key) {
        Station s = byUrl.remove(key);
        if (s != null) close(s);
        keyed.remove(key);
    }

    boolean registered(String key) {
        return keyed.containsKey(key);
    }

    private void close(Station s) {
        s.close();
        byId.remove(s.id);
        if (isKey(s.url)) keyed.remove(s.url);
        AkashicFM.LOG.info("Relay: estação {} fechada ({})", s.id, s.url);
    }

    void closeAll() {
        for (Station s : byUrl.values()) s.close();
        byUrl.clear();
        byId.clear();
        keyed.clear();
    }
}
