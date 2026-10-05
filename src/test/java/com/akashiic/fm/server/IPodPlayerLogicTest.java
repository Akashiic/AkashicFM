package com.akashiic.fm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileIPodPlayer;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.network.C2SRadioAction;
import com.akashiic.fm.server.ipod.IPodActionHandler;

/** O bloco do iPod sem mundo: o modo preso, as ações de rádio que valem nele, redstone, ligar/desligar e o NBT. */
class IPodPlayerLogicTest {

    static {
        // Fora do jogo ninguém registrou os tiles: sem o nome, o writeToNBT do 1.7.10 recusa.
        try {
            TileEntity.addMapping(TileIPodPlayer.class, "akashicfm:ipod_player");
        } catch (IllegalArgumentException ignored) {}
    }

    @BeforeEach
    void config() {
        FmConfig.IPod.enabled = true;
        FmConfig.IPod.maxQueue = 200;
    }

    @AfterEach
    void reset() {
        FmConfig.IPod.enabled = false;
    }

    private static TileIPodPlayer block(int tracks) {
        TileIPodPlayer t = new TileIPodPlayer();
        t.xCoord = 10;
        t.yCoord = 64;
        t.zCoord = -3;
        List<IPodTrack> l = new ArrayList<>();
        for (int i = 0; i < tracks; i++)
            l.add(new IPodTrack(IPodTrack.Source.SOUNDCLOUD, "https://soundcloud.com/a/" + i, "t" + i, "a", 60));
        t.ipod.append(l, 200);
        t.ipod.id = 77;
        return t;
    }

    @Test
    void modoPresoNoBloco() {
        RadioState s = new RadioState(true);
        assertEquals(TuneMode.IPOD, s.mode);
        s.mode = TuneMode.URL; // um NBT de fora tentando virar rádio comum
        s.url = "http://stream.example.com/live";
        s.stations.add("http://stream.example.com/live");
        s.playlist = true;
        s.tunedUrl = "ipod:b0_1_2_3";
        s.sanitize(RadioLimits.RANGE_HARD_MAX, RadioLimits.MAX_SPEAKERS_HARD);
        assertEquals(TuneMode.IPOD, s.mode);
        assertEquals("", s.url);
        assertTrue(s.stations.isEmpty());
        assertFalse(s.playlist);
        assertEquals("ipod:b0_1_2_3", s.effectiveUrl());
    }

    @Test
    void radioComumNuncaFicaNoModoDoIPod() {
        NBTTagCompound tag = new NBTTagCompound();
        RadioState fromBlock = new RadioState(true);
        fromBlock.tunedUrl = "ipod:b0_1_2_3";
        fromBlock.playing = true;
        fromBlock.writeToNbt(tag, false);
        RadioState radio = new RadioState();
        radio.readFromNbt(tag, RadioLimits.RANGE_HARD_MAX, RadioLimits.MAX_SPEAKERS_HARD);
        assertEquals(TuneMode.URL, radio.mode);
        assertEquals("", radio.effectiveUrl()); // sem URL: nada de tocar a estação do bloco
        assertFalse(radio.playing);
    }

    @Test
    void modosQueOJogadorEscolhe() {
        assertEquals(TuneMode.URL, TuneMode.tunable(TuneMode.URL.ordinal()));
        assertEquals(TuneMode.FREQUENCY, TuneMode.tunable(TuneMode.FREQUENCY.ordinal()));
        assertEquals(TuneMode.URL, TuneMode.tunable(TuneMode.IPOD.ordinal()));
        assertEquals(TuneMode.URL, TuneMode.tunable(-1));
        assertEquals(TuneMode.URL, TuneMode.tunable(200));
        // Nenhum caminho de rádio troca o modo do bloco nem põe uma rádio no modo do iPod.
        TileIPodPlayer b = block(1);
        assertFalse(RadioActionHandler.setMode(b, TuneMode.URL));
        assertFalse(RadioActionHandler.setMode(b, TuneMode.FREQUENCY));
        assertEquals(TuneMode.IPOD, b.state.mode);
        TileRadio r = new TileRadio();
        assertFalse(RadioActionHandler.setMode(r, TuneMode.IPOD));
        assertEquals(TuneMode.URL, r.state.mode);
    }

    @Test
    void soAsAcoesDeRadioQueValemNoBloco() {
        Set<C2SRadioAction.Action> allowed = EnumSet.of(
            C2SRadioAction.Action.REQUEST_PERMS,
            C2SRadioAction.Action.STOP,
            C2SRadioAction.Action.SET_VOLUME,
            C2SRadioAction.Action.SET_RANGE,
            C2SRadioAction.Action.SET_SCREEN_TEXT,
            C2SRadioAction.Action.SET_SCREEN_COLOR,
            C2SRadioAction.Action.SET_ACCESS,
            C2SRadioAction.Action.SET_REDSTONE_MODE,
            C2SRadioAction.Action.UNLINK_SPEAKER,
            C2SRadioAction.Action.UNLINK_ALL_SPEAKERS);
        for (C2SRadioAction.Action a : C2SRadioAction.Action.values())
            assertEquals(allowed.contains(a), RadioActionHandler.ipodPlayerAction(a), a.name());
    }

    @Test
    void tocarDeRadioNaoLigaOBloco() {
        TileIPodPlayer b = block(3);
        assertEquals(RadioActionHandler.PlayResult.UNCHANGED, RadioActionHandler.applyPlay(b.state));
        assertFalse(b.state.playing);
        assertEquals(Transport.NONE, b.state.transport);
    }

    @Test
    void ligarEDesligarPelaFila() {
        TileIPodPlayer b = block(3);
        b.ipod.play(1);
        b.commitIPod();
        assertTrue(b.state.playing);
        assertEquals(Transport.RELAY, b.state.transport);
        assertEquals("ipod:b0_10_64_-3", b.state.tunedUrl);
        assertEquals(b.state.tunedUrl, b.state.effectiveUrl());
        int session = b.state.session;
        b.commitIPod(); // já ligado: a sessão da rádio não muda (cada faixa é uma estação nova do relay)
        assertEquals(session, b.state.session);
        b.state.nowPlaying = "a - t1";
        b.state.status = "akashicfm.ipod.status.resolving";
        b.ipod.on = false;
        b.commitIPod();
        assertFalse(b.state.playing);
        assertEquals(Transport.NONE, b.state.transport);
        assertEquals("", b.state.tunedUrl);
        assertEquals("", b.state.nowPlaying);
        assertEquals("", b.state.status);
    }

    @Test
    void desligadoVence() {
        TileIPodPlayer b = block(2);
        b.ipod.play(0);
        b.commitIPod();
        // /fm stop (ou stopall, ou bloqueio) desliga a rádio: a fila acompanha.
        assertTrue(RadioActionHandler.applyStop(b.state));
        assertTrue(b.reconcile());
        assertFalse(b.ipod.on);
        assertFalse(b.reconcile());
        // A fila parou (fim, falha) e a rádio ainda dizia ligada: a rádio desliga.
        b.ipod.play(0);
        b.commitIPod();
        b.ipod.on = false;
        assertTrue(b.reconcile());
        assertFalse(b.state.playing);
    }

    @Test
    void redstoneEnquantoLigado() {
        TileIPodPlayer b = block(2);
        b.state.redstoneMode = RedstoneMode.WHILE_POWERED;
        IPodActionHandler.onRedstone(b, true);
        assertTrue(b.ipod.on);
        assertTrue(b.state.playing);
        int session = b.ipod.session;
        IPodActionHandler.onRedstone(b, true); // mesmo nível: nada muda
        assertEquals(session, b.ipod.session);
        b.ipod.paused = true;
        IPodActionHandler.onRedstone(b, false);
        assertFalse(b.ipod.on);
        assertFalse(b.ipod.paused);
        assertFalse(b.state.playing);
        // Ligado de novo enquanto pausado: volta a tocar.
        IPodActionHandler.onRedstone(b, true);
        b.ipod.paused = true;
        IPodActionHandler.onRedstone(b, false);
        IPodActionHandler.onRedstone(b, true);
        assertTrue(b.ipod.on);
        assertFalse(b.ipod.paused);
    }

    @Test
    void redstoneAlternaSoNaSubida() {
        TileIPodPlayer b = block(2);
        b.state.redstoneMode = RedstoneMode.TOGGLE_ON_PULSE;
        IPodActionHandler.onRedstone(b, true);
        assertTrue(b.ipod.on);
        IPodActionHandler.onRedstone(b, false); // descida não alterna
        assertTrue(b.ipod.on);
        IPodActionHandler.onRedstone(b, true);
        assertFalse(b.ipod.on);
        assertFalse(b.state.playing);
    }

    @Test
    void redstoneNuncaLigaFilaVaziaNemComOIPodDesligado() {
        TileIPodPlayer empty = block(0);
        empty.state.redstoneMode = RedstoneMode.WHILE_POWERED;
        IPodActionHandler.onRedstone(empty, true);
        assertFalse(empty.ipod.on);
        assertFalse(empty.state.playing);
        assertTrue(empty.state.lastPowered);

        TileIPodPlayer ignored = block(2);
        IPodActionHandler.onRedstone(ignored, true);
        assertFalse(ignored.ipod.on);
        assertTrue(ignored.state.lastPowered);

        FmConfig.IPod.enabled = false;
        TileIPodPlayer off = block(2);
        off.state.redstoneMode = RedstoneMode.TOGGLE_ON_PULSE;
        IPodActionHandler.onRedstone(off, true);
        assertFalse(off.ipod.on);
        assertTrue(off.state.lastPowered); // o nível é guardado: religar o config não dispara um pulso velho
    }

    @Test
    void discoGuardaAFilaEARadioManda() {
        TileIPodPlayer b = block(3);
        b.ipod.play(2);
        b.commitIPod();
        NBTTagCompound tag = new NBTTagCompound();
        b.writeToNBT(tag);
        TileIPodPlayer loaded = new TileIPodPlayer();
        loaded.readFromNBT(tag);
        assertEquals(3, loaded.ipod.queue.size());
        assertEquals(2, loaded.ipod.index);
        assertEquals(77, loaded.ipod.id);
        assertTrue(loaded.ipod.on);
        assertEquals(TuneMode.IPOD, loaded.state.mode);
        // Uma parada gravada antes de o serviço ver: ao carregar, a rádio desligada desliga a fila.
        b.state.playing = false;
        NBTTagCompound stopped = new NBTTagCompound();
        b.writeToNBT(stopped);
        TileIPodPlayer again = new TileIPodPlayer();
        again.readFromNBT(stopped);
        assertFalse(again.ipod.on);
        assertEquals(3, again.ipod.queue.size());
    }

    @Test
    void quebrarLevaAFilaDesligada() {
        TileIPodPlayer b = block(4);
        b.ipod.play(1);
        b.commitIPod();
        b.state.volume = 33;
        NBTTagCompound settings = new NBTTagCompound();
        b.writeItemSettings(settings);
        TileIPodPlayer placed = new TileIPodPlayer();
        placed.readItemSettings(settings);
        assertEquals(4, placed.ipod.queue.size());
        assertFalse(placed.ipod.on);
        assertFalse(placed.state.playing);
        assertEquals(33, placed.state.volume);
        assertEquals(TuneMode.IPOD, placed.state.mode);
        // O item de uma rádio comum com as configurações do bloco não vira bloco do iPod (nem toca a chave).
        TileRadio radio = new TileRadio();
        radio.readItemSettings(settings);
        assertEquals(TuneMode.URL, radio.state.mode);
        assertEquals("", radio.state.effectiveUrl());
    }

    @Test
    void pacoteDoPiorCasoCabe() throws Exception {
        // O pacote de descrição do 1.7.10 leva o NBT comprimido com o tamanho num short (até 32767 bytes).
        TileIPodPlayer b = new TileIPodPlayer();
        Random rng = new Random(23);
        List<IPodTrack> worst = new ArrayList<>();
        for (int i = 0; i < IPodState.HARD_MAX_QUEUE; i++) {
            StringBuilder t = new StringBuilder(), a = new StringBuilder(),
                l = new StringBuilder("https://soundcloud.com/");
            for (int k = 0; k < IPodTrack.MAX_TITLE; k++) t.append((char) (0x4E00 + rng.nextInt(20000)));
            for (int k = 0; k < IPodTrack.MAX_ARTIST; k++) a.append((char) (0xAC00 + rng.nextInt(11000)));
            while (l.length() < IPodTrack.MAX_LINK) l.append((char) ('a' + rng.nextInt(26)));
            worst.add(new IPodTrack(IPodTrack.Source.SOUNDCLOUD, l.toString(), t.toString(), a.toString(), 3600));
        }
        b.ipod.append(worst, IPodState.HARD_MAX_QUEUE);
        b.ipod.id = Long.MAX_VALUE;
        b.ipod.play(0);
        b.commitIPod();
        StringBuilder text = new StringBuilder();
        while (text.length() < RadioLimits.MAX_TITLE_LENGTH) text.append((char) (0x4E00 + rng.nextInt(20000)));
        b.state.nowPlaying = text.toString();
        b.state.screenText = text.substring(0, RadioLimits.MAX_SCREEN_TEXT);
        b.state.ownerName = "abcdefghijklmnop";
        b.state.status = "akashicfm.ipod.err.busy";
        for (int i = 0; i < RadioLimits.MAX_SPEAKERS_HARD; i++)
            b.state.speakers.add(new Pos(-29_999_000 + i, 255, 29_999_000 - i));
        byte[] compressed = CompressedStreamTools.compress(b.descriptionTag());
        assertTrue(compressed.length < 30_000, "descrição comprimida com " + compressed.length + " bytes");
    }
}
