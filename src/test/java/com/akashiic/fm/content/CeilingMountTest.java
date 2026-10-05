package com.akashiic.fm.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Onde o alto-falante de teto/parede fica preso, a forma da placa, de onde sai o som e a face exposta. */
class CeilingMountTest {

    private static final double EPS = 1e-9;
    private static final int[] SIDES = { Facing.NORTH, Facing.SOUTH, Facing.WEST, Facing.EAST };

    @Test
    void ladoClicadoViraOMetadata() {
        assertEquals(CeilingMount.DEFAULT, CeilingMount.forSide(0)); // embaixo de um bloco: teto
        assertEquals(-1, CeilingMount.forSide(1)); // em cima de um bloco: não vai
        for (int side : SIDES) assertEquals(side, CeilingMount.forSide(side)); // na lateral: parede, virado para fora
        assertEquals(-1, CeilingMount.forSide(6));
        assertEquals(-1, CeilingMount.forSide(-1));
    }

    @Test
    void tetoGuardaAOrientacaoParaOEstereo() {
        assertTrue(CeilingMount.isCeiling(CeilingMount.DEFAULT));
        assertEquals(Facing.SOUTH, CeilingMount.stereoFacing(CeilingMount.DEFAULT));
        for (int f : SIDES) {
            int m = CeilingMount.ceiling(f);
            assertTrue(m >= 6 && m <= 9);
            assertTrue(CeilingMount.isCeiling(m));
            assertEquals(f, CeilingMount.stereoFacing(m));
            assertEquals(0, CeilingMount.frontSide(m)); // a grade olha para baixo
        }
        for (int f : SIDES) {
            assertFalse(CeilingMount.isCeiling(f));
            assertEquals(f, CeilingMount.stereoFacing(f));
            assertEquals(f, CeilingMount.frontSide(f));
        }
    }

    @Test
    void metadataInvalidoViraOPadrao() {
        for (int bad : new int[] { 0, 1, 10, 15, -3 }) assertEquals(CeilingMount.DEFAULT, CeilingMount.sanitize(bad));
        assertEquals(Facing.SOUTH, CeilingMount.stereoFacing(0)); // o item no inventário
    }

    @Test
    void apoioFicaAtrasDaPlaca() {
        assertArrayEquals(new int[] { 0, 1, 0 }, CeilingMount.supportOffset(CeilingMount.ceiling(Facing.EAST)));
        assertArrayEquals(new int[] { 0, 0, 1 }, CeilingMount.supportOffset(Facing.NORTH));
        assertArrayEquals(new int[] { 0, 0, -1 }, CeilingMount.supportOffset(Facing.SOUTH));
        assertArrayEquals(new int[] { 1, 0, 0 }, CeilingMount.supportOffset(Facing.WEST));
        assertArrayEquals(new int[] { -1, 0, 0 }, CeilingMount.supportOffset(Facing.EAST));
        // A face do apoio que encosta na placa é a que aponta para o alto-falante.
        for (int m = 2; m <= 9; m++) assertEquals(CeilingMount.frontSide(m), CeilingMount.supportSide(m));
    }

    @Test
    void placaFinaEncostadaNoApoio() {
        for (int m = 2; m <= 9; m++) {
            double[] b = CeilingMount.bounds(m);
            double dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
            int[] d = CeilingMount.supportOffset(m);
            // Na direção do apoio, 3 px de espessura, colada na face do bloco; nas outras, 12 px.
            assertEquals(d[0] != 0 ? 3 / 16.0 : 12 / 16.0, dx, EPS, "dx meta " + m);
            assertEquals(d[1] != 0 ? 3 / 16.0 : 12 / 16.0, dy, EPS, "dy meta " + m);
            assertEquals(d[2] != 0 ? 3 / 16.0 : 12 / 16.0, dz, EPS, "dz meta " + m);
            if (d[0] > 0) assertEquals(1, b[3], EPS);
            if (d[0] < 0) assertEquals(0, b[0], EPS);
            if (d[1] > 0) assertEquals(1, b[4], EPS);
            if (d[2] > 0) assertEquals(1, b[5], EPS);
            if (d[2] < 0) assertEquals(0, b[2], EPS);
            for (double v : b) assertTrue(v >= 0 && v <= 1, "dentro do bloco");
        }
    }

    @Test
    void somSaiDoCentroDaPlacaDentroDoBloco() {
        for (int m = 2; m <= 9; m++) {
            double[] b = CeilingMount.bounds(m), e = CeilingMount.emitter(m);
            for (int i = 0; i < 3; i++) {
                assertTrue(e[i] > b[i] && e[i] < b[i + 3], "emissor dentro da placa, meta " + m);
                assertTrue(e[i] > 0 && e[i] < 1, "emissor dentro do bloco (a oclusão ignora o bloco da fonte)");
            }
        }
        assertArrayEquals(new double[] { 0.5, 1 - 1.5 / 16, 0.5 }, CeilingMount.emitter(CeilingMount.DEFAULT), EPS);
    }

    @Test
    void faceExpostaOndeOConeEDesenhado() {
        // O TESR anda FACE_OFFSET a partir do centro, no sentido para onde a face olha: tem que cair na face da placa.
        for (int m = 2; m <= 9; m++) {
            double[] b = CeilingMount.bounds(m);
            // A face olha para o lado oposto ao apoio: apoio em +eixo, a face é o mínimo da placa nesse eixo.
            double near = 0.5 - CeilingMount.FACE_OFFSET, far = 0.5 + CeilingMount.FACE_OFFSET;
            int[] d = CeilingMount.supportOffset(m);
            if (d[1] > 0) assertEquals(near, b[1], EPS); // a face de baixo da placa do teto
            if (d[2] > 0) assertEquals(near, b[2], EPS);
            if (d[2] < 0) assertEquals(far, b[5], EPS);
            if (d[0] > 0) assertEquals(near, b[0], EPS);
            if (d[0] < 0) assertEquals(far, b[3], EPS);
        }
    }
}
