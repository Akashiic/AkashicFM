package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EnergyBufferTest {

    @Test
    void euComLimitePorTickECapacidade() {
        EnergyBuffer b = new EnergyBuffer(1000, 64);
        b.startTick();
        assertEquals(36, b.offerEu(100), 1e-9); // entram 64
        assertEquals(64, b.stored(), 1e-9);
        assertEquals(10, b.offerEu(10), 1e-9); // já entrou o limite deste tick
        for (int i = 0; i < 20; i++) {
            b.startTick();
            b.offerEu(64);
        }
        assertEquals(1000, b.stored(), 1e-9); // cheio
        b.startTick();
        assertEquals(64, b.offerEu(64), 1e-9);
        assertEquals(0, b.demanded(), 1e-9);
    }

    @Test
    void configNovaValeNaHora() {
        EnergyBuffer b = new EnergyBuffer(1000, 64);
        for (int i = 0; i < 20; i++) {
            b.startTick();
            b.offerEu(64);
        }
        assertEquals(1000, b.stored(), 1e-9);
        b.configure(400, 16); // /fm reload com capacidade e entrada menores
        assertEquals(400, b.stored(), 1e-9); // o excesso se perde
        assertEquals(400, b.capacity(), 1e-9);
        b.consume(100);
        b.startTick();
        assertEquals(84, b.offerEu(100), 1e-9); // entram só 16 por tick
        b.configure(2000, 128); // maiores: guarda o que tinha e aceita mais
        assertEquals(316, b.stored(), 1e-9);
        b.startTick();
        assertEquals(0, b.offerEu(128), 1e-9);
        assertEquals(444, b.stored(), 1e-9);
    }

    @Test
    void rfConvertidoSimulacaoNaoMuda() {
        EnergyBuffer b = new EnergyBuffer(1000, 64);
        b.startTick();
        assertEquals(256, b.offerRf(10_000, 4, true)); // 64 EU = 256 RF
        assertEquals(0, b.stored(), 1e-9);
        assertEquals(100, b.offerRf(100, 4, false));
        assertEquals(25, b.stored(), 1e-9);
        assertEquals(156, b.offerRf(10_000, 4, false)); // o resto do limite deste tick
        assertEquals(64, b.stored(), 1e-9);
        assertEquals(256, b.storedRf(4));
        assertEquals(4000, b.capacityRf(4));
    }

    @Test
    void consumo() {
        EnergyBuffer b = new EnergyBuffer(1000, 64);
        b.startTick();
        b.offerEu(20);
        assertTrue(b.consume(8));
        assertTrue(b.consume(8));
        assertFalse(b.consume(8)); // sobraram 4: não gasta nada
        assertEquals(4, b.stored(), 1e-9);
        assertTrue(b.consume(0));
    }

    @Test
    void entradasInvalidas() {
        EnergyBuffer b = new EnergyBuffer(1000, 64);
        b.startTick();
        assertEquals(0, b.offerEu(-5), 0);
        assertEquals(0, b.offerEu(Double.NaN), 0);
        assertEquals(0, b.offerRf(-1, 4, false));
        assertEquals(0, b.offerRf(100, 0, false));
        assertEquals(0, b.stored(), 0);
        b.setStored(5000);
        assertEquals(1000, b.stored(), 0);
        b.setStored(-3);
        assertEquals(0, b.stored(), 0);
        b.setStored(Double.NaN);
        assertEquals(0, b.stored(), 0);
    }

    @Test
    void entradaAbaixoDoConsumoNaoPiscaACadaTick() {
        // 7 EU/t de entrada para 8 de consumo: liga e desliga, mas em ciclos longos (cada troca derruba as rádios).
        EnergyBuffer b = new EnergyBuffer(8000, 128);
        boolean powered = false;
        int flips = 0, onTicks = 0;
        double in = 0, used = 0;
        for (int tick = 0; tick < 4000; tick++) {
            b.startTick();
            in += 7 - b.offerEu(7);
            double before = b.stored();
            boolean now = b.tickPower(powered, true, 8, 320);
            if (now) {
                onTicks++;
                used += before - b.stored();
            }
            if (now != powered) flips++;
            powered = now;
        }
        assertTrue(flips <= 2 * (4000 / 150), "trocas demais: " + flips);
        assertTrue(onTicks > 3000, "ligado pouco: " + onTicks);
        assertTrue(used <= in + 1e-6, "energia de graça");
    }

    @Test
    void reservaLimitadaACapacidade() {
        EnergyBuffer b = new EnergyBuffer(100, 1000);
        b.startTick();
        b.offerEu(100);
        // Reserva maior que a capacidade: com o buffer cheio, volta (senão nunca voltaria).
        assertTrue(b.tickPower(false, true, 8, 320));
    }

    @Test
    void paradoTemEnergiaSeDariaParaTransmitir() {
        EnergyBuffer b = new EnergyBuffer(1000, 1000);
        assertFalse(b.tickPower(true, false, 8, 320)); // vazio: sem energia mesmo vindo "com energia"
        b.startTick();
        b.offerEu(100);
        assertFalse(b.tickPower(false, false, 8, 320)); // sem a reserva, não volta
        assertTrue(b.tickPower(true, false, 8, 320)); // estava com energia e daria para transmitir
        assertEquals(100, b.stored(), 0); // parado não gasta
        b.startTick();
        b.offerEu(300);
        assertTrue(b.tickPower(false, false, 8, 320));
    }
}
