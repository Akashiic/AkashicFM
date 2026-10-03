package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IPodTrackTest {

    @Test
    void textoDeForaELimpoELimitado() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 300; i++) longTitle.append('a');
        IPodTrack t = new IPodTrack(
            IPodTrack.Source.YOUTUBE,
            " https://www.youtube.com/watch?v=jNQXAC9IVRw ",
            "§cVermelho‮" + longTitle,
            "Canal\u0000",
            90);
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", t.link);
        assertTrue(t.title.startsWith("Vermelhoaaa"));
        assertEquals(IPodTrack.MAX_TITLE, t.title.length());
        assertEquals("Canal", t.artist);
        assertEquals(90, t.durationSec);
        assertTrue(t.valid());
    }

    @Test
    void linkLongoDemaisInvalidaEDuracaoForaDoLimiteViraDesconhecida() {
        StringBuilder link = new StringBuilder("https://soundcloud.com/");
        while (link.length() <= IPodTrack.MAX_LINK) link.append('x');
        IPodTrack t = new IPodTrack(IPodTrack.Source.SOUNDCLOUD, link.toString(), "t", "a", -5);
        assertFalse(t.valid());
        assertEquals("", t.link);
        assertEquals(0, t.durationSec);
        assertEquals(
            0,
            new IPodTrack(
                IPodTrack.Source.SOUNDCLOUD,
                "https://soundcloud.com/a",
                "t",
                "a",
                24 * 3600 + 1).durationSec);
        assertThrows(IllegalArgumentException.class, () -> new IPodTrack(null, "x", "t", "a", 1));
    }

    @Test
    void exibicaoNaoRepeteOArtista() {
        assertEquals(
            "Forss - Flickermood",
            new IPodTrack(IPodTrack.Source.SOUNDCLOUD, "l", "Flickermood", "Forss", 1).display());
        assertEquals(
            "Arcángel - FN8",
            new IPodTrack(IPodTrack.Source.YOUTUBE, "l", "Arcángel - FN8", "Arcangel", 1).display());
        assertEquals("Só título", new IPodTrack(IPodTrack.Source.SPOTIFY, "l", "Só título", "", 1).display());
        assertEquals("https://x", new IPodTrack(IPodTrack.Source.SPOTIFY, "https://x", "", "", 1).display());
    }

    @Test
    void origemPorIndice() {
        assertEquals(IPodTrack.Source.SPOTIFY, IPodTrack.Source.byOrdinal(2));
        assertNull(IPodTrack.Source.byOrdinal(3));
        assertNull(IPodTrack.Source.byOrdinal(-1));
    }
}
