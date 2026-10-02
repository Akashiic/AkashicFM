package com.akashiic.fm.content;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

/** Orientação horizontal guardada no metadata, nos mesmos índices de lado do vanilla (2=N, 3=S, 4=O, 5=L). */
public final class Facing {

    private Facing() {}

    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    /** Frente virada para quem colocou (mesma regra da fornalha). */
    public static int fromPlacer(EntityLivingBase placer) {
        int quadrant = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        switch (quadrant) {
            case 0:
                return NORTH;
            case 1:
                return EAST;
            case 2:
                return SOUTH;
            default:
                return WEST;
        }
    }

    /** Metadata inválido (ex.: 0 na renderização do item) vira SUL, para a frente aparecer no inventário. */
    public static int sanitize(int meta) {
        return meta >= NORTH && meta <= EAST ? meta : SOUTH;
    }

    /** Vetor unitário "para a direita" de quem olha a frente do bloco, para posicionar canais L/R. */
    public static double[] rightVector(int facing) {
        switch (sanitize(facing)) {
            case NORTH: // frente aponta para -Z; quem olha para a frente está em -Z olhando +Z: direita = -X
                return new double[] { -1, 0, 0 };
            case SOUTH:
                return new double[] { 1, 0, 0 };
            case WEST: // frente aponta para -X; observador olha +X: direita = +Z
                return new double[] { 0, 0, 1 };
            default: // EAST
                return new double[] { 0, 0, -1 };
        }
    }
}
