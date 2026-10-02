package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.junit.jupiter.api.Test;

class RadioStateTest {

    private static final int MAX_RANGE = 48;
    private static final int MAX_SPEAKERS = 8;

    private static RadioState sample() {
        RadioState s = new RadioState();
        s.url = "https://stream.example.com/live.mp3";
        s.stations.addAll(Arrays.asList("http://a.com/1", "http://b.com/2"));
        s.playing = true;
        s.volume = 77;
        s.range = 33;
        s.access = RadioAccess.PUBLIC;
        s.owner = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        s.ownerName = "Steve";
        s.screenText = "Rock FM";
        s.screenColor = 0x123456;
        s.redstoneMode = RedstoneMode.TOGGLE_ON_PULSE;
        s.lastPowered = true;
        s.speakers.add(new Pos(1, 2, 3));
        s.speakers.add(new Pos(-4, 70, 9));
        s.transport = Transport.DIRECT;
        s.session = 5;
        s.epoch = 42;
        s.status = "status";
        s.nowPlaying = "Artist - Song";
        return s;
    }

    private static RadioState roundTrip(RadioState s, boolean forClient) {
        NBTTagCompound tag = new NBTTagCompound();
        s.writeToNbt(tag, forClient);
        RadioState out = new RadioState();
        out.readFromNbt(tag, MAX_RANGE, MAX_SPEAKERS);
        return out;
    }

    @Test
    void idaEVoltaPreservaTudo() {
        RadioState s = sample();
        RadioState r = roundTrip(s, true);
        assertEquals(s.url, r.url);
        assertEquals(s.stations, r.stations);
        assertTrue(r.playing);
        assertEquals(77, r.volume);
        assertEquals(33, r.range);
        assertEquals(RadioAccess.PUBLIC, r.access);
        assertEquals(s.owner, r.owner);
        assertEquals("Steve", r.ownerName);
        assertEquals("Rock FM", r.screenText);
        assertEquals(0x123456, r.screenColor);
        assertEquals(RedstoneMode.TOGGLE_ON_PULSE, r.redstoneMode);
        assertTrue(r.lastPowered);
        assertEquals(s.speakers, r.speakers);
        assertEquals(Transport.DIRECT, r.transport);
        assertEquals(5, r.session);
        assertEquals(42, r.epoch);
        assertEquals("status", r.status);
        assertEquals("Artist - Song", r.nowPlaying);
    }

    @Test
    void discoNaoGuardaCamposTransitorios() {
        RadioState r = roundTrip(sample(), false);
        assertEquals("", r.status);
        assertEquals("", r.nowPlaying);
    }

    @Test
    void semDonoContinuaSemDono() {
        RadioState s = sample();
        s.owner = null;
        assertNull(roundTrip(s, false).owner);
    }

    @Test
    void saneamentoCortaEPrende() {
        RadioState s = new RadioState();
        StringBuilder longUrl = new StringBuilder("http://a.com/");
        while (longUrl.length() < 600) longUrl.append('x');
        s.url = "  " + longUrl + "\n";
        s.volume = 250;
        s.range = 1000;
        s.screenText = "§kOculto§r texto muito muito muito muito longo para a tela";
        s.screenColor = 0xFF123456;
        s.ownerName = "NomeComprido_Demais_Para_MC";
        for (int i = 0; i < 40; i++) s.stations.add("http://s" + (i % 20) + ".com/");
        s.stations.add("   ");
        for (int i = 0; i < 20; i++) s.speakers.add(new Pos(i, 64, 0));
        s.speakers.add(new Pos(0, 64, 0)); // repetida
        s.speakers.add(new Pos(5, 300, 5)); // fora do mundo
        s.sanitize(MAX_RANGE, MAX_SPEAKERS);

        assertEquals(RadioLimits.MAX_URL_LENGTH, s.url.length());
        assertFalse(s.url.contains("\n") || s.url.startsWith(" "));
        assertEquals(100, s.volume);
        assertEquals(MAX_RANGE, s.range);
        assertFalse(s.screenText.contains("§"));
        assertTrue(s.screenText.length() <= RadioLimits.MAX_SCREEN_TEXT);
        assertEquals(0x123456, s.screenColor);
        assertTrue(s.ownerName.length() <= RadioLimits.MAX_OWNER_NAME);
        assertEquals(RadioLimits.MAX_STATIONS, s.stations.size());
        assertEquals(
            s.stations.size(),
            s.stations.stream()
                .distinct()
                .count());
        assertEquals(MAX_SPEAKERS, s.speakers.size());
        assertEquals(
            s.speakers.size(),
            s.speakers.stream()
                .distinct()
                .count());
    }

    @Test
    void alcanceMinimoEConfigAbsurdo() {
        RadioState s = new RadioState();
        s.range = 0;
        s.sanitize(MAX_RANGE, MAX_SPEAKERS);
        assertEquals(RadioLimits.RANGE_MIN, s.range);
        s.range = 10_000;
        s.sanitize(100_000, 100_000); // config fora da faixa: vale o limite rígido
        assertEquals(RadioLimits.RANGE_HARD_MAX, s.range);
    }

    @Test
    void semUrlNaoToca() {
        RadioState s = new RadioState();
        s.playing = true;
        s.url = " \t ";
        s.sanitize(MAX_RANGE, MAX_SPEAKERS);
        assertFalse(s.playing);
        assertEquals("", s.url);
    }

    @Test
    void nbtAdulteradoVoltaAoPadrao() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("url", 5); // tipo errado
        tag.setByte("access", (byte) 99);
        tag.setByte("redstoneMode", (byte) -3);
        tag.setByte("transport", (byte) 77);
        tag.setByte("volume", (byte) -50);
        tag.setShort("range", (short) -1);
        NBTTagList huge = new NBTTagList();
        for (int i = 0; i < 10_000; i++) huge.appendTag(new NBTTagString("http://x" + i + ".com/"));
        tag.setTag("stations", huge);
        RadioState s = new RadioState();
        s.readFromNbt(tag, MAX_RANGE, MAX_SPEAKERS);
        assertEquals("", s.url);
        assertEquals(RadioAccess.PRIVATE, s.access);
        assertEquals(RedstoneMode.IGNORE, s.redstoneMode);
        assertEquals(Transport.NONE, s.transport);
        assertEquals(0, s.volume);
        assertEquals(RadioLimits.RANGE_MIN, s.range);
        assertEquals(RadioLimits.MAX_STATIONS, s.stations.size());
        assertFalse(s.playing);
    }

    @Test
    void tagVaziaDaOPadrao() {
        RadioState s = new RadioState();
        s.readFromNbt(new NBTTagCompound(), MAX_RANGE, MAX_SPEAKERS);
        assertEquals(RadioLimits.VOLUME_DEFAULT, s.volume);
        assertEquals(RadioLimits.RANGE_DEFAULT, s.range);
        assertEquals(RadioLimits.SCREEN_COLOR_DEFAULT, s.screenColor);
        assertNull(s.owner);
    }

    @Test
    void configuracoesDoItemNaoLevamDonoNemCaixas() {
        RadioState s = sample();
        NBTTagCompound tag = new NBTTagCompound();
        s.writeSettings(tag);
        RadioState r = new RadioState();
        r.readSettings(tag, MAX_RANGE, MAX_SPEAKERS);
        assertEquals(s.url, r.url);
        assertEquals(s.stations, r.stations);
        assertEquals(77, r.volume);
        assertEquals(33, r.range);
        assertEquals("Rock FM", r.screenText);
        assertEquals(RedstoneMode.TOGGLE_ON_PULSE, r.redstoneMode);
        assertEquals(RadioAccess.PUBLIC, r.access);
        assertNull(r.owner);
        assertTrue(r.speakers.isEmpty());
        assertFalse(r.playing);
        assertEquals(0, r.session);
    }

    @Test
    void urlPlausivel() {
        assertTrue(RadioState.isPlausibleUrl("http://stream.example.com:8000/live"));
        assertFalse(RadioState.isPlausibleUrl("ftp://x.com/"));
        assertFalse(RadioState.isPlausibleUrl("http://127.0.0.1/"));
        assertFalse(RadioState.isPlausibleUrl(""));
        assertFalse(RadioState.isPlausibleUrl(null));
    }
}
