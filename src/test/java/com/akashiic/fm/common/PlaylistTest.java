package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlaylistTest {

    private static final List<String> S = Arrays.asList("http://a/", "http://b/", "http://c/");

    @Test
    void proximaEmLoop() {
        assertEquals("http://b/", Playlist.next(S, "http://a/"));
        assertEquals("http://c/", Playlist.next(S, "http://b/"));
        assertEquals("http://a/", Playlist.next(S, "http://c/"));
    }

    @Test
    void foraDaListaComecaPelaPrimeira() {
        assertEquals("http://a/", Playlist.next(S, "http://x/"));
        assertEquals("http://a/", Playlist.next(S, null));
        assertEquals("http://a/", Playlist.next(Collections.singletonList("http://a/"), "http://a/"));
        assertNull(Playlist.next(Collections.emptyList(), "http://a/"));
        assertNull(Playlist.next(null, "http://a/"));
    }

    @Test
    void esperaOFimSoarAntesDeTrocar() {
        // Terminou o download, mas o último áudio só termina de soar nos clientes em 10_000.
        assertEquals(Playlist.Decision.WAIT, Playlist.decide(true, false, 10_000, 9_000, 0, 0, 3));
        assertEquals(Playlist.Decision.ADVANCE, Playlist.decide(true, false, 10_000, 10_000, 0, 0, 3));
    }

    @Test
    void tocandoNaoTroca() {
        assertEquals(Playlist.Decision.WAIT, Playlist.decide(false, false, 0, 99_999, 0, 0, 3));
        assertEquals(Playlist.Decision.WAIT, Playlist.decide(true, false, 0, 99_999, 0, 0, 0));
    }

    @Test
    void intervaloMinimoEntreTrocas() {
        assertEquals(Playlist.Decision.WAIT, Playlist.decide(false, true, 0, 20_000, 16_000, 0, 3));
        assertEquals(Playlist.Decision.ADVANCE, Playlist.decide(false, true, 0, 21_000, 16_000, 0, 3));
    }

    @Test
    void todasFalhandoPara() {
        assertEquals(Playlist.Decision.ADVANCE, Playlist.decide(false, true, 0, 99_999, 0, 1, 3));
        assertEquals(Playlist.Decision.STOP, Playlist.decide(false, true, 0, 99_999, 0, 2, 3));
        assertEquals(Playlist.Decision.STOP, Playlist.decide(false, true, 0, 99_999, 0, 0, 1));
        // Fim normal não conta como falha.
        assertEquals(Playlist.Decision.ADVANCE, Playlist.decide(true, false, 0, 99_999, 0, 5, 1));
    }
}
