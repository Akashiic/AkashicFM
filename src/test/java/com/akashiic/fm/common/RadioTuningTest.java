package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Test;

/** Modo de sintonia da rádio (URL ou frequência) e os campos que o servidor escreve ao sintonizar. */
class RadioTuningTest {

    private static RadioState roundTrip(RadioState s, boolean forClient) {
        NBTTagCompound tag = new NBTTagCompound();
        s.writeToNbt(tag, forClient);
        RadioState out = new RadioState();
        out.readFromNbt(tag, 48, 8);
        return out;
    }

    private static RadioState tuned() {
        RadioState s = new RadioState();
        s.mode = TuneMode.FREQUENCY;
        s.frequency = 987;
        s.playing = true;
        s.tunedUrl = "https://stream.example.com/fm";
        s.tunedName = "Rádio Akashic";
        s.signal = 73;
        return s;
    }

    @Test
    void frequenciaSemUrlPropriaContinuaLigada() {
        RadioState r = roundTrip(tuned(), true);
        assertEquals(TuneMode.FREQUENCY, r.mode);
        assertEquals(987, r.frequency);
        assertTrue(r.playing, "sintonizada fica ligada mesmo sem URL própria");
        assertEquals("https://stream.example.com/fm", r.effectiveUrl());
        assertEquals("Rádio Akashic", r.tunedName);
        assertEquals(73, r.signal);
    }

    @Test
    void sintoniaNaoVaiParaODisco() {
        RadioState r = roundTrip(tuned(), false);
        assertEquals(TuneMode.FREQUENCY, r.mode);
        assertEquals(987, r.frequency);
        assertEquals("", r.tunedUrl); // o servidor resolve de novo ao carregar
        assertEquals("", r.effectiveUrl());
        assertEquals(0, r.signal);
    }

    @Test
    void nbtAntigoCaiNoModoUrl() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("url", "https://old.example.com/a");
        tag.setBoolean("playing", true);
        RadioState r = new RadioState();
        r.readFromNbt(tag, 48, 8);
        assertEquals(TuneMode.URL, r.mode);
        assertEquals(Frequency.DEFAULT, r.frequency);
        assertEquals("https://old.example.com/a", r.effectiveUrl());
        assertTrue(r.playing);
    }

    @Test
    void modoUrlSemUrlDesliga() {
        RadioState s = new RadioState();
        s.playing = true;
        s.tunedUrl = "https://x.example/"; // ignorado no modo URL
        RadioState r = roundTrip(s, true);
        assertFalse(r.playing);
        assertEquals("", r.effectiveUrl());
    }

    @Test
    void valoresAdulteradosSaoSaneados() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("mode", (byte) 99);
        tag.setShort("frequency", (short) 5000);
        tag.setString("tunedUrl", "javascript:alert(1)\u0000" + new String(new char[5000]).replace('\0', 'x'));
        tag.setString("tunedName", "§kOculto§r nome muito muito muito muito muito longo");
        tag.setByte("signal", (byte) -7);
        RadioState r = new RadioState();
        r.readFromNbt(tag, 48, 8);
        assertEquals(TuneMode.URL, r.mode);
        assertEquals(Frequency.MAX, r.frequency);
        assertTrue(r.tunedUrl.length() <= RadioLimits.MAX_URL_LENGTH);
        assertFalse(r.tunedUrl.contains("\u0000"));
        assertFalse(r.tunedName.contains("§"));
        assertTrue(r.tunedName.length() <= RadioLimits.MAX_SCREEN_TEXT);
        assertEquals(0, r.signal);
    }

    @Test
    void configuracoesDoItemLevamModoEFrequencia() {
        RadioState s = tuned();
        NBTTagCompound tag = new NBTTagCompound();
        s.writeSettings(tag);
        RadioState r = new RadioState();
        r.readSettings(tag, 48, 8);
        assertEquals(TuneMode.FREQUENCY, r.mode);
        assertEquals(987, r.frequency);
    }
}
