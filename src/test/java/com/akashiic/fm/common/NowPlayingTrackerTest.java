package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NowPlayingTrackerTest {

    private static final String HOST = "radio.example";

    @Test
    void mostraAoComecarAOuvirENaoRepete() {
        NowPlayingTracker t = new NowPlayingTracker();
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.5f, 1000));
        for (long ms = 1050; ms < 20_000; ms += 50) assertNull(t.update("relay:1", "Song A", HOST, 0.5f, ms));
    }

    @Test
    void tituloNovoDaMesmaEstacao() {
        NowPlayingTracker t = new NowPlayingTracker();
        t.update("relay:1", "Song A", HOST, 0.5f, 0);
        assertEquals("Song B", t.update("relay:1", "Song B", HOST, 0.5f, 60_000));
    }

    @Test
    void semTituloEsperaAntesDeMostrarAEstacao() {
        NowPlayingTracker t = new NowPlayingTracker();
        for (long ms = 0; ms < NowPlayingTracker.TITLE_GRACE_MS; ms += 50) {
            assertNull(t.update("direct:1", "", HOST, 0.5f, ms));
        }
        assertEquals(HOST, t.update("direct:1", "", HOST, 0.5f, NowPlayingTracker.TITLE_GRACE_MS));
    }

    @Test
    void tituloQueChegaNaEsperaSaiUmAvisoSo() {
        NowPlayingTracker t = new NowPlayingTracker();
        assertNull(t.update("relay:1", "", HOST, 0.5f, 0));
        assertNull(t.update("relay:1", "", HOST, 0.5f, 1500));
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.5f, 2000));
        for (long ms = 2050; ms < 15_000; ms += 50) assertNull(t.update("relay:1", "Song A", HOST, 0.5f, ms));
    }

    @Test
    void inaudivelNaoConta() {
        NowPlayingTracker t = new NowPlayingTracker();
        assertNull(t.update("relay:1", "Song A", HOST, 0.01f, 0));
        assertNull(t.update(null, "Song A", HOST, 1f, 50));
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.02f, 200));
    }

    @Test
    void intervaloMinimoDeixaPendenteESaiDepois() {
        NowPlayingTracker t = new NowPlayingTracker();
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.5f, 0));
        // Outra estação logo em seguida: espera o intervalo, depois mostra.
        assertNull(t.update("relay:2", "Other", HOST, 0.5f, 1000));
        assertNull(t.update("relay:2", "Other", HOST, 0.5f, 2999));
        assertEquals("Other", t.update("relay:2", "Other", HOST, 0.5f, 3000));
    }

    @Test
    void pendenteQueDeixouDeTocarNaoSai() {
        NowPlayingTracker t = new NowPlayingTracker();
        t.update("relay:1", "Song A", HOST, 0.5f, 0);
        assertNull(t.update("relay:2", "Other", HOST, 0.5f, 500)); // pendente
        assertNull(t.update("relay:1", "Song A", HOST, 0.5f, 600)); // voltou para a 1: o pendente vira "Song A"
        // Trocar de estação esquece o que foi mostrado: a volta para a 1 mostra de novo, uma vez.
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.5f, 3000));
        for (long ms = 3050; ms < 9000; ms += 50) assertNull(t.update("relay:1", "Song A", HOST, 0.5f, ms));
    }

    @Test
    void esqueceQuemFicouSemOuvirEMostraDeNovo() {
        NowPlayingTracker t = new NowPlayingTracker();
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.5f, 0));
        assertNull(t.update(null, null, null, 0f, 5_000));
        assertNull(t.update("relay:1", "Song A", HOST, 0.5f, 6_000)); // voltou antes de esquecer
        assertNull(t.update(null, null, null, 0f, 6_050));
        assertNull(t.update(null, null, null, 0f, 17_000)); // > 10 s sem ouvir
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.5f, 17_050));
    }

    @Test
    void resetEsquece() {
        NowPlayingTracker t = new NowPlayingTracker();
        t.update("relay:1", "Song A", HOST, 0.5f, 0);
        t.reset();
        assertEquals("Song A", t.update("relay:1", "Song A", HOST, 0.5f, 5_000));
    }
}
