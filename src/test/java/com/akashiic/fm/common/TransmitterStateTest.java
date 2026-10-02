package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

class TransmitterStateTest {

    private static TransmitterState sample() {
        TransmitterState s = new TransmitterState();
        s.url = "https://stream.example.com/fm";
        s.frequency = 987;
        s.broadcasting = true;
        s.name = "Rádio Akashic";
        s.access = RadioAccess.PUBLIC;
        s.owner = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        s.ownerName = "Steve";
        s.redstoneMode = RedstoneMode.WHILE_POWERED;
        s.epoch = 9;
        s.antennas = 3;
        s.range = 160;
        s.powered = false;
        s.energyRequired = true;
        s.energy = 500;
        s.energyCapacity = 4000;
        s.nowPlaying = "Artist - Song";
        return s;
    }

    private static TransmitterState roundTrip(TransmitterState s, boolean forClient) {
        NBTTagCompound tag = new NBTTagCompound();
        s.writeToNbt(tag, forClient);
        TransmitterState out = new TransmitterState();
        out.readFromNbt(tag);
        return out;
    }

    @Test
    void idaEVoltaParaOCliente() {
        TransmitterState r = roundTrip(sample(), true);
        assertEquals("https://stream.example.com/fm", r.url);
        assertEquals(987, r.frequency);
        assertTrue(r.broadcasting);
        assertEquals("Rádio Akashic", r.name);
        assertEquals(RadioAccess.PUBLIC, r.access);
        assertEquals("Steve", r.ownerName);
        assertTrue(r.isOwner(UUID.fromString("12345678-1234-1234-1234-123456789abc")));
        assertEquals(RedstoneMode.WHILE_POWERED, r.redstoneMode);
        assertEquals(3, r.antennas);
        assertEquals(160, r.range);
        assertFalse(r.powered);
        assertTrue(r.energyRequired);
        assertEquals(500, r.energy);
        assertEquals("Artist - Song", r.nowPlaying);
        assertFalse(r.active(), "sem energia não transmite");
    }

    @Test
    void transitoriosNaoVaoParaODisco() {
        TransmitterState r = roundTrip(sample(), false);
        assertEquals(0, r.antennas);
        assertEquals(0, r.range);
        assertTrue(r.powered); // padrão até o servidor recalcular
        assertEquals("", r.nowPlaying);
        assertTrue(r.active());
    }

    @Test
    void semUrlNaoTransmite() {
        TransmitterState s = sample();
        s.url = "";
        TransmitterState r = roundTrip(s, true);
        assertFalse(r.broadcasting);
        assertFalse(r.active());
    }

    @Test
    void valoresAdulteradosSaoSaneados() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("url", "http://a.example/" + new String(new char[5000]).replace('\0', 'x'));
        tag.setShort("frequency", (short) -3);
        tag.setString("name", "§kx§r" + new String(new char[200]).replace('\0', 'n'));
        tag.setByte("access", (byte) 77);
        tag.setByte("redstoneMode", (byte) -1);
        tag.setByte("antennas", (byte) 120);
        tag.setInteger("energy", -10);
        tag.setInteger("url2", 5);
        TransmitterState r = new TransmitterState();
        r.readFromNbt(tag);
        assertTrue(r.url.length() <= RadioLimits.MAX_URL_LENGTH);
        assertEquals(Frequency.MIN, r.frequency);
        assertFalse(r.name.contains("§"));
        assertTrue(r.name.length() <= RadioLimits.MAX_SCREEN_TEXT);
        assertEquals(RadioAccess.PRIVATE, r.access);
        assertEquals(RedstoneMode.IGNORE, r.redstoneMode);
        assertEquals(64, r.antennas);
        assertEquals(0, r.energy);
        assertNull(r.owner);
    }

    @Test
    void urlComTipoErradoNaoVazaToString() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("url", 12345);
        TransmitterState r = new TransmitterState();
        r.readFromNbt(tag);
        assertEquals("", r.url);
    }

    @Test
    void configuracoesDoItem() {
        TransmitterState s = sample();
        NBTTagCompound tag = new NBTTagCompound();
        s.writeSettings(tag);
        TransmitterState r = new TransmitterState();
        r.readSettings(tag);
        assertEquals(s.url, r.url);
        assertEquals(987, r.frequency);
        assertEquals("Rádio Akashic", r.name);
        assertFalse(r.broadcasting);
        assertNull(r.owner);
    }
}
