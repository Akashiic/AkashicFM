package com.akashiic.fm.content;

/**
 * Como o alto-falante de teto/parede está preso, guardado no metadata. Uma placa fina (12 × 12 px, 3 px de espessura)
 * encostada no bloco de apoio:
 * <ul>
 * <li>2..5: na parede, com a frente virada para o lado do metadata (os índices de {@link Facing}); o apoio fica atrás;
 * <li>6..9: no teto, com o apoio em cima e a orientação de quem colocou ({@code meta − 4}) para o par estéreo.
 * </ul>
 * Só contas: nada de mundo aqui (testável sem o jogo).
 */
public final class CeilingMount {

    private CeilingMount() {}

    /** No teto, virado para o sul: o metadata do item (o inventário desenha com 0) e o de um valor inválido. */
    public static final int DEFAULT = 6 + Facing.SOUTH - Facing.NORTH;
    /** Espessura da placa, em blocos. */
    public static final double THICKNESS = 3 / 16.0;
    /** Metade do lado da placa, em blocos (12 px de lado). */
    private static final double HALF = 6 / 16.0;
    /** Onde fica a face exposta, a partir do centro do bloco, no sentido para onde ela olha (atrás do centro). */
    public static final double FACE_OFFSET = THICKNESS - 0.5;

    public static int sanitize(int meta) {
        return meta >= 2 && meta <= 9 ? meta : DEFAULT;
    }

    public static boolean isCeiling(int meta) {
        return sanitize(meta) >= 6;
    }

    /**
     * O metadata ao colocar clicando na face {@code side} de um bloco: embaixo dele (0) fica no teto, num lado (2..5)
     * fica na parede virado para fora; em cima (1) não vai: devolve -1.
     */
    public static int forSide(int side) {
        if (side == 0) return DEFAULT;
        if (side >= 2 && side <= 5) return side;
        return -1;
    }

    /** No teto, com a frente (do estéreo) virada para {@code facing}. */
    public static int ceiling(int facing) {
        return 4 + Facing.sanitize(facing);
    }

    /** A orientação horizontal para o par estéreo (a mesma regra da caixa de chão). */
    public static int stereoFacing(int meta) {
        int m = sanitize(meta);
        return m >= 6 ? m - 4 : m;
    }

    /** A face exposta (o lado do vanilla): embaixo, no teto; a frente, na parede. */
    public static int frontSide(int meta) {
        int m = sanitize(meta);
        return m >= 6 ? 0 : m;
    }

    /** A face do bloco de apoio onde a placa encosta (a que aponta para o alto-falante). */
    public static int supportSide(int meta) {
        return frontSide(meta);
    }

    /** O deslocamento até o bloco de apoio: em cima no teto, atrás na parede. */
    public static int[] supportOffset(int meta) {
        switch (frontSide(meta)) {
            case 0:
                return new int[] { 0, 1, 0 };
            case Facing.NORTH: // frente para -Z: apoio em +Z
                return new int[] { 0, 0, 1 };
            case Facing.SOUTH:
                return new int[] { 0, 0, -1 };
            case Facing.WEST:
                return new int[] { 1, 0, 0 };
            default: // EAST
                return new int[] { -1, 0, 0 };
        }
    }

    /** A caixa da placa dentro do bloco: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public static double[] bounds(int meta) {
        double lo = 0.5 - HALF, hi = 0.5 + HALF, t = THICKNESS;
        switch (frontSide(meta)) {
            case 0:
                return new double[] { lo, 1 - t, lo, hi, 1, hi };
            case Facing.NORTH:
                return new double[] { lo, lo, 1 - t, hi, hi, 1 };
            case Facing.SOUTH:
                return new double[] { lo, lo, 0, hi, hi, t };
            case Facing.WEST:
                return new double[] { 1 - t, lo, lo, 1, hi, hi };
            default: // EAST
                return new double[] { 0, lo, lo, t, hi, hi };
        }
    }

    /** De onde o som sai, dentro do bloco: o centro da placa. */
    public static double[] emitter(int meta) {
        double[] b = bounds(meta);
        return new double[] { (b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2 };
    }
}
