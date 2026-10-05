package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.IPodTrack;

/** Com a saída de verdade do yt-dlp (gravada no spike, só aparada). */
class YtDlpJsonTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = YtDlpJsonTest.class.getResourceAsStream("/ipod/" + name)) {
            if (in == null) throw new IOException("fixture ausente: " + name);
            try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name())) {
                return sc.useDelimiter("\\A")
                    .next();
            }
        }
    }

    @Test
    void faixaDoSoundCloud() throws Exception {
        YtDlpJson.Listing l = YtDlpJson.listing(fixture("sc-track.json"), 50);
        assertFalse(l.playlist);
        assertEquals(1, l.tracks.size());
        IPodTrack t = l.tracks.get(0);
        assertEquals(IPodTrack.Source.SOUNDCLOUD, t.source);
        assertEquals("https://soundcloud.com/forss/flickermood", t.link);
        assertEquals("Flickermood", t.title);
        assertEquals("Forss", t.artist);
        assertEquals(214, t.durationSec);
    }

    @Test
    void setDoSoundCloudSemMetadadosGanhaTituloProvisorio() throws Exception {
        YtDlpJson.Listing l = YtDlpJson.listing(fixture("sc-set.json"), 50);
        assertTrue(l.playlist);
        assertEquals("Soulhack", l.title);
        assertEquals(11, l.tracks.size());
        assertEquals("https://soundcloud.com/forss/city-ports", l.tracks.get(0).link);
        assertEquals("Soulhack #1", l.tracks.get(0).title);
        assertEquals("Forss", l.tracks.get(0).artist);
        assertEquals(0, l.tracks.get(0).durationSec);
        // Os últimos vêm só com a URL da API (aceita: subdomínio do soundcloud.com, extrator fixo).
        assertEquals("https://api-v2.soundcloud.com/tracks/300", l.tracks.get(10).link);
    }

    @Test
    void limiteDeItens() throws Exception {
        YtDlpJson.Listing l = YtDlpJson.listing(fixture("sc-set.json"), 4);
        assertEquals(4, l.tracks.size());
        assertEquals(7, l.skipped);
    }

    @Test
    void playlistDoYouTubePulaPrivadoEAoVivo() throws Exception {
        YtDlpJson.Listing l = YtDlpJson.listing(fixture("yt-playlist.json"), 50);
        assertTrue(l.playlist);
        assertEquals("Popular Music Videos", l.title);
        assertEquals(5, l.tracks.size());
        assertEquals(2, l.skipped);
        IPodTrack first = l.tracks.get(0);
        assertEquals(IPodTrack.Source.YOUTUBE, first.source);
        assertEquals("https://www.youtube.com/watch?v=fOT0BUpITw8", first.link);
        assertEquals("BELLAKEO (Video Oficial) - Peso Pluma, Anitta", first.title);
        assertEquals("Peso Pluma", first.artist);
        assertEquals(235, first.durationSec);
        for (IPodTrack t : l.tracks) {
            assertFalse(t.link.contains("AAAAAAAAAAA"));
            assertFalse(t.link.contains("BBBBBBBBBBB"));
        }
    }

    @Test
    void videoDoYouTube() throws Exception {
        YtDlpJson.Listing l = YtDlpJson.listing(fixture("yt-video.json"), 50);
        assertEquals(1, l.tracks.size());
        IPodTrack t = l.tracks.get(0);
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", t.link);
        assertEquals("Me at the zoo", t.title);
        assertEquals("jawed", t.artist);
        assertEquals(19, t.durationSec);
    }

    @Test
    void buscaDoSoundCloud() throws Exception {
        YtDlpJson.Listing l = YtDlpJson.listing(fixture("sc-search.json"), 8);
        assertEquals(5, l.tracks.size());
        assertEquals("https://soundcloud.com/daftpunkofficialmusic/get-lucky", l.tracks.get(0).link);
        assertEquals(30, l.tracks.get(0).durationSec);
        assertEquals(246, l.tracks.get(1).durationSec);
    }

    @Test
    void buscaDoYouTube() throws Exception {
        // Gravado do yt-dlp de verdade (ytsearch10, --flat-playlist), sem as miniaturas.
        YtDlpJson.Listing l = YtDlpJson.listing(fixture("yt-search.json"), 10);
        assertEquals(10, l.tracks.size());
        for (IPodTrack t : l.tracks) {
            assertEquals(IPodTrack.Source.YOUTUBE, t.source);
            assertTrue(t.link.startsWith("https://www.youtube.com/watch?v="), t.link);
            assertTrue(t.durationSec > 0);
        }
        assertEquals("Daft Punk", l.tracks.get(0).artist);
        assertEquals(3601, l.tracks.get(8).durationSec);
    }

    @Test
    void midiaTrazAUrlDoFormatoEOsMetadados() throws Exception {
        YtDlpJson.Media m = YtDlpJson.media(fixture("sc-media.json"));
        assertTrue(m.url.startsWith("https://cf-media.sndcdn.com/OxDntAhpwK1v.128.mp3?"));
        assertEquals("Characteristics", m.track.title);
        assertEquals("Forss", m.track.artist);
        assertEquals(399, m.track.durationSec);
        assertEquals("https://soundcloud.com/forss/characteristics", m.track.link);
    }

    @Test
    void linksDeOutrosSitesNaoEntram() throws Exception {
        String json = "{\"_type\":\"playlist\",\"title\":\"x\",\"entries\":["
            + "{\"ie_key\":\"Soundcloud\",\"url\":\"https://evil.com/soundcloud.com/a\",\"title\":\"a\"},"
            + "{\"ie_key\":\"Generic\",\"url\":\"https://soundcloud.com/a/b\",\"title\":\"b\"},"
            + "{\"ie_key\":\"YoutubeTab\",\"url\":\"https://www.youtube.com/@canal\",\"title\":\"c\"},"
            + "{\"ie_key\":\"Youtube\",\"id\":\"curto\",\"title\":\"d\"},"
            + "{\"ie_key\":\"Youtube\",\"id\":\"jNQXAC9IVRw\",\"title\":\"[Deleted video]\"},"
            + "null, 42, \"texto\","
            + "{\"ie_key\":\"Soundcloud\",\"url\":\"https://soundcloud.com/ok/faixa\",\"title\":123,\"duration\":\"9\"}"
            + "]}";
        YtDlpJson.Listing l = YtDlpJson.listing(json, 50);
        assertEquals(1, l.tracks.size());
        assertEquals("https://soundcloud.com/ok/faixa", l.tracks.get(0).link);
        assertEquals("x #9", l.tracks.get(0).title); // título que não é texto: provisório
        assertEquals(0, l.tracks.get(0).durationSec);
        assertEquals(8, l.skipped);
    }

    @Test
    void textoDeForaELimpo() throws Exception {
        String json = "{\"extractor_key\":\"Soundcloud\",\"webpage_url\":\"https://soundcloud.com/a/b\","
            + "\"title\":\"\\u00a7kTítulo\\u202e\\u0000 bom\",\"uploader\":\"Fulano - Topic\",\"duration\":99999999}";
        IPodTrack t = YtDlpJson.listing(json, 1).tracks.get(0);
        assertEquals("Título bom", t.title);
        assertEquals("Fulano", t.artist);
        assertEquals(0, t.durationSec); // acima de 24 h: desconhecida
    }

    @Test
    void jsonRuimFalhaComIOException() {
        assertThrows(IOException.class, () -> YtDlpJson.listing("", 5));
        assertThrows(IOException.class, () -> YtDlpJson.listing("[1,2]", 5));
        assertThrows(IOException.class, () -> YtDlpJson.listing("{\"_type\":", 5));
        assertThrows(
            IOException.class,
            () -> YtDlpJson.media("{\"extractor_key\":\"Soundcloud\",\"url\":\"ftp://x\"}"));
        assertThrows(
            IOException.class,
            () -> YtDlpJson.media("{\"extractor_key\":\"Generic\",\"url\":\"https://x/a.mp3\"}"));
    }
}
