package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.IPodTrack;

/** Com as páginas de verdade do player embutido (gravadas, só aparadas). */
class SpotifyClientTest {

    @Test
    void entendeOsFormatosDeLink() {
        SpotifyClient.Ref t = SpotifyClient
            .parseLink("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=abc123");
        assertEquals("track", t.type);
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", t.id);
        assertEquals(
            "album",
            SpotifyClient.parseLink("https://open.spotify.com/intl-pt/album/4LH4d3cOWNNsVw41Gqt2kv").type);
        assertEquals("playlist", SpotifyClient.parseLink("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M").type);
        assertEquals(
            "track",
            SpotifyClient.parseLink("https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC").type);
        assertEquals(
            "playlist",
            SpotifyClient.parseLink("https://open.spotify.com/Playlist/37i9dQZF1DXcBWIGoYBM5M/").type);
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            SpotifyClient.parseLink("https://open.spotify.com/intl-pt-br/playlist/37i9dQZF1DXcBWIGoYBM5M").id);
    }

    @Test
    void recusaOQueNaoEFaixaAlbumOuPlaylist() {
        for (String s : new String[] { null, "", "https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt",
            "https://open.spotify.com/episode/4uLU6hMCjMI75M1A2tKUQC", "https://open.spotify.com/track/curto",
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC/extra",
            "https://evil.com/track/4uLU6hMCjMI75M1A2tKUQC", "spotify:track:4uLU6hMCjMI75M1A2tKUQ!",
            "https://open.spotify.com/track/../album/4LH4d3cOWNNsVw41Gqt2kv" }) {
            assertNull(SpotifyClient.parseLink(s), String.valueOf(s));
        }
    }

    @Test
    void faixa() throws Exception {
        YtDlpJson.Listing l = SpotifyClient.parse(YtDlpJsonTest.fixture("spotify-track.html"), 50);
        assertFalse(l.playlist);
        assertEquals(1, l.tracks.size());
        IPodTrack t = l.tracks.get(0);
        assertEquals(IPodTrack.Source.SPOTIFY, t.source);
        assertEquals("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", t.link);
        assertEquals("Never Gonna Give You Up", t.title);
        assertEquals("Rick Astley", t.artist);
        assertEquals(214, t.durationSec);
    }

    @Test
    void album() throws Exception {
        YtDlpJson.Listing l = SpotifyClient.parse(YtDlpJsonTest.fixture("spotify-album.html"), 50);
        assertTrue(l.playlist);
        assertEquals("The Dark Side of the Moon", l.title);
        assertEquals(10, l.tracks.size());
        assertEquals("Speak to Me", l.tracks.get(0).title);
        assertEquals("Pink Floyd", l.tracks.get(0).artist);
        assertEquals(64, l.tracks.get(0).durationSec);
        assertEquals("https://open.spotify.com/track/574y1r7o2tRA009FW0LE7v", l.tracks.get(0).link);
    }

    @Test
    void playlistComVariosArtistasELimite() throws Exception {
        YtDlpJson.Listing l = SpotifyClient.parse(YtDlpJsonTest.fixture("spotify-playlist.html"), 50);
        assertEquals("Today’s Top Hits", l.title);
        assertEquals(12, l.tracks.size());
        boolean multi = false;
        for (IPodTrack t : l.tracks) {
            assertFalse(t.artist.contains(" "));
            if (t.artist.contains(", ")) multi = true;
        }
        assertTrue(multi, "a fixture tem faixas com mais de um artista");
        YtDlpJson.Listing cut = SpotifyClient.parse(YtDlpJsonTest.fixture("spotify-playlist.html"), 5);
        assertEquals(5, cut.tracks.size());
        assertEquals(7, cut.skipped);
    }

    @Test
    void naoAchadoEFormatoMudado() {
        assertThrows(
            SpotifyClient.NotFoundException.class,
            () -> SpotifyClient.parse(YtDlpJsonTest.fixture("spotify-404.html"), 50));
        assertThrows(IOException.class, () -> SpotifyClient.parse("<html>nada</html>", 50));
        assertThrows(IOException.class, () -> SpotifyClient.parse(null, 50));
        assertThrows(
            IOException.class,
            () -> SpotifyClient
                .parse("<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{}}</script>", 50));
        assertThrows(
            IOException.class,
            () -> SpotifyClient.parse("<script id=\"__NEXT_DATA__\" type=\"application/json\">{quebrado</script>", 50));
    }

    @Test
    void itensQueNaoSaoFaixaSaoPulados() throws Exception {
        String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"state\":{\"data\":"
            + "{\"entity\":{\"type\":\"playlist\",\"name\":\"P\",\"trackList\":["
            + "{\"uri\":\"spotify:episode:4uLU6hMCjMI75M1A2tKUQC\",\"title\":\"Podcast\",\"entityType\":\"episode\"},"
            + "{\"uri\":\"spotify:track:4uLU6hMCjMI75M1A2tKUQC\",\"title\":\"\",\"subtitle\":\"Sem título\"},"
            + "{\"uri\":\"spotify:local:abc\",\"title\":\"Local\"},"
            + "{\"uri\":\"spotify:track:574y1r7o2tRA009FW0LE7v\",\"title\":\"Ok\",\"subtitle\":\"A,\\u00a0B\",\"duration\":\"x\"}"
            + "]}}}}}}</script>";
        YtDlpJson.Listing l = SpotifyClient.parse(html, 50);
        assertEquals(1, l.tracks.size());
        assertEquals("A, B", l.tracks.get(0).artist);
        assertEquals(0, l.tracks.get(0).durationSec);
        assertEquals(3, l.skipped);
    }
}
