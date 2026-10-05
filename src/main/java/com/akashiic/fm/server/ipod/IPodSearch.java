package com.akashiic.fm.server.ipod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CIPodSearchResults;
import com.akashiic.fm.server.AuditLog;
import com.akashiic.fm.server.relay.RelayService;

/**
 * As buscas da tela do iPod (a lista de resultados por serviço). Thread principal; a busca em si (yt-dlp ou a API do
 * Spotify) vai para o pool do {@link IPodService}. O servidor guarda os últimos resultados de cada jogador: para
 * escolher, o cliente manda só o número do pedido e o índice, nunca título nem link.
 * <p>
 * Limites: uma busca por vez por jogador, {@link #COOLDOWN_MS} entre buscas, e no máximo {@link #MAX_IN_FLIGHT} no
 * servidor inteiro (elas dividem os processos do yt-dlp com as faixas tocando).
 */
final class IPodSearch {

    static final long COOLDOWN_MS = 2_000;
    static final long RESULTS_TTL_MS = 10 * 60_000L;
    static final int MAX_IN_FLIGHT = 2;

    private static final class Pending {

        final UUID player;
        final int requestId;
        final IPodTrack.Source source;
        final Future<List<IPodTrack>> future;

        Pending(UUID player, int requestId, IPodTrack.Source source, Future<List<IPodTrack>> future) {
            this.player = player;
            this.requestId = requestId;
            this.source = source;
            this.future = future;
        }
    }

    private static final class Results {

        final int requestId;
        final List<IPodTrack> tracks;
        final long atMs;

        Results(int requestId, List<IPodTrack> tracks, long atMs) {
            this.requestId = requestId;
            this.tracks = tracks;
            this.atMs = atMs;
        }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Results> LAST = new HashMap<>();
    private static final Map<UUID, Long> LAST_START = new HashMap<>();

    private IPodSearch() {}

    /** O que a tela pode oferecer neste servidor. */
    static int flags() {
        int f = 0;
        if (FmConfig.IPod.enabled) f |= S2CIPodSearchResults.FLAG_ENABLED;
        if (FmConfig.IPod.spotify) f |= S2CIPodSearchResults.FLAG_SPOTIFY_LINKS;
        if (SpotifySearch.available()) f |= S2CIPodSearchResults.FLAG_SPOTIFY_SEARCH;
        return f;
    }

    /** A tela abriu: manda o que ela pode oferecer. */
    static void hello(EntityPlayerMP p) {
        reply(p, 0, null, "");
    }

    /** Uma resposta sem faixas (o motivo vai em {@code status}). */
    static void reply(EntityPlayerMP p, int requestId, IPodTrack.Source source, String status) {
        if (p == null) return;
        FmNetwork.sendTo(new S2CIPodSearchResults(requestId, source, flags(), status, Collections.emptyList()), p);
    }

    /**
     * Começa a busca de {@code query} (texto, nunca link) em {@code source}. Devolve null se começou; senão a chave do
     * motivo (para a lista de resultados).
     */
    static String start(EntityPlayerMP p, int requestId, IPodTrack.Source source, String query) {
        if (!FmConfig.IPod.enabled) return IPodService.STATUS_DISABLED;
        if (source == null || YtDlp.classify(query) != YtDlp.Kind.SEARCH) return "akashicfm.ipod.err.invalid";
        UUID id = p.getUniqueID();
        Pending pending = PENDING.get(id);
        if (pending != null && !pending.future.isDone()) return "akashicfm.ipod.notice.searching";
        long now = RelayService.nowMs();
        Long last = LAST_START.get(id);
        if (last != null && now - last < COOLDOWN_MS) return "akashicfm.ipod.notice.search_wait";
        String tool = IPodService.toolsRefusal(); // também cria o resolvedor, que a busca do Spotify usa
        if (source == IPodTrack.Source.SPOTIFY) {
            if (!FmConfig.IPod.spotify) return "akashicfm.ipod.err.spotify_off";
            if (!SpotifySearch.available()) return "akashicfm.ipod.err.spotify_nokey";
        } else if (tool != null) {
            return tool;
        }
        if (inFlight() >= MAX_IN_FLIGHT) return IPodService.STATUS_BUSY;
        MediaResolver resolver = IPodService.resolver();
        if (resolver == null) return IPodService.STATUS_BUSY;
        Future<List<IPodTrack>> f = IPodService
            .submit(() -> resolver.searchTracks(source, query, MediaResolver.LIST_RESULTS));
        if (f == null) return IPodService.STATUS_BUSY;
        PENDING.put(id, new Pending(id, requestId, source, f));
        LAST_START.put(id, now);
        AuditLog.log(p.getCommandSenderName(), id, "ipod.search", source.name() + ": " + query);
        return null;
    }

    private static int inFlight() {
        int n = 0;
        for (Pending x : PENDING.values()) if (!x.future.isDone()) n++;
        return n;
    }

    /** No tick do serviço: as buscas que terminaram vão para quem buscou. */
    static void finish() {
        long now = RelayService.nowMs();
        LAST.values()
            .removeIf(r -> now - r.atMs > RESULTS_TTL_MS);
        LAST_START.values()
            .removeIf(t -> now - t > COOLDOWN_MS);
        if (PENDING.isEmpty()) return;
        Map<UUID, EntityPlayerMP> online = new HashMap<>();
        for (EntityPlayerMP p : IPodService.onlinePlayers()) online.put(p.getUniqueID(), p);
        Iterator<Pending> it = PENDING.values()
            .iterator();
        while (it.hasNext()) {
            Pending x = it.next();
            if (!x.future.isDone()) continue;
            it.remove();
            EntityPlayerMP p = online.get(x.player);
            if (p == null) continue;
            List<IPodTrack> tracks;
            try {
                tracks = x.future.get();
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                reply(
                    p,
                    x.requestId,
                    x.source,
                    c instanceof MediaResolver.ResolveException ? ((MediaResolver.ResolveException) c).status()
                        : "akashicfm.ipod.err.failed|" + c);
                continue;
            } catch (Exception e) {
                continue; // cancelada (iPod desligado no config, servidor parando)
            }
            List<IPodTrack> kept = new ArrayList<>(Math.min(tracks.size(), S2CIPodSearchResults.MAX_RESULTS));
            List<S2CIPodSearchResults.Entry> shown = new ArrayList<>(kept.size());
            for (IPodTrack t : tracks) {
                if (kept.size() >= S2CIPodSearchResults.MAX_RESULTS) break;
                kept.add(t);
                shown.add(new S2CIPodSearchResults.Entry(t.title, t.artist, t.durationSec));
            }
            LAST.put(x.player, new Results(x.requestId, Collections.unmodifiableList(kept), now));
            FmNetwork.sendTo(new S2CIPodSearchResults(x.requestId, x.source, flags(), "", shown), p);
        }
    }

    /** A faixa {@code index} da última busca do jogador, se ela é a do pedido {@code requestId}; senão null. */
    static IPodTrack result(UUID player, int requestId, int index) {
        Results r = player == null ? null : LAST.get(player);
        if (r == null || r.requestId != requestId || index < 0 || index >= r.tracks.size()) return null;
        if (RelayService.nowMs() - r.atMs > RESULTS_TTL_MS) return null;
        return r.tracks.get(index);
    }

    static void forget(UUID player) {
        Pending x = PENDING.remove(player);
        if (x != null) x.future.cancel(true);
        LAST.remove(player);
        LAST_START.remove(player);
    }

    static void clear() {
        for (Pending x : PENDING.values()) x.future.cancel(true);
        PENDING.clear();
        LAST.clear();
        LAST_START.clear();
    }
}
