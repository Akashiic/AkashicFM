package com.akashiic.fm.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileTransmitter;

/** O que um computador do OpenComputers faz com rádio e transmissor, sem o OC (a lógica é toda daqui). */
class RadioScriptingTest {

    private final List<String> audited = new ArrayList<>();
    private static final UUID STEVE = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @BeforeEach
    void config() {
        FmConfig.Relay.enabled = false;
        FmConfig.Direct.enabled = true;
        FmConfig.Policy.allowedHosts = new String[0];
        FmConfig.Policy.allowHighPorts = true;
        FmConfig.OpenComputers.allowPrivate = false;
        FmConfig.Transmitter.requireEnergy = false;
        RadioScripting.clear();
        RadioScripting.audit = (actor, action, details) -> audited.add(actor + " " + action + " " + details);
        RadioScripting.blocked = id -> false;
    }

    @AfterEach
    void reset() {
        FmConfig.Direct.enabled = false;
        RadioScripting.clear();
        RadioScripting.audit = (actor, action, details) -> AuditLog.log(actor, null, action, details);
        RadioScripting.blocked = Moderation::isBlocked;
    }

    private static TileRadio radio(int x) {
        TileRadio r = new TileRadio(); // sem mundo: markStateChanged não faz nada, a lógica é a mesma
        r.xCoord = x;
        return r;
    }

    private static boolean refused(Object[] result) {
        return result.length == 2 && Boolean.FALSE.equals(result[0]) && result[1] instanceof String;
    }

    @Test
    void soPublicaOuSemDonoAceitaComputador() {
        TileRadio r = radio(1);
        assertArrayEquals(new Object[] { 0.5 }, RadioScripting.setVol(r, 5)); // sem dono
        r.state.owner = STEVE;
        r.state.access = RadioAccess.PRIVATE;
        Object[] no = RadioScripting.setVol(r, 7);
        assertTrue(refused(no), "privada recusada");
        assertEquals(50, r.state.volume);
        r.state.access = RadioAccess.PUBLIC;
        assertArrayEquals(new Object[] { 0.7 }, RadioScripting.setVol(r, 7));
        // Pública de alguém: controla, mas tela e redstone são do dono (como para um jogador qualquer).
        assertTrue(refused(RadioScripting.setScreenText(r, "invadida")));
        assertTrue(refused(RadioScripting.setListenRedstone(r, true)));
        assertEquals("", r.state.screenText);
        r.state.access = RadioAccess.PRIVATE;
        FmConfig.OpenComputers.allowPrivate = true; // o admin permitiu
        assertArrayEquals(new Object[] { 0.8 }, RadioScripting.setVol(r, 8));
        RadioScripting.blocked = id -> true; // dono bloqueado: nem com a permissão
        assertTrue(refused(RadioScripting.setVol(r, 9)));
        assertEquals(80, r.state.volume);
        // Ler não muda nada: vale mesmo recusado para mudar.
        assertArrayEquals(new Object[] { 0.8 }, RadioScripting.getVol(r));
    }

    @Test
    void escalaDeVolumeDoOpenFM() {
        TileRadio r = radio(2);
        assertArrayEquals(new Object[] { 1.0 }, RadioScripting.setVol(r, 10)); // o OpenFM recusava o 10
        assertEquals(100, r.state.volume);
        assertArrayEquals(new Object[] { false }, RadioScripting.volUp(r)); // já no máximo
        assertArrayEquals(new Object[] { 0.9 }, RadioScripting.volDown(r));
        assertArrayEquals(new Object[] { false }, RadioScripting.setVol(r, 10.5));
        assertArrayEquals(new Object[] { false }, RadioScripting.setVol(r, -1));
        assertArrayEquals(new Object[] { false }, RadioScripting.setVol(r, Double.NaN));
        assertArrayEquals(new Object[] { 0.0 }, RadioScripting.setVol(r, 0));
        assertArrayEquals(new Object[] { false }, RadioScripting.volDown(r)); // já no mínimo
        assertEquals(0, r.state.volume);
    }

    @Test
    void urlPassaPelaPoliticaEVaiParaAAuditoria() {
        TileRadio r = radio(3);
        FmConfig.Policy.allowedHosts = new String[] { "example.com" };
        Object[] no = RadioScripting.setURL(r, "http://other.org/live", "oc:abc");
        assertTrue(refused(no));
        assertTrue(((String) no[1]).contains("not_allowed"), String.valueOf(no[1]));
        assertTrue(refused(RadioScripting.setURL(r, "http://10.0.0.1/live", "oc:abc"))); // interno
        assertTrue(refused(RadioScripting.setURL(r, "", "oc:abc")));
        assertEquals("", r.state.url);
        assertTrue(audited.isEmpty());
        // O OC entrega strings do Lua como bytes.
        byte[] lua = "http://stream.example.com/live".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(new Object[] { true }, RadioScripting.setURL(r, lua, "oc:abc"));
        assertEquals("http://stream.example.com/live", r.state.url);
        assertEquals(1, audited.size());
        assertTrue(
            audited.get(0)
                .startsWith("oc:abc radio.url "),
            audited.get(0));
        assertArrayEquals(new Object[] { true }, RadioScripting.setURL(r, lua, "oc:abc")); // a mesma: sem linha nova
        assertEquals(1, audited.size());
        assertArrayEquals(new Object[] { "http://stream.example.com/live" }, RadioScripting.getURL(r));
    }

    @Test
    void tocarEParar() {
        TileRadio r = radio(4);
        assertTrue(refused(RadioScripting.start(r))); // sem URL
        assertFalse(r.state.playing);
        RadioScripting.setURL(r, "http://stream.example.com/live", "oc:abc");
        assertArrayEquals(new Object[] { true }, RadioScripting.start(r));
        assertArrayEquals(new Object[] { true }, RadioScripting.isPlaying(r));
        assertArrayEquals(new Object[] { true }, RadioScripting.stop(r));
        assertArrayEquals(new Object[] { false }, RadioScripting.isPlaying(r));
    }

    @Test
    void limiteDeMudancasPorSegundoPorBloco() {
        TileRadio r = radio(5), other = radio(6);
        int accepted = 0;
        for (int i = 0; i < 20; i++) if (!refused(RadioScripting.setScreenColor(r, i))) accepted++;
        // Rajada de até o dobro do limite por segundo, depois recusa.
        assertEquals(RadioScripting.CHANGES_PER_SECOND * 2, accepted);
        Object[] no = RadioScripting.setScreenColor(r, 99);
        assertTrue(refused(no));
        assertEquals("too many changes per second", no[1]);
        assertArrayEquals(new Object[] { true }, RadioScripting.setScreenColor(other, 1)); // outro bloco, outra conta
        assertArrayEquals(new Object[] { 7 }, RadioScripting.getScreenColor(r)); // a última aceita
    }

    @Test
    void redstoneTelaEModo() {
        TileRadio r = radio(7);
        assertArrayEquals(new Object[] { true }, RadioScripting.setListenRedstone(r, true));
        assertEquals(RedstoneMode.WHILE_POWERED, r.state.redstoneMode);
        assertArrayEquals(new Object[] { false }, RadioScripting.setListenRedstone(r, false));
        assertEquals(RedstoneMode.IGNORE, r.state.redstoneMode);
        assertArrayEquals(new Object[] { true }, RadioScripting.setScreenText(r, "Rock\nFM§c"));
        assertFalse(r.state.screenText.contains("\n"));
        assertArrayEquals(new Object[] { "fm" }, RadioScripting.setMode(r, "FM"));
        assertEquals(TuneMode.FREQUENCY, r.state.mode);
        assertTrue(refused(RadioScripting.setMode(r, "am")));
        assertArrayEquals(new Object[] { 98.7 }, RadioScripting.setFrequency(r, 98.7));
        assertEquals(987, r.state.frequency);
        assertArrayEquals(new Object[] { 108.0 }, RadioScripting.setFrequency(r, 250)); // preso na faixa
        assertTrue(refused(RadioScripting.setFrequency(r, Double.NaN)));
        assertArrayEquals(new Object[] { RadioScripting.GREETING }, RadioScripting.greet());
    }

    @Test
    void transmissor() {
        TileTransmitter t = new TileTransmitter();
        t.xCoord = 8;
        assertTrue(refused(RadioScripting.start(t))); // sem URL
        assertArrayEquals(new Object[] { true }, RadioScripting.setURL(t, "http://stream.example.com/x", "oc:def"));
        assertArrayEquals(new Object[] { 98.7 }, RadioScripting.setFrequency(t, 98.7, "oc:def"));
        assertEquals(987, t.state.frequency);
        assertEquals(2, audited.size());
        assertTrue(
            audited.get(1)
                .contains("transmitter.frequency"),
            audited.get(1));
        assertArrayEquals(new Object[] { true }, RadioScripting.setName(t, "Akashic FM"));
        assertArrayEquals(new Object[] { true }, RadioScripting.start(t));
        assertTrue(t.state.broadcasting);
        assertArrayEquals(new Object[] { false }, RadioScripting.getEnergy(t)); // energia não exigida
        assertArrayEquals(new Object[] { true }, RadioScripting.stop(t));
        assertFalse(t.state.broadcasting);
        t.state.owner = STEVE; // privado de alguém
        assertTrue(refused(RadioScripting.start(t)));
        assertFalse(t.state.broadcasting);
    }
}
