package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FrequencyTest {

    @Test
    void formatacao() {
        assertEquals("98.7", Frequency.format(987));
        assertEquals("87.5", Frequency.format(875));
        assertEquals("108.0", Frequency.format(1080));
        assertEquals("87.5", Frequency.format(10)); // fora da faixa: preso ao limite
        assertEquals("108.0", Frequency.format(99999));
    }

    @Test
    void leitura() {
        assertEquals(987, Frequency.parse("98.7"));
        assertEquals(987, Frequency.parse(" 98,7 "));
        assertEquals(1000, Frequency.parse("100"));
        assertEquals(875, Frequency.parse("87.5"));
        assertEquals(1080, Frequency.parse("108.0"));
        assertEquals(987, Frequency.parse("098.7")); // zero à esquerda é inofensivo
        for (String bad : new String[] { null, "", "87.4", "108.1", "98.75", "98.", ".5", "abc", "9 8.7", "-98.7",
            "1e2", "98.7.1" }) {
            assertEquals(-1, Frequency.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void limites() {
        assertEquals(875, Frequency.clamp(0));
        assertEquals(1080, Frequency.clamp(2000));
        assertEquals(950, Frequency.clamp(950));
        assertEquals(Frequency.DEFAULT, Frequency.clamp(Frequency.DEFAULT));
    }
}
