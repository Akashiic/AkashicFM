package com.akashiic.fm.server.ipod;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.common.TextSanitizer;

/**
 * Do que o jogador colou até a URL do áudio. Bloqueia (só nas threads do iPod):
 * <ul>
 * <li>{@link #expand}: o link (ou a busca) vira faixas, só com metadados: SoundCloud e YouTube pelo yt-dlp (modo
 * rápido, sem resolver cada item), Spotify pela página do player embutido, texto livre pela busca do SoundCloud;</li>
 * <li>{@link #locate}: na hora de tocar, a URL direta do áudio (as dos CDNs expiram em minutos). O SoundCloud toca
 * direto; YouTube e Spotify tocam a mesma música achada no SoundCloud ({@link MirrorMatcher}), testando os melhores
 * candidatos (faixas com DRM são puladas, nunca contornadas). Com {@code youtubeDirect}, tenta o YouTube antes;</li>
 * <li>{@link #refresh}: URL nova para a mesma faixa, quando a anterior expirou no meio.</li>
 * </ul>
 * O espelho escolhido fica em cache (a busca custa ~4 s), e um "não achado" também, por {@link #NOT_FOUND_TTL_MS}.
 */
public final class MediaResolver {

    /** Roda o yt-dlp com estes argumentos (depois dos comuns). Trocado nos testes. */
    interface Runner {

        YtDlp.Result run(List<String> args, long timeoutMs, int maxOutBytes) throws IOException, ResolveException;
    }

    /** Lê um link do Spotify. Trocado nos testes. */
    interface Spotify {

        YtDlpJson.Listing fetch(SpotifyClient.Ref ref, int maxItems) throws IOException;
    }

    /** Falha com motivo para a tela: {@code akashicfm.ipod.err.<key>}, com argumento opcional. */
    public static final class ResolveException extends Exception {

        public final String key;
        public final String arg;

        public ResolveException(String key, String arg) {
            super(arg == null || arg.isEmpty() ? key : key + ": " + arg, null, false, false);
            this.key = key;
            // O argumento pode ser texto do yt-dlp (de fora): vai para a tela já limpo e curto.
            this.arg = TextSanitizer.clean(arg, 80)
                .replace('|', '/');
        }

        /** Não adianta tentar outro candidato: a ferramenta falta ou a resolução foi cancelada. */
        boolean fatal() {
            return key.equals("tools") || key.equals("cancelled");
        }

        /** Falha passageira (rede, tempo): não vira "não achado" no cache. */
        boolean transientFailure() {
            return key.equals("failed") || key.equals("timeout");
        }

        /** No formato de status das rádios: chave de tradução e argumento depois de '|'. */
        public String status() {
            return "akashicfm.ipod.err." + key + (arg.isEmpty() ? "" : "|" + arg);
        }
    }

    /** O que tocar. */
    public static final class Located {

        public final String mediaUrl;
        /** A faixa da fila, com o que faltava preenchido (título de item de set, duração). */
        public final IPodTrack track;
        /** A faixa do SoundCloud que toca no lugar (null se toca o próprio link). */
        public final IPodTrack mirror;
        /** Página e formato para renovar a URL. */
        final String page;
        final String format;

        Located(String mediaUrl, IPodTrack track, IPodTrack mirror, String page, String format) {
            this.mediaUrl = mediaUrl;
            this.track = track;
            this.mirror = mirror;
            this.page = page;
            this.format = format;
        }
    }

    static final long INFO_TIMEOUT_MS = 90_000;
    static final long SEARCH_TIMEOUT_MS = 45_000;
    static final long MEDIA_TIMEOUT_MS = 45_000;
    /** Com o YouTube direto o Deno resolve o desafio JS, bem mais lento. */
    static final long DIRECT_TIMEOUT_MS = 120_000;
    static final int INFO_MAX_OUT = 8 << 20;
    static final int MEDIA_MAX_OUT = 2 << 20;
    static final int SEARCH_RESULTS = 8;
    /**
     * Candidatos do espelho testados por busca (cada teste é uma chamada ao yt-dlp, ~3 s). Os uploads oficiais de
     * gravadora costumam ter DRM e gastam tentativas.
     */
    static final int MAX_MIRROR_TRIES = 4;
    static final long NOT_FOUND_TTL_MS = 10 * 60_000L;
    static final long FOUND_TTL_MS = 24 * 3600_000L;
    static final int CACHE_SIZE = 512;

    private final Runner runner;
    private final Spotify spotify;
    private final Map<String, CacheEntry> mirrors = new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private static final class CacheEntry {

        /** A faixa do espelho, ou null para "não achado". */
        final IPodTrack mirror;
        final long atMs;

        CacheEntry(IPodTrack mirror, long atMs) {
            this.mirror = mirror;
            this.atMs = atMs;
        }
    }

    MediaResolver(Runner runner, Spotify spotify) {
        this.runner = runner;
        this.spotify = spotify;
    }

    /** O resolvedor de verdade: o yt-dlp do {@link ToolManager}, no máximo {@code ipod.maxResolves} por vez. */
    public static MediaResolver create(ToolManager tools) {
        return new MediaResolver(new ProcessRunner(tools), SpotifyClient::fetch);
    }

    // ---- Expandir ----

    /** O link ou a busca vira faixas (no máximo {@code maxItems}). Nunca devolve lista vazia. */
    public YtDlpJson.Listing expand(String input, int maxItems) throws ResolveException {
        String s = input == null ? "" : input.trim();
        YtDlp.Kind kind = YtDlp.classify(s);
        YtDlpJson.Listing listing;
        switch (kind) {
            case SOUNDCLOUD:
            case YOUTUBE:
                listing = info(s, maxItems);
                break;
            case SPOTIFY:
                listing = spotify(s, maxItems);
                break;
            case SEARCH:
                listing = search(s);
                break;
            default:
                throw new ResolveException("invalid", "");
        }
        return withinLimits(listing);
    }

    private YtDlpJson.Listing info(String link, int maxItems) throws ResolveException {
        YtDlp.Result r = run(YtDlp.infoArgs(link, Math.max(1, maxItems)), INFO_TIMEOUT_MS, INFO_MAX_OUT);
        try {
            return YtDlpJson.listing(r.out, maxItems);
        } catch (IOException e) {
            throw new ResolveException("failed", e.getMessage());
        }
    }

    private YtDlpJson.Listing spotify(String link, int maxItems) throws ResolveException {
        if (!FmConfig.IPod.spotify) throw new ResolveException("spotify_off", "");
        SpotifyClient.Ref ref = SpotifyClient.parseLink(link);
        if (ref == null) throw new ResolveException("invalid", "");
        try {
            return spotify.fetch(ref, maxItems);
        } catch (SpotifyClient.NotFoundException e) {
            throw new ResolveException("spotify_notfound", "");
        } catch (IOException e) {
            throw new ResolveException("failed", e.getMessage());
        }
    }

    /** Texto livre: o melhor resultado do SoundCloud que não é prévia. */
    private YtDlpJson.Listing search(String text) throws ResolveException {
        for (IPodTrack t : searchSoundCloud(text)) {
            if (t.durationSec > 0 && t.durationSec <= 31) continue; // prévia de faixa paga
            List<IPodTrack> one = new ArrayList<>(1);
            one.add(t);
            return new YtDlpJson.Listing("", false, one, 0);
        }
        throw new ResolveException("notfound", "");
    }

    private List<IPodTrack> searchSoundCloud(String query) throws ResolveException {
        YtDlp.Result r = run(
            YtDlp.infoArgs(YtDlp.soundcloudSearch(query, SEARCH_RESULTS), SEARCH_RESULTS),
            SEARCH_TIMEOUT_MS,
            INFO_MAX_OUT);
        try {
            return YtDlpJson.listing(r.out, SEARCH_RESULTS).tracks;
        } catch (IOException e) {
            throw new ResolveException("failed", e.getMessage());
        }
    }

    /** Tira as faixas longas demais; sem nenhuma que toque, falha com o motivo. */
    private static YtDlpJson.Listing withinLimits(YtDlpJson.Listing l) throws ResolveException {
        int max = maxTrackSec();
        List<IPodTrack> ok = new ArrayList<>(l.tracks.size());
        int tooLong = 0;
        for (IPodTrack t : l.tracks) {
            if (t.durationSec > max) tooLong++;
            else ok.add(t);
        }
        if (ok.isEmpty()) {
            if (tooLong > 0) throw new ResolveException("toolong", String.valueOf(FmConfig.IPod.maxTrackMinutes));
            throw new ResolveException("empty", "");
        }
        return new YtDlpJson.Listing(l.title, l.playlist, ok, l.skipped + tooLong);
    }

    private static int maxTrackSec() {
        return Math.max(1, FmConfig.IPod.maxTrackMinutes) * 60;
    }

    // ---- Tocar ----

    /** A URL do áudio da faixa, agora. */
    public Located locate(IPodTrack t) throws ResolveException {
        switch (t.source) {
            case SOUNDCLOUD: {
                YtDlpJson.Media m = media(t.link, YtDlp.SOUNDCLOUD_FORMAT, MEDIA_TIMEOUT_MS);
                IPodTrack filled = new IPodTrack(t.source, t.link, m.track.title, m.track.artist, m.track.durationSec);
                checkLength(filled);
                return new Located(m.url, filled, null, t.link, YtDlp.SOUNDCLOUD_FORMAT);
            }
            case YOUTUBE:
                if (FmConfig.IPod.youtubeDirect) {
                    try {
                        YtDlpJson.Media m = media(t.link, YtDlp.YOUTUBE_FORMAT, DIRECT_TIMEOUT_MS);
                        checkLength(m.track);
                        return new Located(m.url, t, null, t.link, YtDlp.YOUTUBE_FORMAT);
                    } catch (ResolveException e) {
                        if (e.fatal() || e.key.equals("toolong")) throw e;
                        AkashicFM.LOG.debug("iPod: YouTube direto falhou ({}), indo para o espelho", e.getMessage());
                    }
                }
                return mirror(t, MirrorMatcher.fromYouTube(t));
            case SPOTIFY:
                return mirror(t, MirrorMatcher.fromSpotify(t));
            default:
                throw new ResolveException("invalid", "");
        }
    }

    /** URL nova para o que {@link #locate} achou (a anterior expirou). */
    public String refresh(Located l) throws IOException {
        try {
            return media(
                l.page,
                l.format,
                l.format.equals(YtDlp.YOUTUBE_FORMAT) ? DIRECT_TIMEOUT_MS : MEDIA_TIMEOUT_MS).url;
        } catch (ResolveException e) {
            throw new IOException(e.getMessage());
        }
    }

    private Located mirror(IPodTrack t, MirrorMatcher.Wanted w) throws ResolveException {
        Set<String> tried = new HashSet<>();
        CacheEntry cached = cached(t.link);
        if (cached != null) {
            if (cached.mirror == null) throw new ResolveException("notfound", "");
            try {
                return playMirror(t, cached.mirror);
            } catch (ResolveException e) {
                if (e.fatal()) throw e;
                forget(t.link); // o espelho sumiu ou mudou: procura de novo, sem ele
                tried.add(cached.mirror.link);
            }
        }
        boolean drm = false;
        ResolveException transientFailure = null;
        // Músicas de gravadora: os uploads oficiais costumam ter DRM e os outros aparecem melhor na segunda busca.
        for (String query : new String[] { w.query, w.fallbackQuery }) {
            if (query == null || query.isEmpty()) continue;
            int tries = 0;
            for (IPodTrack candidate : MirrorMatcher.rank(w, searchSoundCloud(query))) {
                if (tried.contains(candidate.link)) continue;
                if (tries++ >= MAX_MIRROR_TRIES) break;
                tried.add(candidate.link);
                try {
                    Located l = playMirror(t, candidate);
                    remember(t.link, candidate);
                    AkashicFM.LOG.debug("iPod: espelho de {} -> {}", t.link, candidate.link);
                    return l;
                } catch (ResolveException e) {
                    if (e.fatal()) throw e;
                    if (e.key.equals("drm")) drm = true;
                    if (e.transientFailure()) transientFailure = e;
                }
            }
        }
        // Rede ou tempo no meio: o motivo de verdade, sem guardar "não achado" (a próxima tentativa pode achar).
        if (transientFailure != null && !drm) throw transientFailure;
        remember(t.link, null);
        throw new ResolveException(drm ? "drm" : "notfound", "");
    }

    private Located playMirror(IPodTrack t, IPodTrack candidate) throws ResolveException {
        YtDlpJson.Media m = media(candidate.link, YtDlp.SOUNDCLOUD_FORMAT, MEDIA_TIMEOUT_MS);
        checkLength(m.track);
        IPodTrack filled = t.durationSec > 0 ? t
            : new IPodTrack(t.source, t.link, t.title, t.artist, m.track.durationSec);
        return new Located(m.url, filled, m.track, candidate.link, YtDlp.SOUNDCLOUD_FORMAT);
    }

    private static void checkLength(IPodTrack t) throws ResolveException {
        if (t.durationSec > maxTrackSec()) {
            throw new ResolveException("toolong", String.valueOf(FmConfig.IPod.maxTrackMinutes));
        }
    }

    private YtDlpJson.Media media(String page, String format, long timeoutMs) throws ResolveException {
        YtDlp.Result r = run(YtDlp.mediaArgs(page, format), timeoutMs, MEDIA_MAX_OUT);
        try {
            return YtDlpJson.media(r.out);
        } catch (IOException e) {
            throw new ResolveException("failed", e.getMessage());
        }
    }

    // ---- yt-dlp ----

    private YtDlp.Result run(List<String> args, long timeoutMs, int maxOut) throws ResolveException {
        // Resolução cancelada (o iPod parou ou trocou de faixa): nenhum processo novo.
        if (Thread.currentThread()
            .isInterrupted()) throw new ResolveException("cancelled", "");
        YtDlp.Result r;
        try {
            r = runner.run(args, timeoutMs, maxOut);
        } catch (IOException e) {
            if (Thread.currentThread()
                .isInterrupted()) throw new ResolveException("cancelled", "");
            throw new ResolveException("failed", e.getMessage());
        }
        if (r.ok()) return r;
        if (r.timedOut) throw new ResolveException("timeout", "");
        if (r.drm()) throw new ResolveException("drm", "");
        String error = r.error();
        if (error.contains("Requested format is not available")) throw new ResolveException("format", "");
        if (error.contains("Unsupported URL")) throw new ResolveException("invalid", "");
        throw new ResolveException("failed", shorten(error));
    }

    /** Sem o prefixo "[extrator] id:" e curto (vai para a tela). */
    static String shorten(String error) {
        String e = error.replaceFirst("^\\[[^\\]]*\\]\\s*[^:\\s]*:\\s*", "");
        return e.length() > 80 ? e.substring(0, 80) : e;
    }

    // ---- Cache do espelho ----

    private synchronized CacheEntry cached(String link) {
        CacheEntry e = mirrors.get(link);
        if (e == null) return null;
        long age = System.currentTimeMillis() - e.atMs;
        if (age > (e.mirror == null ? NOT_FOUND_TTL_MS : FOUND_TTL_MS)) {
            mirrors.remove(link);
            return null;
        }
        return e;
    }

    private synchronized void remember(String link, IPodTrack mirror) {
        mirrors.put(link, new CacheEntry(mirror, System.currentTimeMillis()));
    }

    private synchronized void forget(String link) {
        mirrors.remove(link);
    }

    /** O yt-dlp de verdade, com o limite de processos simultâneos do config. */
    private static final class ProcessRunner implements Runner {

        private final ToolManager tools;
        private Semaphore gate;
        private int gatePermits;

        ProcessRunner(ToolManager tools) {
            this.tools = tools;
        }

        /** O limite pode mudar com o reload: um semáforo novo (quem segura o antigo devolve nele mesmo). */
        private synchronized Semaphore gate() {
            int permits = Math.max(1, Math.min(8, FmConfig.IPod.maxResolves));
            if (gate == null || permits != gatePermits) {
                gate = new Semaphore(permits, true);
                gatePermits = permits;
            }
            return gate;
        }

        @Override
        public YtDlp.Result run(List<String> args, long timeoutMs, int maxOutBytes)
            throws IOException, ResolveException {
            File exe = tools.ytDlp();
            if (exe == null) {
                throw new ResolveException("tools", tools.state() == ToolManager.State.FAILED ? tools.detail() : "");
            }
            File deno = FmConfig.IPod.youtubeDirect ? tools.deno() : null;
            List<String> cmd = YtDlp
                .base(exe, new File(tools.dir(), "cache"), deno, System.getenv("SSL_CERT_FILE") != null);
            cmd.addAll(args);
            Semaphore g = gate();
            try {
                g.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                throw new IOException("interrompido");
            }
            try {
                return YtDlp.run(cmd, tools.tmpDir(), timeoutMs, maxOutBytes);
            } finally {
                g.release();
            }
        }
    }
}
