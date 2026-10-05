package com.akashiic.fm.server.ipod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import com.akashiic.fm.Tags;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodTrack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Busca de faixas na API oficial do Spotify, só se o admin pôs uma chave no config ({@code ipod.spotifyClientId} e
 * {@code ipod.spotifyClientSecret}). Sem chave, a busca do Spotify não existe: links continuam funcionando pelo
 * {@link SpotifyClient}, sem chave nenhuma.
 * <p>
 * Desde fevereiro de 2026 um app em modo de desenvolvimento exige Premium do dono e devolve no máximo 10 resultados
 * por busca; o fluxo de credenciais do cliente (o token sem usuário) é o que serve para um servidor, e o Spotify disse
 * que vai deixá-lo de lado: tudo aqui falha com um motivo claro. O segredo e o token nunca vão para o log nem para as
 * mensagens de erro. Hosts fixos, sem redirecionamento; bloqueia (threads do iPod).
 */
final class SpotifySearch {

    static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    static final String SEARCH_URL = "https://api.spotify.com/v1/search";
    /** O máximo do modo de desenvolvimento desde fevereiro de 2026. */
    static final int MAX_RESULTS = 10;
    static final int MAX_BYTES = 1 << 20;
    static final int TIMEOUT_MS = 10_000;
    /** Sem Retry-After num 429: espera isto. */
    static final long DEFAULT_RETRY_MS = 30_000;
    static final long MAX_RETRY_MS = 10 * 60_000L;
    private static final Pattern TRACK_ID = Pattern.compile("[A-Za-z0-9]{22}");

    /** Uma requisição HTTP (trocada nos testes). */
    interface Http {

        Response send(String method, String url, Map<String, String> headers, byte[] body) throws IOException;
    }

    /** Código, corpo (limitado) e o Retry-After, se veio. */
    static final class Response {

        final int code;
        final String body;
        final String retryAfter;

        Response(int code, String body, String retryAfter) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.retryAfter = retryAfter;
        }
    }

    private final Http http;
    private final String tokenUrl;
    private final String searchUrl;
    private final LongSupplier clock;
    /** Para que credenciais o token vale (trocar a chave no config descarta o token). */
    private String tokenFor;
    private String token;
    private long tokenExpiresAtMs;
    /** Depois de um 429, nada até aqui. */
    private long blockedUntilMs;

    SpotifySearch() {
        this(SpotifySearch::send, TOKEN_URL, SEARCH_URL, System::currentTimeMillis);
    }

    SpotifySearch(Http http, String tokenUrl, String searchUrl, LongSupplier clock) {
        this.http = http;
        this.tokenUrl = tokenUrl;
        this.searchUrl = searchUrl;
        this.clock = clock;
    }

    /** A busca do Spotify existe neste servidor (links do Spotify ligados e uma chave no config). */
    static boolean available() {
        return FmConfig.IPod.spotify && !clientId().isEmpty() && !clientSecret().isEmpty();
    }

    private static String clientId() {
        return FmConfig.IPod.spotifyClientId == null ? "" : FmConfig.IPod.spotifyClientId.trim();
    }

    private static String clientSecret() {
        return FmConfig.IPod.spotifyClientSecret == null ? "" : FmConfig.IPod.spotifyClientSecret.trim();
    }

    /** As faixas achadas (até {@code max}, no máximo 10). Pode vir vazia. */
    synchronized List<IPodTrack> search(String query, int max) throws MediaResolver.ResolveException {
        if (!FmConfig.IPod.spotify) throw new MediaResolver.ResolveException("spotify_off", "");
        String id = clientId(), secret = clientSecret();
        if (id.isEmpty() || secret.isEmpty()) throw new MediaResolver.ResolveException("spotify_nokey", "");
        int n = Math.max(1, Math.min(MAX_RESULTS, max));
        String url = searchUrl + "?q=" + encode(query) + "&type=track&limit=" + n;
        Response r = request("GET", url, bearer(id, secret), null);
        if (r.code == 401) { // o token caiu antes da hora: um novo, uma vez
            token = null;
            r = request("GET", url, bearer(id, secret), null);
        }
        if (r.code == 401 || r.code == 403) throw new MediaResolver.ResolveException("spotify_key", "");
        if (r.code != 200) throw failure(r);
        try {
            return parse(r.body, n);
        } catch (IOException e) {
            throw new MediaResolver.ResolveException("failed", e.getMessage());
        }
    }

    private Map<String, String> bearer(String id, String secret) throws MediaResolver.ResolveException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token(id, secret));
        h.put("Accept", "application/json");
        return h;
    }

    /** O token de credenciais do cliente, do cache enquanto vale (com um minuto de folga). */
    private String token(String id, String secret) throws MediaResolver.ResolveException {
        String who = id + '\n' + secret;
        long now = clock.getAsLong();
        if (token != null && who.equals(tokenFor) && now < tokenExpiresAtMs) return token;
        token = null;
        Map<String, String> h = new LinkedHashMap<>();
        h.put(
            "Authorization",
            "Basic " + Base64.getEncoder()
                .encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        Response r = request("POST", tokenUrl, h, "grant_type=client_credentials".getBytes(StandardCharsets.US_ASCII));
        if (r.code == 400 || r.code == 401 || r.code == 403)
            throw new MediaResolver.ResolveException("spotify_key", "");
        if (r.code != 200) throw failure(r);
        String access;
        long expiresSec;
        try {
            JsonObject o = new JsonParser().parse(r.body)
                .getAsJsonObject();
            access = YtDlpJson.str(o, "access_token");
            JsonElement e = o.get("expires_in");
            expiresSec = e != null && e.isJsonPrimitive()
                && e.getAsJsonPrimitive()
                    .isNumber() ? e.getAsLong() : 3600;
        } catch (JsonParseException | IllegalStateException e) {
            throw new MediaResolver.ResolveException("failed", "token ilegível");
        }
        if (access.isEmpty()) throw new MediaResolver.ResolveException("failed", "token ilegível");
        token = access;
        tokenFor = who;
        tokenExpiresAtMs = now + Math.max(60, expiresSec - 60) * 1000L;
        return token;
    }

    private Response request(String method, String url, Map<String, String> headers, byte[] body)
        throws MediaResolver.ResolveException {
        long now = clock.getAsLong();
        if (now < blockedUntilMs) throw new MediaResolver.ResolveException("busy", "");
        Response r;
        try {
            r = http.send(method, url, headers, body);
        } catch (IOException e) {
            // Sem a mensagem da exceção: ela pode citar a URL, e a busca vai na query.
            throw new MediaResolver.ResolveException("failed", "rede");
        }
        if (r.code == 429) {
            blockedUntilMs = now + retryAfterMs(r.retryAfter);
            throw new MediaResolver.ResolveException("busy", "");
        }
        return r;
    }

    private static MediaResolver.ResolveException failure(Response r) {
        return new MediaResolver.ResolveException("failed", "Spotify HTTP " + r.code);
    }

    static long retryAfterMs(String header) {
        if (header != null) {
            try {
                long sec = Long.parseLong(header.trim());
                if (sec >= 0) return Math.min(MAX_RETRY_MS, Math.max(1000, sec * 1000));
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_RETRY_MS;
    }

    /** As faixas da resposta da busca ({@code tracks.items}). */
    static List<IPodTrack> parse(String json, int max) throws IOException {
        JsonArray items;
        try {
            JsonElement root = new JsonParser().parse(json);
            JsonElement tracks = root.isJsonObject() ? root.getAsJsonObject()
                .get("tracks") : null;
            JsonElement it = tracks != null && tracks.isJsonObject() ? tracks.getAsJsonObject()
                .get("items") : null;
            if (it == null || !it.isJsonArray()) throw new IOException("resposta do Spotify sem faixas");
            items = it.getAsJsonArray();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("resposta do Spotify ilegível");
        }
        List<IPodTrack> out = new ArrayList<>();
        for (JsonElement e : items) {
            if (out.size() >= max) break;
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = YtDlpJson.str(o, "id");
            String name = YtDlpJson.str(o, "name");
            if (!TRACK_ID.matcher(id)
                .matches() || name.trim()
                    .isEmpty())
                continue;
            int sec = 0;
            JsonElement d = o.get("duration_ms");
            if (d != null && d.isJsonPrimitive()
                && d.getAsJsonPrimitive()
                    .isNumber())
                sec = (int) Math.round(d.getAsDouble() / 1000.0);
            IPodTrack t = new IPodTrack(IPodTrack.Source.SPOTIFY, SpotifyClient.trackLink(id), name, artists(o), sec);
            if (t.valid()) out.add(t);
        }
        return out;
    }

    private static String artists(JsonObject track) {
        JsonElement a = track.get("artists");
        if (a == null || !a.isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : a.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) continue;
            String name = YtDlpJson.str(e.getAsJsonObject(), "name");
            if (name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A requisição de verdade: sem redirecionamento, com tempo e tamanho limitados. */
    static Response send(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "AkashicFM/" + Tags.VERSION);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = s.read(buf)) != -1) {
                        if (out.size() + n > MAX_BYTES) throw new IOException("resposta do Spotify grande demais");
                        out.write(buf, 0, n);
                    }
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new Response(code, text, c.getHeaderField("Retry-After"));
        } finally {
            c.disconnect();
        }
    }
}
