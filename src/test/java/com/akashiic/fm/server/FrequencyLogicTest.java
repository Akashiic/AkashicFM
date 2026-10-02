package com.akashiic.fm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.FrequencyResolver;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileTransmitter;

/** Rádio sintonizada (modo FREQUENCY) e transmissor, sem mundo. */
class FrequencyLogicTest {

    private static final String A = "http://a.example.com/live", B = "http://b.example.com/live";

    @BeforeEach
    void config() {
        FmConfig.Relay.enabled = true;
        FmConfig.Direct.enabled = true;
        FmConfig.Policy.allowedHosts = new String[0];
        FmConfig.Policy.allowHighPorts = true;
        ServerPolicy.setRelayAvailable(false);
    }

    @AfterEach
    void reset() {
        FmConfig.Relay.enabled = false;
        FmConfig.Direct.enabled = false;
        FmConfig.Policy.allowedHosts = new String[0];
        ServerPolicy.setRelayAvailable(false);
        FrequencyService.clear();
    }

    private static FrequencyResolver.Tuning tuning(String url, String name, double x) {
        FrequencyResolver.Transmitter t = new FrequencyResolver.Transmitter(
            new Pos(0, 64, 0),
            987,
            100,
            url,
            name,
            true);
        return FrequencyResolver.resolve(Collections.singletonList(t), 987, x, 64.5, 0.5, null);
    }

    private static RadioState tuned() {
        RadioState s = new RadioState();
        s.mode = TuneMode.FREQUENCY;
        s.frequency = 987;
        return s;
    }

    @Test
    void ligarSintonizadaNaoPrecisaDeUrl() {
        RadioState s = tuned();
        assertEquals(RadioActionHandler.PlayResult.CHANGED, RadioActionHandler.applyPlay(s));
        assertTrue(s.playing);
        assertEquals(Transport.NONE, s.transport); // até sintonizar
        assertEquals(1, s.session);
        assertEquals(RadioActionHandler.PlayResult.UNCHANGED, RadioActionHandler.applyPlay(s));
        assertEquals(1, s.session);
    }

    @Test
    void semSinalFicaLigadaEmSilencio() {
        RadioState s = tuned();
        RadioActionHandler.applyPlay(s);
        assertTrue(FrequencyService.apply(s, null));
        assertTrue(s.playing);
        assertEquals("", s.effectiveUrl());
        assertEquals(Transport.NONE, s.transport);
        assertEquals(FrequencyService.NO_SIGNAL, s.status);
        assertFalse(FrequencyService.apply(s, null)); // nada mudou: nenhum pacote
    }

    @Test
    void pegaOTransmissorETrocaDeSessaoSoQuandoAFonteMuda() {
        RadioState s = tuned();
        RadioActionHandler.applyPlay(s);
        FrequencyService.apply(s, null);
        int session = s.session;

        assertTrue(FrequencyService.apply(s, tuning(A, "Rádio A", 10.5)));
        assertEquals(A, s.effectiveUrl());
        assertEquals(Transport.DIRECT, s.transport);
        assertEquals("", s.status);
        assertEquals("Rádio A", s.tunedName);
        assertEquals(90, s.signal); // 1 − 10/100
        assertEquals(session + 1, s.session);

        // Andou um pouco: sinal mudou menos que o passo, nada vai para a rede.
        assertFalse(FrequencyService.apply(s, tuning(A, "Rádio A", 12.5)));
        assertEquals(90, s.signal);
        // Andou bastante: só o sinal muda (mesma fonte, mesma sessão).
        assertTrue(FrequencyService.apply(s, tuning(A, "Rádio A", 40.5)));
        assertEquals(60, s.signal);
        assertEquals(session + 1, s.session);

        // Outro transmissor (outra URL): fonte nova, título da anterior apagado.
        s.nowPlaying = "Música da A";
        assertTrue(FrequencyService.apply(s, tuning(B, "Rádio B", 40.5)));
        assertEquals(B, s.effectiveUrl());
        assertEquals("", s.nowPlaying);
        assertEquals(session + 2, s.session);

        // Perdeu o sinal: sessão nova (para de tocar), status de sem sinal.
        assertTrue(FrequencyService.apply(s, null));
        assertEquals(session + 3, s.session);
        assertEquals(FrequencyService.NO_SIGNAL, s.status);
        assertEquals(0, s.signal);
    }

    @Test
    void urlDoTransmissorPassaPelaPoliticaDoServidor() {
        RadioState s = tuned();
        RadioActionHandler.applyPlay(s);
        FmConfig.Policy.allowedHosts = new String[] { "b.example.com" };
        assertTrue(FrequencyService.apply(s, tuning(A, "", 10.5)));
        assertEquals(Transport.NONE, s.transport);
        assertTrue(s.status.startsWith("akashicfm.policy.not_allowed"), s.status);
        // Liberou no config: volta a tocar e o status de política some.
        FmConfig.Policy.allowedHosts = new String[0];
        assertTrue(FrequencyService.apply(s, tuning(A, "", 10.5)));
        assertEquals(Transport.DIRECT, s.transport);
        assertEquals("", s.status);
    }

    @Test
    void statusDoRelayNaoEApagadoPelaSintonia() {
        RadioState s = tuned();
        RadioActionHandler.applyPlay(s);
        FrequencyService.apply(s, tuning(A, "", 10.5));
        s.status = "akashicfm.status.reconnecting"; // escrito pelo relay
        assertFalse(FrequencyService.apply(s, tuning(A, "", 10.5)));
        assertEquals("akashicfm.status.reconnecting", s.status);
    }

    @Test
    void statusDaFonteAnteriorSomeNaTroca() {
        RadioState s = tuned();
        RadioActionHandler.applyPlay(s);
        FrequencyService.apply(s, tuning(A, "", 10.5));
        s.status = "akashicfm.status.error|timeout"; // erro da estação A
        assertTrue(FrequencyService.apply(s, tuning(B, "", 10.5)));
        assertEquals("", s.status);
    }

    @Test
    void semTransporteNoServidor() {
        FmConfig.Direct.enabled = false;
        FmConfig.Relay.enabled = false;
        RadioState s = tuned();
        RadioActionHandler.applyPlay(s);
        FrequencyService.apply(s, tuning(A, "", 10.5));
        assertEquals(Transport.NONE, s.transport);
        assertEquals(FrequencyService.NO_TRANSPORT, s.status);
        assertTrue(s.playing);
    }

    @Test
    void trocarDeModoTocandoRecomecaComAFonteNova() {
        TileRadio r = new TileRadio(); // sem mundo: a sintonia na hora não acha nada
        RadioState s = r.state;
        s.url = A;
        RadioActionHandler.applyPlay(s);
        assertEquals(Transport.DIRECT, s.transport);
        int session = s.session;

        assertTrue(RadioActionHandler.setMode(r, TuneMode.FREQUENCY));
        assertTrue(s.playing);
        assertEquals("", s.effectiveUrl());
        assertTrue(s.session > session);
        session = s.session;

        assertTrue(RadioActionHandler.setMode(r, TuneMode.URL));
        assertTrue(s.playing);
        assertEquals(A, s.effectiveUrl());
        assertEquals(Transport.DIRECT, s.transport);
        assertTrue(s.session > session);
        assertFalse(RadioActionHandler.setMode(r, TuneMode.URL));
    }

    @Test
    void voltarParaUrlSemUrlPara() {
        TileRadio r = new TileRadio();
        RadioState s = r.state;
        s.mode = TuneMode.FREQUENCY;
        RadioActionHandler.applyPlay(s);
        assertTrue(RadioActionHandler.setMode(r, TuneMode.URL));
        assertFalse(s.playing);
        assertEquals(Transport.NONE, s.transport);
    }

    @Test
    void redstoneLigaASintonizada() {
        TileRadio r = new TileRadio();
        r.state.mode = TuneMode.FREQUENCY;
        r.state.redstoneMode = RedstoneMode.WHILE_POWERED;
        RadioActionHandler.onRedstone(r, true);
        assertTrue(r.state.playing);
        RadioActionHandler.onRedstone(r, false);
        assertFalse(r.state.playing);
    }

    @Test
    void transmissorSoTransmiteComUrlAceita() {
        TransmitterState s = new TransmitterState();
        assertFalse(TransmitterActionHandler.applyBroadcast(s));
        s.url = "http://10.0.0.1/live";
        assertFalse(TransmitterActionHandler.applyBroadcast(s));
        assertFalse(s.broadcasting);
        s.url = A;
        assertTrue(TransmitterActionHandler.applyBroadcast(s));
        assertTrue(s.broadcasting);
    }

    @Test
    void transmissorPorRedstone() {
        TileTransmitter t = new TileTransmitter();
        t.state.url = A;
        t.state.redstoneMode = RedstoneMode.TOGGLE_ON_PULSE;
        TransmitterActionHandler.onRedstone(t, true);
        assertTrue(t.state.broadcasting);
        TransmitterActionHandler.onRedstone(t, false);
        assertTrue(t.state.broadcasting);
        TransmitterActionHandler.onRedstone(t, true);
        assertFalse(t.state.broadcasting);
    }
}
