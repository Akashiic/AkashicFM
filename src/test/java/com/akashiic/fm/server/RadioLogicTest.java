package com.akashiic.fm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.content.TileRadio;

/** Lógica do servidor que não precisa de mundo: tocar, favoritas e redstone. */
class RadioLogicTest {

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
        ServerPolicy.setRelayAvailable(false);
    }

    @Test
    void transportePreferidoERelaySeDisponivel() {
        assertEquals(Transport.DIRECT, ServerPolicy.chooseTransport("http://stream.example.com/live"));
        ServerPolicy.setRelayAvailable(true);
        assertEquals(Transport.RELAY, ServerPolicy.chooseTransport("http://stream.example.com/live"));
        FmConfig.Relay.enabled = false;
        FmConfig.Direct.enabled = false;
        assertEquals(Transport.NONE, ServerPolicy.chooseTransport("http://stream.example.com/live"));
    }

    @Test
    void tocar() {
        RadioState s = new RadioState();
        assertEquals(RadioActionHandler.PlayResult.NO_URL, RadioActionHandler.applyPlay(s));
        s.url = "http://stream.example.com/live";
        assertEquals(RadioActionHandler.PlayResult.CHANGED, RadioActionHandler.applyPlay(s));
        assertTrue(s.playing);
        assertEquals(Transport.DIRECT, s.transport);
        assertEquals(1, s.session);
        assertEquals(RadioActionHandler.PlayResult.UNCHANGED, RadioActionHandler.applyPlay(s));
        assertEquals(1, s.session);
    }

    @Test
    void semTransporteNaoToca() {
        FmConfig.Direct.enabled = false;
        RadioState s = new RadioState();
        s.url = "http://stream.example.com/live";
        assertEquals(RadioActionHandler.PlayResult.NO_TRANSPORT, RadioActionHandler.applyPlay(s));
        assertFalse(s.playing);
    }

    @Test
    void urlForaDaPoliticaAtualNaoToca() {
        // Ex.: veio no NBT do item, ou a allowlist mudou depois de a URL ser salva.
        RadioState s = new RadioState();
        s.url = "http://10.0.0.1/";
        assertEquals(RadioActionHandler.PlayResult.REJECTED, RadioActionHandler.applyPlay(s));
        s.url = "http://other.com/live";
        FmConfig.Policy.allowedHosts = new String[] { "example.com" };
        assertEquals(RadioActionHandler.PlayResult.REJECTED, RadioActionHandler.applyPlay(s));
        assertFalse(s.playing);
    }

    @Test
    void favoritaConfereAUrlQueOClienteViu() {
        RadioState s = new RadioState();
        s.stations.addAll(Arrays.asList("http://a.com/", "http://b.com/"));
        assertTrue(RadioActionHandler.isStationAt(s, 1, "http://b.com/"));
        assertTrue(RadioActionHandler.isStationAt(s, 1, ""));
        assertFalse(RadioActionHandler.isStationAt(s, 1, "http://a.com/")); // a lista mudou no meio
        assertFalse(RadioActionHandler.isStationAt(s, 2, ""));
        assertFalse(RadioActionHandler.isStationAt(s, -1, ""));
    }

    private static TileRadio radio(RedstoneMode mode) {
        TileRadio r = new TileRadio(); // sem mundo: markStateChanged não faz nada, a lógica é a mesma
        r.state.url = "http://stream.example.com/live";
        r.state.redstoneMode = mode;
        return r;
    }

    @Test
    void redstoneEnquantoLigada() {
        TileRadio r = radio(RedstoneMode.WHILE_POWERED);
        RadioActionHandler.onRedstone(r, true);
        assertTrue(r.state.playing);
        RadioActionHandler.onRedstone(r, true); // mesmo nível: nada muda
        assertEquals(1, r.state.session);
        RadioActionHandler.onRedstone(r, false);
        assertFalse(r.state.playing);
    }

    @Test
    void redstoneAlternaSoNaSubida() {
        TileRadio r = radio(RedstoneMode.TOGGLE_ON_PULSE);
        RadioActionHandler.onRedstone(r, true);
        assertTrue(r.state.playing);
        RadioActionHandler.onRedstone(r, false); // descida não alterna
        assertTrue(r.state.playing);
        RadioActionHandler.onRedstone(r, true);
        assertFalse(r.state.playing);
    }

    @Test
    void redstoneIgnorada() {
        TileRadio r = radio(RedstoneMode.IGNORE);
        RadioActionHandler.onRedstone(r, true);
        assertFalse(r.state.playing);
        assertTrue(r.state.lastPowered);
    }
}
