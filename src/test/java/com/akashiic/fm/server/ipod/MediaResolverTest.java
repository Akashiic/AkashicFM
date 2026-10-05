package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodTrack;

/** O fluxo inteiro com um yt-dlp falso que responde com a saída gravada do de verdade. */
class MediaResolverTest {

    /** yt-dlp falso: lembra cada chamada e responde pelo alvo (o último argumento, depois do "--"). */
    private static final class FakeYtDlp implements MediaResolver.Runner {

        final List<List<String>> calls = new ArrayList<>();
        final Map<String, String> info = new HashMap<>();
        final Map<String, Integer> durations = new HashMap<>();
        final Set<String> drm = new HashSet<>();
        String searchJson;
        String ytSearchJson;
        YtDlp.Result next; // força um resultado (erro, timeout)
        MediaResolver.ResolveException fail;

        @Override
        public YtDlp.Result run(List<String> args, long timeoutMs, int maxOutBytes)
            throws MediaResolver.ResolveException {
            calls.add(args);
            if (fail != null) throw fail;
            if (next != null) return next;
            assertEquals("--", args.get(args.size() - 2));
            String target = args.get(args.size() - 1);
            if (args.get(0)
                .equals("-J")) {
                if (target.startsWith("scsearch")) return ok(searchJson);
                if (target.startsWith("ytsearch")) return ok(ytSearchJson);
                String json = info.get(target);
                return json != null ? ok(json)
                    : new YtDlp.Result(1, "", "ERROR: [generic] Unsupported URL: " + target, false);
            }
            assertEquals("-j", args.get(0));
            if (drm.contains(target))
                return new YtDlp.Result(1, "", "ERROR: [soundcloud] 1: This video is DRM protected", false);
            int dur = durations.containsKey(target) ? durations.get(target) : 200;
            String key = target.contains("youtube.com") ? "Youtube" : "Soundcloud";
            String ext = key.equals("Youtube") ? "webm" : "mp3";
            return ok(
                "{\"extractor_key\":\"" + key
                    + "\",\"id\":\"jNQXAC9IVRw\",\"webpage_url\":\""
                    + target
                    + "\",\"title\":\"Título de "
                    + target.substring(target.lastIndexOf('/') + 1)
                    + "\",\"uploader\":\"Up\",\"duration\":"
                    + dur
                    + ",\"url\":\"https://cdn.example.com/a."
                    + ext
                    + "?sig="
                    + calls.size()
                    + "\"}");
        }

        int count(String first) {
            int n = 0;
            for (List<String> c : calls) if (c.get(0)
                .equals(first)) n++;
            return n;
        }
    }

    private static YtDlp.Result ok(String out) {
        return new YtDlp.Result(0, out, "", false);
    }

    private final FakeYtDlp yt = new FakeYtDlp();
    private YtDlpJson.Listing spotifyAnswer;
    private MediaResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        FmConfig.IPod.maxTrackMinutes = 20;
        FmConfig.IPod.spotify = true;
        FmConfig.IPod.youtubeDirect = false;
        yt.searchJson = YtDlpJsonTest.fixture("sc-search.json");
        yt.ytSearchJson = YtDlpJsonTest.fixture("yt-search.json");
        resolver = new MediaResolver(yt, (ref, max) -> {
            if (spotifyAnswer == null) throw new SpotifyClient.NotFoundException();
            return spotifyAnswer;
        });
    }

    @AfterEach
    void tearDown() {
        FmConfig.IPod.maxTrackMinutes = 0;
        FmConfig.IPod.spotify = false;
        FmConfig.IPod.youtubeDirect = false;
    }

    // ---- Buscar (a lista de resultados) ----

    private String lastTarget() {
        List<String> c = yt.calls.get(yt.calls.size() - 1);
        return c.get(c.size() - 1);
    }

    @Test
    void buscaDoSoundCloudSemAsPrevias() throws Exception {
        // sc-search.json (gravado): 5 faixas, duas são prévias de 30 s de faixas pagas.
        List<IPodTrack> r = resolver.searchTracks(IPodTrack.Source.SOUNDCLOUD, " daft punk get lucky ", 10);
        assertEquals(3, r.size());
        for (IPodTrack t : r) {
            assertEquals(IPodTrack.Source.SOUNDCLOUD, t.source);
            assertTrue(t.durationSec > MediaResolver.PREVIEW_MAX_SEC, t.toString());
        }
        assertEquals("scsearch10:daft punk get lucky", lastTarget());
        List<String> call = yt.calls.get(0);
        assertEquals("10", call.get(call.indexOf("--playlist-end") + 1));
        // Faixa curta de verdade (não é o corte de 30 s) continua.
        yt.searchJson = "{\"_type\":\"playlist\",\"entries\":[{\"_type\":\"url\",\"ie_key\":\"Soundcloud\","
            + "\"webpage_url\":\"https://soundcloud.com/a/vinheta\",\"title\":\"Vinheta\",\"duration\":12.0},"
            + "{\"_type\":\"url\",\"ie_key\":\"Soundcloud\",\"webpage_url\":\"https://soundcloud.com/a/paga\","
            + "\"title\":\"Paga\",\"duration\":30.0}]}";
        List<IPodTrack> shortOnes = resolver.searchTracks(IPodTrack.Source.SOUNDCLOUD, "vinheta", 10);
        assertEquals(1, shortOnes.size());
        assertEquals("Vinheta", shortOnes.get(0).title);
    }

    @Test
    void buscaDoYouTubeTiraAsLongasDemais() throws Exception {
        // yt-search.json (gravado com o yt-dlp de verdade, ytsearch10): 10 vídeos, um de 1 hora.
        List<IPodTrack> r = resolver.searchTracks(IPodTrack.Source.YOUTUBE, "daft punk get lucky", 10);
        assertEquals(9, r.size());
        IPodTrack first = r.get(0);
        assertEquals(IPodTrack.Source.YOUTUBE, first.source);
        assertEquals("https://www.youtube.com/watch?v=5NV6Rdv1a3I", first.link);
        assertEquals(249, first.durationSec);
        assertTrue(first.title.startsWith("Daft Punk - Get Lucky"));
        assertFalse(first.artist.isEmpty());
        for (IPodTrack t : r) assertTrue(t.durationSec <= 20 * 60, t.toString());
        assertEquals("ytsearch10:daft punk get lucky", lastTarget());
    }

    @Test
    void buscaLimitadaADez() throws Exception {
        resolver.searchTracks(IPodTrack.Source.YOUTUBE, "x", 50);
        assertEquals("ytsearch10:x", lastTarget());
        resolver.searchTracks(IPodTrack.Source.SOUNDCLOUD, "y", 0);
        assertEquals("scsearch1:y", lastTarget());
    }

    @Test
    void buscaSoComTextoNuncaComLinkOuPrefixo() {
        for (String bad : new String[] { "", "   ", "https://soundcloud.com/a/b", "https://evil.example/x",
            "spotify:track:4cOdK2wGLETKBW3PvgPWqT" }) {
            MediaResolver.ResolveException e = assertThrows(
                MediaResolver.ResolveException.class,
                () -> resolver.searchTracks(IPodTrack.Source.YOUTUBE, bad, 10),
                bad);
            assertEquals("invalid", e.key);
        }
        assertTrue(yt.calls.isEmpty());
    }

    @Test
    void prefixoDigitadoViraTextoBuscado() throws Exception {
        // "ytsearch5:x" digitado é só texto: vai depois do prefixo do servidor, nunca vira outro extrator.
        resolver.searchTracks(IPodTrack.Source.SOUNDCLOUD, "ytsearch5:x", 10);
        assertEquals("scsearch10:ytsearch5:x", lastTarget());
    }

    @Test
    void buscaDoSpotifyPelaChaveFiltraERemoveRepetidas() throws Exception {
        List<IPodTrack> answer = new ArrayList<>();
        answer.add(
            new IPodTrack(
                IPodTrack.Source.SPOTIFY,
                SpotifyClient.trackLink("4cOdK2wGLETKBW3PvgPWqT"),
                "Never Gonna Give You Up",
                "Rick Astley",
                213));
        answer.add(
            new IPodTrack(
                IPodTrack.Source.SPOTIFY,
                SpotifyClient.trackLink("4cOdK2wGLETKBW3PvgPWqT"),
                "Never Gonna Give You Up",
                "Rick Astley",
                213));
        answer.add(
            new IPodTrack(
                IPodTrack.Source.SPOTIFY,
                SpotifyClient.trackLink("0000000000000000000001"),
                "Mix de 2 horas",
                "DJ",
                7200));
        MediaResolver r = new MediaResolver(yt, (ref, max) -> null, (q, max) -> {
            assertEquals("rick astley", q);
            assertEquals(10, max);
            return answer;
        });
        List<IPodTrack> found = r.searchTracks(IPodTrack.Source.SPOTIFY, "rick astley", 10);
        assertEquals(1, found.size());
        assertEquals("Rick Astley", found.get(0).artist);
        assertTrue(yt.calls.isEmpty());
    }

    @Test
    void buscaDoSpotifySemChave() {
        MediaResolver.ResolveException e = assertThrows(
            MediaResolver.ResolveException.class,
            () -> resolver.searchTracks(IPodTrack.Source.SPOTIFY, "rick astley", 10));
        assertEquals("spotify_nokey", e.key);
    }

    // ---- Expandir ----

    @Test
    void linkDoSoundCloud() throws Exception {
        yt.info.put("https://soundcloud.com/forss/flickermood", YtDlpJsonTest.fixture("sc-track.json"));
        YtDlpJson.Listing l = resolver.expand("  https://soundcloud.com/forss/flickermood ", 50);
        assertEquals(1, l.tracks.size());
        assertEquals("Flickermood", l.tracks.get(0).title);
        List<String> call = yt.calls.get(0);
        assertEquals("50", call.get(call.indexOf("--playlist-end") + 1));
    }

    @Test
    void buscaPegaOPrimeiroQueNaoEPrevia() throws Exception {
        YtDlpJson.Listing l = resolver.expand("daft punk get lucky", 50);
        assertEquals(1, l.tracks.size());
        assertEquals("https://soundcloud.com/eldjkb/get-lucky-original-version", l.tracks.get(0).link);
        assertEquals(
            "scsearch8:daft punk get lucky",
            yt.calls.get(0)
                .get(
                    yt.calls.get(0)
                        .size() - 1));
    }

    @Test
    void playlistPerdeAsFaixasLongasDemais() throws Exception {
        FmConfig.IPod.maxTrackMinutes = 5; // o "FN8" tem 650 s
        String link = "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI";
        yt.info.put(link, YtDlpJsonTest.fixture("yt-playlist.json"));
        YtDlpJson.Listing l = resolver.expand(link, 50);
        assertEquals(4, l.tracks.size());
        assertEquals(3, l.skipped); // privado, ao vivo e o longo
        for (IPodTrack t : l.tracks) assertTrue(t.durationSec <= 300);
    }

    @Test
    void faixaUnicaLongaDemaisExplicaOLimite() throws Exception {
        FmConfig.IPod.maxTrackMinutes = 3;
        yt.info.put("https://soundcloud.com/forss/flickermood", YtDlpJsonTest.fixture("sc-track.json"));
        MediaResolver.ResolveException e = assertThrows(
            MediaResolver.ResolveException.class,
            () -> resolver.expand("https://soundcloud.com/forss/flickermood", 50));
        assertEquals("toolong", e.key);
        assertEquals("akashicfm.ipod.err.toolong|3", e.status());
    }

    @Test
    void spotifyLigadoDesligadoENaoAchado() throws Exception {
        String link = "https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv";
        spotifyAnswer = SpotifyClient.parse(YtDlpJsonTest.fixture("spotify-album.html"), 50);
        assertEquals(10, resolver.expand(link, 50).tracks.size());
        assertTrue(yt.calls.isEmpty()); // o Spotify não passa pelo yt-dlp

        spotifyAnswer = null;
        assertEquals(
            "spotify_notfound",
            assertThrows(MediaResolver.ResolveException.class, () -> resolver.expand(link, 50)).key);
        assertEquals(
            "invalid",
            assertThrows(
                MediaResolver.ResolveException.class,
                () -> resolver.expand("https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt", 50)).key);
        FmConfig.IPod.spotify = false;
        assertEquals(
            "spotify_off",
            assertThrows(MediaResolver.ResolveException.class, () -> resolver.expand(link, 50)).key);
    }

    @Test
    void linkInvalidoNemChamaOYtDlp() {
        for (String s : new String[] { "https://evil.com/x", "", null }) {
            assertEquals(
                "invalid",
                assertThrows(MediaResolver.ResolveException.class, () -> resolver.expand(s, 50)).key);
        }
        assertTrue(yt.calls.isEmpty());
    }

    @Test
    void playlistSoComPrivadosFicaVazia() throws Exception {
        String link = "https://www.youtube.com/playlist?list=PLx";
        yt.info.put(
            link,
            "{\"_type\":\"playlist\",\"title\":\"x\",\"entries\":[{\"ie_key\":\"Youtube\",\"id\":\"AAAAAAAAAAA\","
                + "\"title\":\"[Private video]\"}]}");
        assertEquals("empty", assertThrows(MediaResolver.ResolveException.class, () -> resolver.expand(link, 50)).key);
    }

    // ---- Tocar ----

    @Test
    void soundCloudTocaDiretoComOsMetadadosCompletos() throws Exception {
        IPodTrack t = new IPodTrack(
            IPodTrack.Source.SOUNDCLOUD,
            "https://api-v2.soundcloud.com/tracks/297",
            "Soulhack #8",
            "Forss",
            0);
        yt.durations.put(t.link, 399);
        MediaResolver.Located l = resolver.locate(t);
        assertTrue(l.mediaUrl.startsWith("https://cdn.example.com/a.mp3"));
        assertNull(l.mirror);
        assertEquals(t.link, l.track.link); // o link da fila não muda
        assertEquals("Título de 297", l.track.title);
        assertEquals(399, l.track.durationSec);
        assertTrue(
            yt.calls.get(0)
                .contains(YtDlp.SOUNDCLOUD_FORMAT));

        String renewed = resolver.refresh(l);
        assertTrue(renewed.startsWith("https://cdn.example.com/a.mp3"));
        assertFalse(renewed.equals(l.mediaUrl));
    }

    private static final String KB = "https://soundcloud.com/eldjkb/get-lucky-original-version";
    private static final String FAN = "https://soundcloud.com/fan/daft-punk-get-lucky";

    /** Busca com dois candidatos bons e uma prévia; o do DJ KB fica na frente (duração mais perto de 250 s). */
    private static String twoCandidates() {
        return "{\"_type\":\"playlist\",\"entries\":["
            + "{\"ie_key\":\"Soundcloud\",\"webpage_url\":\"https://soundcloud.com/daftpunkofficialmusic/get-lucky\","
            + "\"title\":\"Get Lucky\",\"uploader\":\"Daft Punk\",\"duration\":30.0},"
            + "{\"ie_key\":\"Soundcloud\",\"webpage_url\":\""
            + KB
            + "\",\"title\":\"Get Lucky [Original Version] - Daft Punk\","
            + "\"uploader\":\"DJ KB\",\"duration\":246.4},"
            + "{\"ie_key\":\"Soundcloud\",\"webpage_url\":\""
            + FAN
            + "\",\"title\":\"Daft Punk - Get Lucky\","
            + "\"uploader\":\"fan\",\"duration\":240}]}";
    }

    private static IPodTrack getLuckyVideo() {
        return new IPodTrack(
            IPodTrack.Source.YOUTUBE,
            "https://www.youtube.com/watch?v=5NV6Rdv1a3I",
            "Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers",
            "DaftPunkVEVO",
            250);
    }

    @Test
    void youtubeTocaPeloEspelhoPulandoDrmEGuardaAEscolha() throws Exception {
        yt.searchJson = twoCandidates();
        yt.drm.add(KB); // o melhor candidato tem DRM
        yt.durations.put(FAN, 240);
        MediaResolver.Located l = resolver.locate(getLuckyVideo());
        assertEquals(1, yt.count("-J"));
        assertEquals(2, yt.count("-j"));
        assertEquals(getLuckyVideo().link, l.track.link);
        assertEquals(getLuckyVideo().title, l.track.title); // a tela mostra o vídeo, não o espelho
        assertEquals(FAN, l.mirror.link);
        List<String> search = yt.calls.get(0);
        assertEquals("scsearch8:Daft Punk Get Lucky", search.get(search.size() - 1));

        // A segunda vez vai direto no espelho guardado, sem buscar.
        resolver.locate(getLuckyVideo());
        assertEquals(1, yt.count("-J"));
        assertEquals(3, yt.count("-j"));
    }

    @Test
    void naoAchadoTambemFicaGuardado() throws Exception {
        IPodTrack album = new IPodTrack(
            IPodTrack.Source.YOUTUBE,
            "https://www.youtube.com/watch?v=x1",
            "Daft Punk - Get Lucky",
            "",
            369);
        assertEquals("notfound", assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(album)).key);
        int calls = yt.calls.size();
        assertEquals(2, calls); // a busca principal e a segunda ("Get Lucky"), as duas sem candidato
        assertEquals("notfound", assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(album)).key);
        assertEquals(calls, yt.calls.size());
    }

    @Test
    void todosOsCandidatosComDrm() {
        yt.searchJson = twoCandidates();
        yt.drm.add(KB);
        yt.drm.add(FAN);
        IPodTrack t = getLuckyVideo();
        MediaResolver.ResolveException e = assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(t));
        assertEquals("drm", e.key);
        assertEquals(2, yt.count("-J")); // a segunda busca ("Get Lucky") também é tentada
        assertEquals(2, yt.count("-j")); // e não repete quem já falhou
    }

    @Test
    void espelhoQueSumiuProcuraDeNovo() throws Exception {
        yt.searchJson = twoCandidates();
        yt.durations.put(KB, 246);
        assertEquals(KB, resolver.locate(getLuckyVideo()).mirror.link);
        yt.drm.add(KB); // o upload mudou
        MediaResolver.Located l = resolver.locate(getLuckyVideo());
        assertEquals(FAN, l.mirror.link);
        assertEquals(2, yt.count("-J"));
    }

    @Test
    void spotifyTocaPeloEspelho() throws Exception {
        IPodTrack t = new IPodTrack(
            IPodTrack.Source.SPOTIFY,
            "https://open.spotify.com/track/2Foc5Q5nqNiosCNqttzHof",
            "Get Lucky (feat. Pharrell Williams & Nile Rodgers) - Radio Edit",
            "Daft Punk, Pharrell Williams, Nile Rodgers",
            248);
        yt.durations.put(KB, 246);
        MediaResolver.Located l = resolver.locate(t);
        assertEquals(KB, l.mirror.link);
        assertEquals(t, l.track);
    }

    @Test
    void youtubeDiretoQuandoLigadoECaiNoEspelhoSeFalhar() throws Exception {
        FmConfig.IPod.youtubeDirect = true;
        IPodTrack t = getLuckyVideo();
        yt.durations.put(t.link, 250);
        MediaResolver.Located direct = resolver.locate(t);
        assertNull(direct.mirror);
        assertTrue(direct.mediaUrl.contains(".webm"));
        assertTrue(
            yt.calls.get(0)
                .contains(YtDlp.YOUTUBE_FORMAT));

        yt.drm.add(t.link); // o YouTube recusou: espelho
        yt.calls.clear();
        MediaResolver.Located mirrored = resolver.locate(t);
        assertTrue(mirrored.mirror != null);
        assertEquals(1, yt.count("-J"));
    }

    @Test
    void espelhoLongoDemaisNaoToca() {
        FmConfig.IPod.maxTrackMinutes = 3;
        IPodTrack t = new IPodTrack(IPodTrack.Source.SOUNDCLOUD, "https://soundcloud.com/a/b", "b", "a", 0);
        yt.durations.put(t.link, 400);
        assertEquals("toolong", assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(t)).key);
    }

    // ---- Erros ----

    @Test
    void errosViramMotivosParaATela() {
        IPodTrack t = new IPodTrack(IPodTrack.Source.SOUNDCLOUD, "https://soundcloud.com/a/b", "b", "a", 0);
        yt.next = new YtDlp.Result(-1, "", "", true);
        assertEquals("timeout", assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(t)).key);
        yt.next = new YtDlp.Result(
            1,
            "",
            "ERROR: [soundcloud] 123: Requested format is not available. Use --list-formats",
            false);
        assertEquals("format", assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(t)).key);
        yt.next = new YtDlp.Result(1, "", "ERROR: [soundcloud] 123: HTTP Error 404: Not Found", false);
        MediaResolver.ResolveException e = assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(t));
        assertEquals("failed", e.key);
        assertEquals("HTTP Error 404: Not Found", e.arg);
        yt.next = ok("não é json");
        assertEquals("failed", assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(t)).key);
        yt.next = null;
        yt.fail = new MediaResolver.ResolveException("tools", "baixando yt-dlp");
        e = assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(t));
        assertEquals("akashicfm.ipod.err.tools|baixando yt-dlp", e.status());
        assertThrows(
            IOException.class,
            () -> resolver.refresh(new MediaResolver.Located("u", t, null, t.link, YtDlp.SOUNDCLOUD_FORMAT)));
    }

    @Test
    void ferramentaFaltandoNoEspelhoNaoViraNaoAchado() {
        yt.fail = new MediaResolver.ResolveException("tools", "");
        assertEquals(
            "tools",
            assertThrows(MediaResolver.ResolveException.class, () -> resolver.locate(getLuckyVideo())).key);
        yt.fail = null;
        // Não ficou "não achado" em cache: com a ferramenta de volta, procura.
        yt.durations.put(KB, 246);
        assertTrue(assertDoesNotThrowLocate(getLuckyVideo()).mirror != null);
    }

    private MediaResolver.Located assertDoesNotThrowLocate(IPodTrack t) {
        try {
            return resolver.locate(t);
        } catch (MediaResolver.ResolveException e) {
            throw new AssertionError("não devia falhar: " + e.getMessage());
        }
    }

    @Test
    void mensagemCurtaSemPrefixoDoExtrator() {
        assertEquals("HTTP Error 404", MediaResolver.shorten("[soundcloud] 555584253: HTTP Error 404"));
        assertEquals("sem prefixo", MediaResolver.shorten("sem prefixo"));
        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 200; i++) longMsg.append('x');
        assertEquals(
            80,
            MediaResolver.shorten(longMsg.toString())
                .length());
    }

    @Test
    void falhaPassageiraNaoViraNaoAchado() throws Exception {
        yt.searchJson = twoCandidates();
        boolean[] down = { true };
        // Os dois candidatos dão erro de rede: o motivo é a rede, e nada fica guardado.
        MediaResolver r = new MediaResolver((args, timeout, max) -> {
            if (down[0] && args.get(0)
                .equals("-j"))
                return new YtDlp.Result(
                    1,
                    "",
                    "ERROR: [soundcloud] 1: Unable to download JSON metadata: timed out",
                    false);
            return yt.run(args, timeout, max);
        }, (ref, max) -> null);
        assertEquals("failed", assertThrows(MediaResolver.ResolveException.class, () -> r.locate(getLuckyVideo())).key);
        // Com a rede de volta, o mesmo resolvedor procura de novo (não ficou "não achado" no cache).
        down[0] = false;
        yt.durations.put(KB, 246);
        assertEquals(KB, r.locate(getLuckyVideo()).mirror.link);
    }

    @Test
    void canceladaNaoAbreMaisProcessos() {
        yt.searchJson = twoCandidates();
        Thread.currentThread()
            .interrupt();
        try {
            MediaResolver.ResolveException e = assertThrows(
                MediaResolver.ResolveException.class,
                () -> resolver.locate(getLuckyVideo()));
            assertEquals("cancelled", e.key);
            assertTrue(yt.calls.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void textoDoErroVaiLimpoParaATela() {
        MediaResolver.ResolveException e = new MediaResolver.ResolveException("failed", "§kERRO\u0000 a|b");
        assertEquals("ERRO a/b", e.arg);
        assertEquals("akashicfm.ipod.err.failed|ERRO a/b", e.status());
    }
}
