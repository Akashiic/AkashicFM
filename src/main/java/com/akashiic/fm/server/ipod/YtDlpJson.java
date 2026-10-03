package com.akashiic.fm.server.ipod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.akashiic.fm.common.IPodTrack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Lê a saída JSON do yt-dlp ({@code -J --flat-playlist} para links e buscas, {@code -j -f} para a mídia). Tudo que vem
 * dali é texto de fora: campos ausentes, nulos ou de outro tipo viram vazio, e cada link passa de novo por
 * {@link YtDlp#classify} (uma playlist não consegue enfiar na fila um link de outro site).
 */
final class YtDlpJson {

    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** O que um link ou uma busca rendeu. */
    static final class Listing {

        /** Título da playlist (vazio para uma faixa só). */
        final String title;
        final boolean playlist;
        final List<IPodTrack> tracks;
        /** Itens que não tocam (privados, ao vivo, de outro tipo) ou que passaram do limite. */
        final int skipped;

        Listing(String title, boolean playlist, List<IPodTrack> tracks, int skipped) {
            this.title = title;
            this.playlist = playlist;
            this.tracks = Collections.unmodifiableList(tracks);
            this.skipped = skipped;
        }
    }

    /** A mídia de uma faixa: a URL direta do formato escolhido e os metadados completos. */
    static final class Media {

        final String url;
        final IPodTrack track;

        Media(String url, IPodTrack track) {
            this.url = url;
            this.track = track;
        }
    }

    private YtDlpJson() {}

    static Listing listing(String json, int maxItems) throws IOException {
        JsonObject root = object(json);
        if (!"playlist".equals(str(root, "_type"))) {
            IPodTrack t = entry(root, "", "", 0);
            List<IPodTrack> one = new ArrayList<>(1);
            if (t != null) one.add(t);
            return new Listing("", false, one, t == null ? 1 : 0);
        }
        String title = str(root, "title");
        String uploader = artistOf(root);
        List<IPodTrack> tracks = new ArrayList<>();
        int skipped = 0;
        JsonElement entries = root.get("entries");
        if (entries != null && entries.isJsonArray()) {
            JsonArray arr = entries.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                IPodTrack t = e != null && e.isJsonObject() ? entry(e.getAsJsonObject(), title, uploader, i) : null;
                if (t == null || tracks.size() >= maxItems) skipped++;
                else tracks.add(t);
            }
        }
        return new Listing(title, true, tracks, skipped);
    }

    static Media media(String json) throws IOException {
        JsonObject root = object(json);
        String url = str(root, "url");
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) throw new IOException("sem URL de mídia");
        IPodTrack t = entry(root, "", "", 0);
        if (t == null) throw new IOException("mídia sem faixa válida");
        return new Media(url, t);
    }

    /**
     * Um item (ou o vídeo/faixa da raiz). Null se não toca: outro tipo (canal, playlist dentro da playlist), privado,
     * ao vivo ou sem link aceito.
     */
    private static IPodTrack entry(JsonObject e, String listTitle, String listArtist, int index) {
        String key = str(e, "ie_key");
        if (key.isEmpty()) key = str(e, "extractor_key");
        IPodTrack.Source source;
        if (key.equals("Soundcloud")) source = IPodTrack.Source.SOUNDCLOUD;
        else if (key.equals("Youtube")) source = IPodTrack.Source.YOUTUBE;
        else return null;

        String live = str(e, "live_status");
        if (live.equals("is_live") || live.equals("is_upcoming") || live.equals("post_live")) return null;
        String availability = str(e, "availability");
        if (availability.equals("private") || availability.equals("premium_only")
            || availability.equals("subscriber_only")
            || availability.equals("needs_auth")) return null;
        String title = str(e, "title");
        if (title.isEmpty()) title = str(e, "track");
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        if (lowerTitle.startsWith("[") && lowerTitle.endsWith(" video]")) return null; // [Private video], [Deleted
                                                                                       // video]

        String link;
        if (source == IPodTrack.Source.YOUTUBE) {
            String id = str(e, "id");
            if (!YOUTUBE_ID.matcher(id)
                .matches()) return null;
            link = "https://www.youtube.com/watch?v=" + id;
        } else {
            link = soundcloudLink(str(e, "webpage_url"));
            if (link == null) link = soundcloudLink(str(e, "url"));
            if (link == null) return null;
        }

        String artist = artistOf(e);
        if (title.isEmpty()) {
            // Itens de um set do SoundCloud vêm sem metadados no modo rápido: o título certo chega quando tocar.
            title = listTitle.isEmpty() ? "" : listTitle + " #" + (index + 1);
            if (artist.isEmpty()) artist = listArtist;
        }
        IPodTrack t = new IPodTrack(source, link, title, artist, seconds(e.get("duration")));
        return t.valid() ? t : null;
    }

    private static String soundcloudLink(String url) {
        return !url.isEmpty() && YtDlp.classify(url) == YtDlp.Kind.SOUNDCLOUD ? url : null;
    }

    /** Artista: o campo próprio, senão quem publicou; canais automáticos do YouTube ("X - Topic") viram "X". */
    static String artistOf(JsonObject e) {
        String a = str(e, "artist");
        if (a.isEmpty()) a = str(e, "uploader");
        if (a.isEmpty()) a = str(e, "channel");
        if (a.endsWith(" - Topic")) a = a.substring(0, a.length() - " - Topic".length());
        return a.trim();
    }

    private static int seconds(JsonElement v) {
        if (v == null || !v.isJsonPrimitive()
            || !v.getAsJsonPrimitive()
                .isNumber())
            return 0;
        double d = v.getAsDouble();
        return d > 0 && d < Integer.MAX_VALUE ? (int) Math.round(d) : 0;
    }

    static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive()) return "";
        JsonPrimitive p = v.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : "";
    }

    private static JsonObject object(String json) throws IOException {
        try {
            JsonElement e = new JsonParser().parse(json == null ? "" : json);
            if (e == null || !e.isJsonObject()) throw new IOException("resposta do yt-dlp não é um objeto JSON");
            return e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("resposta do yt-dlp ilegível");
        }
    }
}
