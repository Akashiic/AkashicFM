package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;

import org.junit.jupiter.api.Test;

class PortableStateTest {

    @Test
    void itemNovo() {
        PortableState s = PortableState.fromItemTag(null);
        assertEquals(TuneMode.URL, s.mode);
        assertEquals("", s.url);
        assertEquals(Frequency.DEFAULT, s.frequency);
        assertEquals(RadioLimits.VOLUME_DEFAULT, s.volume);
        assertFalse(s.on);
        assertEquals(0, s.id);
    }

    @Test
    void idaEVolta() {
        PortableState s = new PortableState();
        s.mode = TuneMode.FREQUENCY;
        s.url = "http://a.example.com/live";
        s.frequency = 987;
        s.volume = 35;
        s.on = true;
        s.session = 7;
        s.id = 123456789012345L;
        PortableState r = PortableState.fromItemTag(s.writeToItemTag(null));
        assertEquals(TuneMode.FREQUENCY, r.mode);
        assertEquals("http://a.example.com/live", r.url);
        assertEquals(987, r.frequency);
        assertEquals(35, r.volume);
        assertTrue(r.on);
        assertEquals(7, r.session);
        assertEquals(123456789012345L, r.id);
    }

    @Test
    void nbtHostilSaneado() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("mode", (byte) 99); // vira URL
        tag.setTag("url", new NBTTagCompound()); // tipo errado: vira vazio
        tag.setShort("frequency", (short) 5000);
        tag.setByte("volume", (byte) -20);
        tag.setBoolean("on", true);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag(PortableState.KEY, tag);
        PortableState s = PortableState.fromItemTag(root);
        assertEquals(TuneMode.URL, s.mode);
        assertEquals("", s.url);
        assertEquals(Frequency.MAX, s.frequency);
        assertEquals(RadioLimits.VOLUME_MIN, s.volume);
        assertFalse(s.on); // modo URL sem URL não fica ligado
    }

    @Test
    void sintonizadoLigadoSemUrl() {
        PortableState s = new PortableState();
        s.mode = TuneMode.FREQUENCY;
        s.on = true;
        s.sanitize();
        assertTrue(s.on);
    }

    @Test
    void chaveComOutroTipoEIgnorada() {
        NBTTagCompound root = new NBTTagCompound();
        root.setTag(PortableState.KEY, new NBTTagString("x"));
        PortableState s = PortableState.fromItemTag(root);
        assertFalse(s.on);
        assertEquals("", s.url);
    }

    @Test
    void urlComControleCortada() {
        PortableState s = new PortableState();
        s.url = "http://a.example.com/\u0000live\n";
        s.sanitize();
        assertFalse(s.url.contains("\u0000"));
        assertFalse(s.url.contains("\n"));
    }
}
