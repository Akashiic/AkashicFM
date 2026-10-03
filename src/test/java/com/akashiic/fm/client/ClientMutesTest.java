package com.akashiic.fm.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.Pos;

class ClientMutesTest {

    @AfterEach
    void clear() {
        ClientMutes.clear();
    }

    @Test
    void radioPorPosicaoEDimensao() {
        Pos p = new Pos(1, 64, -3);
        assertTrue(ClientMutes.toggleRadio(0, p));
        assertTrue(ClientMutes.radioMuted(0, p, "http://a/"));
        assertFalse(ClientMutes.radioMuted(-1, p, "http://a/")); // outra dimensão, mesma posição
        assertFalse(ClientMutes.toggleRadio(0, p)); // alterna de volta
        assertFalse(ClientMutes.radioMuted(0, p, "http://a/"));
    }

    @Test
    void transmissorSilenciaQuemTocaAUrlDele() {
        assertTrue(ClientMutes.toggleUrl("http://a/"));
        assertTrue(ClientMutes.radioMuted(0, new Pos(9, 9, 9), "http://a/"));
        assertFalse(ClientMutes.radioMuted(0, new Pos(9, 9, 9), "http://b/"));
        assertFalse(ClientMutes.toggleUrl(""));
    }

    @Test
    void portatilDeOutroJogadorPeloUuid() {
        UUID steve = UUID.randomUUID();
        assertFalse(ClientMutes.toggleCarrier(42, null)); // sem UUID não silencia
        assertTrue(ClientMutes.toggleCarrier(42, steve));
        assertTrue(ClientMutes.carrierMuted(42));
        assertTrue(ClientMutes.any());
        // Trocou de dimensão: id de entidade novo. Reconhecido quando a entidade é vista de novo.
        assertFalse(ClientMutes.carrierMuted(77));
        ClientMutes.sawCarrier(77, steve);
        assertTrue(ClientMutes.carrierMuted(77));
        assertFalse(ClientMutes.toggleCarrier(77, steve)); // alterna de volta
        assertFalse(ClientMutes.carrierMuted(42));
        assertFalse(ClientMutes.carrierMuted(77));
    }

    @Test
    void limpaAoDesconectar() {
        ClientMutes.toggleRadio(0, new Pos(1, 2, 3));
        ClientMutes.toggleUrl("http://a/");
        ClientMutes.toggleCarrier(1, UUID.randomUUID());
        assertTrue(ClientMutes.any());
        ClientMutes.clear();
        assertFalse(ClientMutes.any());
        assertFalse(ClientMutes.urlMuted("http://a/"));
        assertFalse(ClientMutes.carrierMuted(1));
    }
}
