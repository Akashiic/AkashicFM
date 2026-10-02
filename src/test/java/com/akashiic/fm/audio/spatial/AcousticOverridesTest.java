package com.akashiic.fm.audio.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AcousticOverridesTest {

    @Test
    void entradasValidas() {
        List<String> bad = new ArrayList<>();
        Map<String, AcousticOverrides.Value> m = AcousticOverrides.parse(
            new String[] { "minecraft:stone=0.8", " gregtech:gt.blockcasings = 0.7 , 0.1 ", "Mod:Bars=0.2,-1", "",
                null },
            bad::add);
        assertTrue(bad.isEmpty(), bad.toString());
        assertEquals(3, m.size());
        assertEquals(0.8f, m.get("minecraft:stone").absorption, 1e-6);
        assertFalse(
            m.get("minecraft:stone")
                .hasDamping());
        assertEquals(0.1f, m.get("gregtech:gt.blockcasings").damping, 1e-6);
        assertEquals(-1f, m.get("mod:bars").damping, 1e-6); // chave em minúsculas
    }

    @Test
    void entradasInvalidasSaoIgnoradas() {
        List<String> bad = new ArrayList<>();
        String[] in = { "stone=0.5", "minecraft:stone", "minecraft:stone=", "minecraft:stone=abc",
            "minecraft:stone=1.5", "minecraft:stone=-0.1", "minecraft:stone=0.5,2", "minecraft:stone=0.5,0.1,0.2",
            ":stone=0.5", "minecraft:=0.5", "minecraft:st one=0.5", "minecraft:stone=NaN", "minecraft:stone=0.5,-0.5",
            "=0.5" };
        Map<String, AcousticOverrides.Value> m = AcousticOverrides.parse(in, bad::add);
        assertTrue(
            m.isEmpty(),
            m.keySet()
                .toString());
        assertEquals(in.length, bad.size());
    }

    @Test
    void listaNulaOuVazia() {
        assertTrue(
            AcousticOverrides.parse(null, s -> {})
                .isEmpty());
        assertTrue(
            AcousticOverrides.parse(new String[0], s -> {})
                .isEmpty());
    }

    @Test
    void ultimaEntradaRepetidaVence() {
        Map<String, AcousticOverrides.Value> m = AcousticOverrides
            .parse(new String[] { "minecraft:glass=0.1", "minecraft:glass=0.3" }, s -> {});
        assertEquals(0.3f, m.get("minecraft:glass").absorption, 1e-6);
    }
}
