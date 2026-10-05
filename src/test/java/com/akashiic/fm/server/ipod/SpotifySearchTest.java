package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodTrack;
import com.sun.net.httpserver.HttpServer;

/** A busca do Spotify pela chave do config: token, erros (sem vazar o segredo), resposta e o HTTP de verdade. */
class SpotifySearchTest {

    private static final String SECRET = "s3cr3t-do-admin";
    private static final String TOKEN = "BQD-token-secreto";
    private static final String TOKEN_JSON = "{\"access_token\":\"" + TOKEN
        + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    /** Uma requisição vista pelo HTTP falso. */
    private static final class Seen {

        final String method, url;
        final Map<String, String> headers;
        final String body;

        Seen(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body == null ? null : new String(body, StandardCharsets.UTF_8);
        }
    }

    /** HTTP falso: responde pela fila de respostas de cada host (token e busca). */
    private static final class FakeHttp implements SpotifySearch.Http {

        final List<Seen> seen = new ArrayList<>();
        final Deque<SpotifySearch.Response> tokens = new ArrayDeque<>();
        final Deque<SpotifySearch.Response> searches = new ArrayDeque<>();
        boolean networkDown;

        @Override
        public SpotifySearch.Response send(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
            seen.add(new Seen(method, url, headers, body));
            if (networkDown) throw new IOException("falhou ao ligar para " + url);
            Deque<SpotifySearch.Response> q = url.startsWith("tok:") ? tokens : searches;
            return q.isEmpty() ? new SpotifySearch.Response(200, url.startsWith("tok:") ? TOKEN_JSON : search(), null)
                : q.poll();
        }

        int count(String prefix) {
            int n = 0;
            for (Seen s : seen) if (s.url.startsWith(prefix)) n++;
            return n;
        }
    }

    private static String search() {
        try {
            return YtDlpJsonTest.fixture("spotify-search.json");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final FakeHttp http = new FakeHttp();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private SpotifySearch search;

    @BeforeEach
    void setUp() {
        FmConfig.IPod.spotify = true;
        FmConfig.IPod.spotifyClientId = "client-id";
        FmConfig.IPod.spotifyClientSecret = SECRET;
        search = new SpotifySearch(http, "tok:", "api:", now::get);
    }

    @AfterEach
    void tearDown() {
        FmConfig.IPod.spotify = false;
        FmConfig.IPod.spotifyClientId = "";
        FmConfig.IPod.spotifyClientSecret = "";
    }

    @Test
    void tokenPorCredenciaisDoClienteEmCache() throws Exception {
        List<IPodTrack> r = search.search("never gonna", 10);
        search.search("daft punk", 5);
        assertEquals(1, http.count("tok:"), "o token fica em cache");
        assertEquals(2, http.count("api:"));
        Seen tok = http.seen.get(0);
        assertEquals("POST", tok.method);
        assertEquals("grant_type=client_credentials", tok.body);
        assertEquals("application/x-www-form-urlencoded", tok.headers.get("Content-Type"));
        assertEquals(
            "Basic " + Base64.getEncoder()
                .encodeToString(("client-id:" + SECRET).getBytes(StandardCharsets.UTF_8)),
            tok.headers.get("Authorization"));
        Seen q = http.seen.get(1);
        assertEquals("GET", q.method);
        assertEquals("api:?q=never+gonna&type=track&limit=10", q.url);
        assertEquals("Bearer " + TOKEN, q.headers.get("Authorization"));
        assertEquals("api:?q=daft+punk&type=track&limit=5", http.seen.get(2).url);
        assertEquals(3, r.size());
    }

    @Test
    void limiteDeDezDoModoDeDesenvolvimento() throws Exception {
        search.search("x", 50);
        assertTrue(http.seen.get(1).url.endsWith("&limit=10"));
        search.search("x", 0);
        assertTrue(http.seen.get(2).url.endsWith("&limit=1"));
    }

    @Test
    void textoComCaracteresEspeciaisVaiCodificado() throws Exception {
        search.search("AC/DC & Queen? ção", 10);
        assertEquals("api:?q=AC%2FDC+%26+Queen%3F+%C3%A7%C3%A3o&type=track&limit=10", http.seen.get(1).url);
    }

    @Test
    void tokenRenovadoAntesDeExpirar() throws Exception {
        search.search("a", 10);
        now.addAndGet((3600 - 61) * 1000L);
        search.search("b", 10);
        assertEquals(1, http.count("tok:"));
        now.addAndGet(2000);
        search.search("c", 10);
        assertEquals(2, http.count("tok:"), "com menos de um minuto de validade, pede outro");
    }

    @Test
    void trocarAChaveDescartaOToken() throws Exception {
        search.search("a", 10);
        FmConfig.IPod.spotifyClientSecret = "outro-segredo";
        search.search("b", 10);
        assertEquals(2, http.count("tok:"));
    }

    @Test
    void tokenRecusadoNaBuscaPedeOutroUmaVez() throws Exception {
        http.searches.add(new SpotifySearch.Response(401, "{\"error\":{\"status\":401}}", null));
        assertEquals(
            3,
            search.search("a", 10)
                .size());
        assertEquals(2, http.count("tok:"));
        http.searches.add(new SpotifySearch.Response(401, "", null));
        http.searches.add(new SpotifySearch.Response(401, "", null));
        MediaResolver.ResolveException e = assertThrows(
            MediaResolver.ResolveException.class,
            () -> search.search("b", 10));
        assertEquals("spotify_key", e.key);
    }

    @Test
    void chaveRecusadaNoToken() {
        for (int code : new int[] { 400, 401, 403 }) {
            SpotifySearch s = new SpotifySearch(http, "tok:", "api:", now::get);
            http.tokens.add(new SpotifySearch.Response(code, "{\"error\":\"invalid_client\"}", null));
            MediaResolver.ResolveException e = assertThrows(
                MediaResolver.ResolveException.class,
                () -> s.search("a", 10));
            assertEquals("spotify_key", e.key, "HTTP " + code);
        }
    }

    @Test
    void premiumDoDonoVencidoRecusaABusca() {
        http.searches.add(new SpotifySearch.Response(403, "{\"error\":{\"status\":403}}", null));
        MediaResolver.ResolveException e = assertThrows(
            MediaResolver.ResolveException.class,
            () -> search.search("a", 10));
        assertEquals("spotify_key", e.key);
    }

    @Test
    void limiteDeRequisicoesRespeitaORetryAfter() throws Exception {
        http.searches.add(new SpotifySearch.Response(429, "", "5"));
        assertEquals("busy", assertThrows(MediaResolver.ResolveException.class, () -> search.search("a", 10)).key);
        int calls = http.seen.size();
        assertEquals("busy", assertThrows(MediaResolver.ResolveException.class, () -> search.search("a", 10)).key);
        assertEquals(calls, http.seen.size(), "durante a espera, nada sai");
        now.addAndGet(5000);
        assertEquals(
            3,
            search.search("a", 10)
                .size());
    }

    @Test
    void outrasFalhas() {
        http.tokens.add(new SpotifySearch.Response(503, "", null));
        MediaResolver.ResolveException e = assertThrows(
            MediaResolver.ResolveException.class,
            () -> search.search("a", 10));
        assertEquals("failed", e.key);
        assertEquals("Spotify HTTP 503", e.arg);
        http.searches.add(new SpotifySearch.Response(200, "<html>não é json</html>", null));
        assertEquals("failed", assertThrows(MediaResolver.ResolveException.class, () -> search.search("a", 10)).key);
        http.tokens.add(new SpotifySearch.Response(200, "{\"token_type\":\"Bearer\"}", null));
        SpotifySearch s = new SpotifySearch(http, "tok:", "api:", now::get);
        assertEquals("failed", assertThrows(MediaResolver.ResolveException.class, () -> s.search("a", 10)).key);
    }

    @Test
    void oSegredoEOTokenNuncaVaoParaAMensagem() {
        List<MediaResolver.ResolveException> errors = new ArrayList<>();
        int[][] cases = { { 400, 0 }, { 500, 0 }, { 0, 401 }, { 0, 403 }, { 0, 429 }, { 0, 500 } };
        for (int[] c : cases) {
            http.tokens.clear();
            http.searches.clear();
            SpotifySearch s = new SpotifySearch(http, "tok:", "api:", now::get);
            if (c[0] != 0) http.tokens.add(new SpotifySearch.Response(c[0], "{\"secret\":\"" + SECRET + "\"}", null));
            else {
                http.searches.add(new SpotifySearch.Response(c[1], TOKEN, "1"));
                http.searches.add(new SpotifySearch.Response(c[1], TOKEN, "1"));
            }
            try {
                s.search(SECRET, 10);
            } catch (MediaResolver.ResolveException e) {
                errors.add(e);
            }
        }
        http.networkDown = true;
        try {
            new SpotifySearch(http, "tok:", "api:", now::get).search("q", 10);
        } catch (MediaResolver.ResolveException e) {
            errors.add(e);
        }
        assertEquals(cases.length + 1, errors.size());
        for (MediaResolver.ResolveException e : errors) {
            String all = e.getMessage() + " " + e.arg + " " + e.status();
            assertFalse(all.contains(SECRET), all);
            assertFalse(all.contains(TOKEN), all);
            assertFalse(all.contains("tok:") || all.contains("api:"), "sem a URL (a busca vai na query): " + all);
        }
    }

    @Test
    void semChaveOuDesligadoNaoChamaNada() {
        FmConfig.IPod.spotifyClientSecret = " ";
        assertFalse(SpotifySearch.available());
        assertEquals(
            "spotify_nokey",
            assertThrows(MediaResolver.ResolveException.class, () -> search.search("a", 10)).key);
        FmConfig.IPod.spotifyClientSecret = SECRET;
        assertTrue(SpotifySearch.available());
        FmConfig.IPod.spotify = false;
        assertFalse(SpotifySearch.available());
        assertEquals(
            "spotify_off",
            assertThrows(MediaResolver.ResolveException.class, () -> search.search("a", 10)).key);
        assertTrue(http.seen.isEmpty());
    }

    @Test
    void respostaDaBusca() throws Exception {
        List<IPodTrack> r = SpotifySearch.parse(search(), 10);
        assertEquals(3, r.size(), "sem id de 22 caracteres ou sem nome fica de fora");
        IPodTrack a = r.get(0);
        assertEquals(IPodTrack.Source.SPOTIFY, a.source);
        assertEquals("https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8", a.link);
        assertEquals("Never Gonna Give You Up", a.title);
        assertEquals("Rick Astley", a.artist);
        assertEquals(214, a.durationSec);
        assertEquals("Kylie Minogue, Jason Donovan", r.get(1).artist);
        assertEquals(196, r.get(1).durationSec);
        assertEquals("", r.get(2).artist);
        assertEquals(0, r.get(2).durationSec);
        assertEquals(
            1,
            SpotifySearch.parse(search(), 1)
                .size());
        assertThrows(IOException.class, () -> SpotifySearch.parse("{\"tracks\":{}}", 10));
        assertThrows(IOException.class, () -> SpotifySearch.parse("[1,2]", 10));
        assertThrows(IOException.class, () -> SpotifySearch.parse("{{{", 10));
    }

    @Test
    void retryAfter() {
        assertEquals(SpotifySearch.DEFAULT_RETRY_MS, SpotifySearch.retryAfterMs(null));
        assertEquals(SpotifySearch.DEFAULT_RETRY_MS, SpotifySearch.retryAfterMs("amanhã"));
        assertEquals(1000, SpotifySearch.retryAfterMs("0"));
        assertEquals(7000, SpotifySearch.retryAfterMs(" 7 "));
        assertEquals(SpotifySearch.MAX_RETRY_MS, SpotifySearch.retryAfterMs("100000"));
    }

    @Test
    void httpDeVerdadeContraUmServidorLocal() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> log = new ArrayList<>();
        server.createContext("/token", ex -> {
            String body = read(ex.getRequestBody());
            log.add(
                ex.getRequestMethod() + " "
                    + ex.getRequestHeaders()
                        .getFirst("Authorization")
                    + " "
                    + ex.getRequestHeaders()
                        .getFirst("Content-Type")
                    + " "
                    + body);
            reply(ex, 200, TOKEN_JSON);
        });
        server.createContext("/search", ex -> {
            log.add(
                ex.getRequestMethod() + " "
                    + ex.getRequestURI()
                    + " "
                    + ex.getRequestHeaders()
                        .getFirst("Authorization"));
            reply(ex, 200, search());
        });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders()
                .add("Location", "http://127.0.0.1:1/x");
            reply(ex, 302, "");
        });
        server.createContext("/big", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 20; i++) out.write(chunk);
            } catch (IOException ignored) {}
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress()
                .getPort();
            SpotifySearch s = new SpotifySearch(SpotifySearch::send, base + "/token", base + "/search", now::get);
            List<IPodTrack> r = s.search("never gonna", 10);
            assertEquals(3, r.size());
            assertEquals(2, log.size());
            assertTrue(
                log.get(0)
                    .startsWith("POST Basic "),
                log.get(0));
            assertTrue(
                log.get(0)
                    .endsWith(" application/x-www-form-urlencoded grant_type=client_credentials"),
                log.get(0));
            assertEquals("GET /search?q=never+gonna&type=track&limit=10 Bearer " + TOKEN, log.get(1));
            assertEquals(302, SpotifySearch.send("GET", base + "/moved", java.util.Collections.emptyMap(), null).code);
            assertThrows(
                IOException.class,
                () -> SpotifySearch.send("GET", base + "/big", java.util.Collections.emptyMap(), null));
        } finally {
            server.stop(0);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
        ex.close();
    }
}
