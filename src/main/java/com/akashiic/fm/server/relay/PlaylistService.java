package com.akashiic.fm.server.relay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.stream.StreamPump;
import com.akashiic.fm.common.Playlist;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.server.RadioActionHandler;
import com.akashiic.fm.server.ServerPolicy;

/**
 * Playlist das favoritas: quando a estação de uma rádio com playlist termina (arquivo) ou falha, a rádio passa para a
 * próxima favorita, em loop. Chamado pelo {@link RelayService} a cada atualização de status, só para rádios no relay
 * (no modo direto o servidor não sabe quando o arquivo acaba). Thread principal.
 */
final class PlaylistService {

    /** Folga depois do último áudio soar nos clientes antes de trocar (a latência já está somada). */
    static final long END_MARGIN_MS = 250;

    /** O que a playlist lembra de cada rádio entre ciclos. */
    private static final class Track {

        long lastChangeMs;
        int failures;
    }

    private static final Map<String, Track> TRACKS = new HashMap<>();
    private static final Set<String> SEEN = new HashSet<>();

    private PlaylistService() {}

    private static String key(TileRadio r) {
        return r.dimension() + "@" + r.pos();
    }

    /** Começo de uma atualização de status (para esquecer as rádios que pararam ou sumiram). */
    static void beginCycle() {
        SEEN.clear();
    }

    /**
     * A rádio está tocando pelo relay neste ciclo (com ou sem estação aberta ainda: logo depois de uma troca, a
     * estação nova só abre na próxima atualização da audiência, e as falhas seguidas não podem ser esquecidas aí).
     */
    static void keep(TileRadio r) {
        if (r.state.playlist) SEEN.add(key(r));
    }

    /** Fim da atualização: só ficam as rádios vistas neste ciclo (as que pararam ou sumiram são esquecidas). */
    static void endCycle() {
        TRACKS.keySet()
            .retainAll(SEEN);
    }

    /**
     * Esconde o status "terminou" enquanto a playlist vai trocar de faixa (o fim ainda está soando e a próxima vem).
     */
    static boolean willAdvance(TileRadio r, Station s) {
        RadioState st = r.state;
        return st.playlist && st.mode == TuneMode.URL
            && !st.stations.isEmpty()
            && s.status() == StreamPump.Status.ENDED
            && s.ring.lastSeq() >= 0;
    }

    /**
     * Aplica a playlist à rádio, que toca a estação {@code s}. Devolve true se mudou o estado da rádio (quem chama
     * marca a mudança).
     */
    static boolean update(TileRadio r, Station s, long nowMs, int latencyMs) {
        RadioState st = r.state;
        if (!st.playlist || st.mode != TuneMode.URL || st.stations.isEmpty()) return false;
        String key = key(r);
        StreamPump.Status status = s.status();
        // Fim de arquivo sem nenhum áudio (URL que responde vazio) conta como falha: não vira um loop de 5 s.
        boolean hadAudio = s.ring.lastSeq() >= 0;
        boolean ended = status == StreamPump.Status.ENDED && hadAudio;
        boolean failed = status == StreamPump.Status.ERROR || (status == StreamPump.Status.ENDED && !hadAudio);
        Track t = TRACKS.computeIfAbsent(key, k -> new Track());
        if (!ended && !failed) {
            if (status == StreamPump.Status.PLAYING) t.failures = 0; // tocou: as falhas seguidas acabaram
            return false;
        }
        long endHeardAtMs = s.ring.endPtsMs() + latencyMs + END_MARGIN_MS;
        Playlist.Decision d = Playlist
            .decide(ended, failed, endHeardAtMs, nowMs, t.lastChangeMs, t.failures, st.stations.size());
        if (d == Playlist.Decision.WAIT) return false;
        String next = d == Playlist.Decision.ADVANCE ? allowedAfter(st) : null;
        if (next == null) {
            // Todas falharam em seguida (ou nenhuma passa mais pela política): para, com o motivo na tela.
            String why = RelayService.statusFor(s);
            RadioActionHandler.applyStop(st);
            st.status = why;
            TRACKS.remove(key);
            AkashicFM.LOG.debug("Playlist: rádio em dim {} {} parou ({})", r.dimension(), r.pos(), why);
            return true;
        }
        t.failures = failed ? t.failures + 1 : 0;
        t.lastChangeMs = nowMs;
        String old = st.url;
        RadioActionHandler.applyPlaylistUrl(r, next);
        RelayService.releaseIfUnused(old);
        return true;
    }

    /** A próxima favorita que a política deixa tocar (a allowlist pode ter mudado), ou null se nenhuma. */
    private static String allowedAfter(RadioState st) {
        String url = st.url;
        for (int i = 0; i < st.stations.size(); i++) {
            url = Playlist.next(st.stations, url);
            if (url != null && ServerPolicy.rejection(url) == null) return url;
        }
        return null;
    }

    /** Servidor parando. */
    static void clear() {
        TRACKS.clear();
        SEEN.clear();
    }
}
