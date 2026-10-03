package com.akashiic.fm.server.ipod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.akashiic.fm.Tags;
import com.akashiic.fm.common.IPodTrack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Faixas do Spotify, só os metadados (título, artistas e duração), para tocar a mesma música do SoundCloud. Lê a
 * página pública do player embutido ({@code open.spotify.com/embed/...}), a mesma que qualquer site mostra: sem chave
 * (a API oficial passou a exigir Premium do dono do app e não lista mais playlists de outros). O player embutido
 * mostra até 100 faixas de uma playlist. Host fixo; bloqueia (threads do iPod).
 */
final class SpotifyClient {

    /** Faixa, álbum ou playlist. */
    static final class Ref {

        final String type;
        final String id;

        Ref(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /** O link existe, mas o Spotify não achou (apagado, privado ou digitado errado). */
    static final class NotFoundException extends IOException {

        NotFoundException() {
            super("não achado no Spotify");
        }
    }

    static final String EMBED = "https://open.spotify.com/embed/";
    static final int MAX_PAGE_BYTES = 4 << 20;
    private static final Pattern URI_FORM = Pattern.compile("spotify:(track|album|playlist):([A-Za-z0-9]{22})");
    /** O tipo e o prefixo de idioma em qualquer caixa; o id diferencia maiúsculas. */
    private static final Pattern PATH = Pattern.compile(
        "/(?:(?i:intl-[a-z]{2}(?:-[a-z]{2})?)/)?(?:(?i:embed)/)?((?i:track|album|playlist))/([A-Za-z0-9]{22})/?");
    private static final String DATA_START = "<script id=\"__NEXT_DATA__\" type=\"application/json\">";

    private SpotifyClient() {}

    /** O que o link aponta, ou null (artista, podcast, outro host...). */
    static Ref parseLink(String input) {
        if (input == null) return null;
        String s = input.trim();
        Matcher u = URI_FORM.matcher(s);
        if (u.matches()) return new Ref(u.group(1), u.group(2));
        if (YtDlp.classify(s) != YtDlp.Kind.SPOTIFY) return null;
        String path;
        try {
            path = new URI(s).getRawPath();
        } catch (Exception e) {
            return null;
        }
        if (path == null) return null;
        Matcher m = PATH.matcher(path);
        return m.matches() ? new Ref(
            m.group(1)
                .toLowerCase(Locale.ROOT),
            m.group(2)) : null;
    }

    static String trackLink(String id) {
        return "https://open.spotify.com/track/" + id;
    }

    static YtDlpJson.Listing fetch(Ref ref, int maxItems) throws IOException {
        return parse(download(EMBED + ref.type + "/" + ref.id), maxItems);
    }

    /** Lê o JSON que a página embute ({@code __NEXT_DATA__}). */
    static YtDlpJson.Listing parse(String html, int maxItems) throws IOException {
        int start = html == null ? -1 : html.indexOf(DATA_START);
        int end = start < 0 ? -1 : html.indexOf("</script>", start);
        if (end < 0) throw new IOException("página do Spotify sem dados (o formato mudou?)");
        JsonObject props;
        try {
            JsonElement root = new JsonParser().parse(html.substring(start + DATA_START.length(), end));
            props = obj(obj(root, "props"), "pageProps");
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("dados do Spotify ilegíveis");
        }
        if (props == null) throw new IOException("página do Spotify sem dados (o formato mudou?)");
        JsonElement status = props.get("status");
        if (status != null && status.isJsonPrimitive()
            && status.getAsJsonPrimitive()
                .isNumber()
            && status.getAsInt() != 200) throw new NotFoundException();
        JsonObject entity = obj(obj(obj(props, "state"), "data"), "entity");
        if (entity == null) throw new IOException("página do Spotify sem faixa (o formato mudou?)");

        String type = YtDlpJson.str(entity, "type");
        List<IPodTrack> tracks = new ArrayList<>();
        int skipped = 0;
        if (type.equals("track")) {
            IPodTrack t = track(entity, YtDlpJson.str(entity, "name"), artists(entity));
            if (t != null) tracks.add(t);
            else skipped++;
            return new YtDlpJson.Listing("", false, tracks, skipped);
        }
        if (!type.equals("album") && !type.equals("playlist")) throw new IOException("link do Spotify sem faixas");
        JsonElement list = entity.get("trackList");
        if (list != null && list.isJsonArray()) {
            JsonArray arr = list.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                IPodTrack t = null;
                if (e != null && e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    String kind = YtDlpJson.str(o, "entityType");
                    if (kind.isEmpty() || kind.equals("track"))
                        t = track(o, YtDlpJson.str(o, "title"), YtDlpJson.str(o, "subtitle"));
                }
                if (t == null || tracks.size() >= maxItems) skipped++;
                else tracks.add(t);
            }
        }
        String title = YtDlpJson.str(entity, "name");
        if (title.isEmpty()) title = YtDlpJson.str(entity, "title");
        return new YtDlpJson.Listing(title, true, tracks, skipped);
    }

    /** Uma faixa (o id vem do uri "spotify:track:..."). Null se falta id ou título. */
    private static IPodTrack track(JsonObject o, String title, String artist) {
        Matcher m = URI_FORM.matcher(YtDlpJson.str(o, "uri"));
        if (!m.matches() || !m.group(1)
            .equals("track")
            || title.trim()
                .isEmpty())
            return null;
        JsonElement d = o.get("duration");
        int sec = 0;
        if (d != null && d.isJsonPrimitive()
            && d.getAsJsonPrimitive()
                .isNumber())
            sec = (int) Math.round(d.getAsDouble() / 1000.0);
        IPodTrack t = new IPodTrack(IPodTrack.Source.SPOTIFY, trackLink(m.group(2)), title, clean(artist), sec);
        return t.valid() ? t : null;
    }

    /** "A, B" a partir da lista de artistas da página de uma faixa. */
    private static String artists(JsonObject entity) {
        JsonElement a = entity.get("artists");
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

    /** A página separa os artistas com vírgula e espaço não separável. */
    private static String clean(String artist) {
        return artist.replace(' ', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static JsonObject obj(JsonElement e, String key) {
        if (e == null || !e.isJsonObject()) return null;
        JsonElement v = e.getAsJsonObject()
            .get(key);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }

    private static String download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AkashicFM/" + Tags.VERSION + ")");
        c.setRequestProperty("Accept", "text/html");
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new NotFoundException();
            if (code != 200) throw new IOException("Spotify respondeu HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > MAX_PAGE_BYTES) throw new IOException("página do Spotify grande demais");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
