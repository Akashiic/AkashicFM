package com.akashiic.fm.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FacingTest {

    /** Normal da face da frente (para fora do bloco), nos índices de lado do vanilla. */
    private static double[] frontNormal(int facing) {
        switch (facing) {
            case Facing.NORTH:
                return new double[] { 0, 0, -1 };
            case Facing.SOUTH:
                return new double[] { 0, 0, 1 };
            case Facing.WEST:
                return new double[] { -1, 0, 0 };
            default:
                return new double[] { 1, 0, 0 };
        }
    }

    @Test
    void direitaEFrenteDoObservadorVezesCima() {
        // Quem olha a frente do bloco olha no sentido oposto à normal; direita = olhar × cima.
        for (int f = Facing.NORTH; f <= Facing.EAST; f++) {
            double[] n = frontNormal(f);
            double[] look = { -n[0], -n[1], -n[2] };
            double[] up = { 0, 1, 0 };
            double[] right = { look[1] * up[2] - look[2] * up[1], look[2] * up[0] - look[0] * up[2],
                look[0] * up[1] - look[1] * up[0] };
            assertArrayEquals(right, Facing.rightVector(f), 1e-9, "facing " + f);
        }
    }

    @Test
    void metadataInvalidoViraSul() {
        assertEquals(Facing.SOUTH, Facing.sanitize(0));
        assertEquals(Facing.SOUTH, Facing.sanitize(1));
        assertEquals(Facing.SOUTH, Facing.sanitize(6));
        assertEquals(Facing.SOUTH, Facing.sanitize(-1));
        for (int f = Facing.NORTH; f <= Facing.EAST; f++) assertEquals(f, Facing.sanitize(f));
    }
}
