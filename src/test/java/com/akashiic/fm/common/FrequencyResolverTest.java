package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class FrequencyResolverTest {

    private static FrequencyResolver.Transmitter tx(int x, int freq, int range, String url) {
        return new FrequencyResolver.Transmitter(new Pos(x, 64, 0), freq, range, url, "T" + x, true);
    }

    @Test
    void dentroDaCoberturaNaFrequenciaCerta() {
        FrequencyResolver.Transmitter a = tx(0, 987, 100, "http://a.example/");
        FrequencyResolver.Tuning t = FrequencyResolver
            .resolve(Collections.singletonList(a), 987, 50.5, 64.5, 0.5, null);
        assertSame(a, t.transmitter);
        assertEquals(0.5, t.signal, 1e-9);
        assertNull(FrequencyResolver.resolve(Collections.singletonList(a), 988, 50.5, 64.5, 0.5, null));
        assertNull(FrequencyResolver.resolve(Collections.singletonList(a), 987, 100.5, 64.5, 0.5, null)); // borda
    }

    @Test
    void maisForteVence() {
        FrequencyResolver.Transmitter far = tx(0, 987, 200, "http://far.example/");
        FrequencyResolver.Transmitter near = tx(100, 987, 64, "http://near.example/");
        // No ponto x=90: far tem 1 - 90/200 = 0,55; near tem 1 - 10/64 = 0,84.
        FrequencyResolver.Tuning t = FrequencyResolver.resolve(Arrays.asList(far, near), 987, 90.5, 64.5, 0.5, null);
        assertSame(near, t.transmitter);
    }

    @Test
    void histereseSegueComOAtual() {
        FrequencyResolver.Transmitter a = tx(0, 987, 100, "http://a.example/");
        FrequencyResolver.Transmitter b = tx(100, 987, 100, "http://b.example/");
        List<FrequencyResolver.Transmitter> both = Arrays.asList(a, b);
        // x=52: a = 0,48, b = 0,52. Sem histerese, b; ouvindo a, a diferença (0,04) não basta para trocar.
        assertSame(b, FrequencyResolver.resolve(both, 987, 52.5, 64.5, 0.5, null).transmitter);
        assertSame(a, FrequencyResolver.resolve(both, 987, 52.5, 64.5, 0.5, a.pos).transmitter);
        // x=60: a = 0,40, b = 0,60 (diferença 0,2): troca.
        assertSame(b, FrequencyResolver.resolve(both, 987, 60.5, 64.5, 0.5, a.pos).transmitter);
        // O atual fora da cobertura: troca mesmo com diferença pequena.
        FrequencyResolver.Transmitter small = tx(0, 987, 20, "http://small.example/");
        assertSame(b, FrequencyResolver.resolve(Arrays.asList(small, b), 987, 52.5, 64.5, 0.5, small.pos).transmitter);
    }

    @Test
    void inativoOuSemUrlNaoConta() {
        FrequencyResolver.Transmitter off = new FrequencyResolver.Transmitter(
            new Pos(0, 64, 0),
            987,
            100,
            "http://off.example/",
            "",
            false);
        FrequencyResolver.Transmitter noUrl = tx(5, 987, 100, "");
        assertNull(FrequencyResolver.resolve(Arrays.asList(off, noUrl), 987, 1.5, 64.5, 0.5, null));
    }

    @Test
    void empateEhDeterministico() {
        FrequencyResolver.Transmitter left = tx(-10, 987, 100, "http://l.example/");
        FrequencyResolver.Transmitter right = tx(10, 987, 100, "http://r.example/");
        // Ponto no meio: mesmo sinal e mesma distância: vence a menor posição, em qualquer ordem da lista.
        assertSame(left, FrequencyResolver.resolve(Arrays.asList(left, right), 987, 0.5, 64.5, 0.5, null).transmitter);
        assertSame(left, FrequencyResolver.resolve(Arrays.asList(right, left), 987, 0.5, 64.5, 0.5, null).transmitter);
    }

    @Test
    void sinal() {
        FrequencyResolver.Transmitter a = tx(0, 987, 100, "http://a.example/");
        assertEquals(1.0, FrequencyResolver.signal(a, 0.5, 64.5, 0.5), 1e-9);
        assertEquals(0.0, FrequencyResolver.signal(a, 500.5, 64.5, 0.5), 0);
        assertEquals(0.0, FrequencyResolver.signal(tx(0, 987, 0, "http://z.example/"), 0.5, 64.5, 0.5), 0);
    }
}
