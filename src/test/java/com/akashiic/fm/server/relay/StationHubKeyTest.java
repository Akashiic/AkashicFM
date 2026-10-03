package com.akashiic.fm.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.audio.stream.MediaLocator;
import com.akashiic.fm.common.FmConfig;

/** Estações com chave (iPod): o áudio vem do registro, que some junto com a estação. */
class StationHubKeyTest {

    /** Nunca entrega URL: a estação fica tentando (sem rede) até fechar. */
    private static final MediaLocator NOWHERE = new MediaLocator() {

        @Override
        public String url() throws IOException {
            throw new IOException("teste");
        }

        @Override
        public String refresh() throws IOException {
            throw new IOException("teste");
        }
    };

    private final StationHub hub = new StationHub();
    private int savedMax, savedBitrate;

    @BeforeEach
    void setUp() {
        savedMax = FmConfig.Relay.maxStations;
        savedBitrate = FmConfig.Relay.opusBitrateKbps;
        FmConfig.Relay.maxStations = 2;
        FmConfig.Relay.opusBitrateKbps = 64;
    }

    @AfterEach
    void tearDown() {
        hub.closeAll();
        FmConfig.Relay.maxStations = savedMax;
        FmConfig.Relay.opusBitrateKbps = savedBitrate;
    }

    @Test
    void chaveSoAbreComRegistro() {
        assertTrue(StationHub.isKey("ipod:abc"));
        assertFalse(StationHub.isKey("https://radio.example.com/ipod:abc"));
        assertFalse(StationHub.isKey(null));
        assertNull(hub.acquire("ipod:semregistro", 0));
        hub.register("ipod:a", NOWHERE);
        assertTrue(hub.registered("ipod:a"));
        Station s = hub.acquire("ipod:a", 0);
        assertNotNull(s);
        assertEquals("ipod:a", s.url);
        assertSame(s, hub.acquire("ipod:a", 5));
        assertSame(s, hub.byId(s.id));
        assertThrows(IllegalArgumentException.class, () -> hub.register("https://radio.example.com/x", NOWHERE));
    }

    @Test
    void registrarDeNovoTrocaAEstacao() {
        hub.register("ipod:a", NOWHERE);
        Station first = hub.acquire("ipod:a", 0);
        hub.register("ipod:a", NOWHERE);
        assertNull(hub.byId(first.id));
        assertTrue(first.ring.isClosed());
        assertTrue(hub.registered("ipod:a"));
        Station second = hub.acquire("ipod:a", 0);
        assertNotSame(first, second);
    }

    @Test
    void estacaoQueFechaLevaORegistro() {
        hub.register("ipod:a", NOWHERE);
        hub.acquire("ipod:a", 0);
        hub.expire(StationHub.GRACE_MS + 1);
        assertNull(hub.get("ipod:a"));
        assertFalse(hub.registered("ipod:a")); // não recomeça a faixa sozinha
        assertNull(hub.acquire("ipod:a", 0));

        hub.register("ipod:b", NOWHERE);
        hub.acquire("ipod:b", 0);
        hub.restart("ipod:b");
        assertFalse(hub.registered("ipod:b"));

        hub.register("ipod:c", NOWHERE);
        hub.acquire("ipod:c", 0);
        hub.unregister("ipod:c");
        assertNull(hub.get("ipod:c"));
        assertFalse(hub.registered("ipod:c"));
        assertEquals(0, hub.size());
    }

    @Test
    void chavesContamNoLimiteDeEstacoes() {
        for (String k : new String[] { "ipod:1", "ipod:2", "ipod:3" }) hub.register(k, NOWHERE);
        assertNotNull(hub.acquire("ipod:1", 0));
        assertNotNull(hub.acquire("ipod:2", 0));
        assertNull(hub.acquire("ipod:3", 0));
        assertTrue(hub.registered("ipod:3")); // abre quando vagar
        hub.unregister("ipod:1");
        assertNotNull(hub.acquire("ipod:3", 0));
    }

    @Test
    void posicaoContaOsFramesQueJaSoaram() throws Exception {
        hub.register("ipod:a", NOWHERE);
        Station s = hub.acquire("ipod:a", 0);
        assertEquals(0, s.positionMs(ServerClock.nowMs(), 500));
        byte[] f = { 1, 2, 3 };
        for (int i = 0; i < 10; i++) assertTrue(s.ring.add(f, f.length, 2000, 500, () -> false));
        assertEquals(200, s.positionMs(ServerClock.nowMs() + 60_000, 500));
        assertEquals(0, s.positionMs(ServerClock.nowMs() - 60_000, 500));
        s.pause(Long.MIN_VALUE);
        assertTrue(s.paused());
        assertEquals(0, s.positionMs(ServerClock.nowMs() + 60_000, 500)); // nada foi enviado: tudo guardado
        s.resume();
        assertFalse(s.paused());
    }
}
