package com.akashiic.fm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.PortableState;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.network.C2SPortableAction.Action;

/** Ações do rádio portátil sobre o estado do item (sem jogador: sem avisos). */
class PortableLogicTest {

    private static final String A = "http://a.example.com/live";

    @BeforeEach
    void config() {
        FmConfig.Policy.allowedHosts = new String[0];
        FmConfig.Policy.allowHighPorts = true;
    }

    @AfterEach
    void reset() {
        FmConfig.Policy.allowedHosts = new String[0];
    }

    private static boolean act(PortableState s, Action a, int i, String str) {
        return PortableActionHandler.apply(null, s, a, i, str);
    }

    @Test
    void ligarSemUrlNaoLiga() {
        PortableState s = new PortableState();
        assertFalse(act(s, Action.TURN_ON, 0, ""));
        assertFalse(s.on);
    }

    @Test
    void urlLigarDesligar() {
        PortableState s = new PortableState();
        assertTrue(act(s, Action.SET_URL, 0, A));
        assertEquals(A, s.url);
        assertEquals(0, s.session); // desligado: trocar a URL não é fonte nova
        assertTrue(act(s, Action.TURN_ON, 0, ""));
        assertTrue(s.on);
        assertEquals(1, s.session);
        assertFalse(act(s, Action.TURN_ON, 0, "")); // já ligado
        assertTrue(act(s, Action.SET_URL, 0, "http://b.example.com/live"));
        assertEquals(2, s.session); // troca ao vivo
        assertTrue(act(s, Action.TURN_OFF, 0, ""));
        assertFalse(s.on);
    }

    @Test
    void urlRecusadaPelaPolitica() {
        PortableState s = new PortableState();
        assertFalse(act(s, Action.SET_URL, 0, "http://10.0.0.1/live"));
        assertEquals("", s.url);
        FmConfig.Policy.allowedHosts = new String[] { "b.example.com" };
        assertFalse(act(s, Action.SET_URL, 0, A));
        // URL que já estava no item (de antes da allowlist, ou do criativo) não liga.
        s.url = A;
        assertFalse(act(s, Action.TURN_ON, 0, ""));
        assertFalse(s.on);
    }

    @Test
    void apagarAUrlDesligaNoModoUrl() {
        PortableState s = new PortableState();
        act(s, Action.SET_URL, 0, A);
        act(s, Action.TURN_ON, 0, "");
        assertTrue(act(s, Action.SET_URL, 0, ""));
        assertFalse(s.on);
    }

    @Test
    void sintonizadoLigaSemUrlETrocaDeModo() {
        PortableState s = new PortableState();
        assertTrue(act(s, Action.SET_MODE, TuneMode.FREQUENCY.ordinal(), ""));
        assertTrue(act(s, Action.TURN_ON, 0, ""));
        assertTrue(s.on);
        int session = s.session;
        // Voltar para URL sem URL: desliga.
        assertTrue(act(s, Action.SET_MODE, TuneMode.URL.ordinal(), ""));
        assertFalse(s.on);
        assertTrue(s.session > session);
        // Com URL: continua ligado, sessão nova.
        act(s, Action.SET_URL, 0, A);
        act(s, Action.SET_MODE, TuneMode.FREQUENCY.ordinal(), "");
        act(s, Action.TURN_ON, 0, "");
        session = s.session;
        assertTrue(act(s, Action.SET_MODE, TuneMode.URL.ordinal(), ""));
        assertTrue(s.on);
        assertEquals(session + 1, s.session);
        assertFalse(act(s, Action.SET_MODE, 77, "")); // inválido vira URL: sem mudança
    }

    @Test
    void frequenciaEVolumeLimitados() {
        PortableState s = new PortableState();
        assertTrue(act(s, Action.SET_FREQUENCY, 5000, ""));
        assertEquals(Frequency.MAX, s.frequency);
        assertFalse(act(s, Action.SET_FREQUENCY, 5000, ""));
        assertTrue(act(s, Action.SET_VOLUME, -10, ""));
        assertEquals(RadioLimits.VOLUME_MIN, s.volume);
        assertTrue(act(s, Action.SET_VOLUME, 1000, ""));
        assertEquals(RadioLimits.VOLUME_MAX, s.volume);
    }

    @Test
    void identidadeNuncaZero() {
        for (int i = 0; i < 1000; i++) assertTrue(PortableActionHandler.newId() != 0);
    }
}
