package com.akashiic.fm.audio.spatial;

/**
 * Oclusão do som por blocos entre o ouvinte e uma fonte. Funções puras (o mundo entra por
 * {@link BlockAbsorption}), testadas numa grade sintética.
 * <p>
 * Cada raio percorre os blocos com DDA voxel (Amanatides &amp; Woo). A atenuação de um bloco depende do
 * <b>comprimento do caminho dentro dele</b> (lei de Beer–Lambert): um bloco de absorção {@code a} atravessado
 * por {@code l} blocos de caminho transmite {@code (1 − a)^l}. Assim uma parede de 1 bloco atenua igual, não
 * importa se o raio diagonal passa por 1 ou 2 voxels dela, e um raio que raspa a quina de um bloco atenua quase
 * nada. Contar voxels inteiros fazia a oclusão oscilar entre 0,9 e 0,99 ao andar ao longo de uma parede.
 * <p>
 * O bloco do ouvinte e o da fonte nunca contam. São 5 raios: para o centro da fonte e para 4 pontos em volta
 * dela, perpendiculares à direção e sempre dentro do bloco da fonte. A média dá uma transição suave quando a
 * fonte sai de trás de uma quina, em vez de liga/desliga.
 */
public final class OcclusionTracer {

    /** Quanto um bloco absorve por bloco de caminho: 0 = ar/transparente, 1 = bloqueia quase tudo. */
    public interface BlockAbsorption {

        double absorption(int x, int y, int z);
    }

    /** Superfícies que refletem som, para a sondagem da sala. */
    public interface SurfaceDamping {

        /** -1 se o bloco não reflete (ar, plantas, líquidos); senão o amortecimento da superfície, 0..1. */
        double damping(int x, int y, int z);
    }

    /** Raio dos 4 raios em volta da fonte (blocos). Menor que 0,5: do centro do bloco, fica dentro dele. */
    public static final double SPREAD = 0.4;
    /** Teto da absorção: 1 exato faria um raio raspando a quina zerar de uma vez. */
    static final double MAX_ABSORPTION = 0.999;
    /** Transmissão abaixo disto conta como zero (o raio para cedo). */
    private static final double MIN_TRANSMISSION = 1e-3;
    private static final double MAX_OPTICAL_DEPTH = -Math.log(MIN_TRANSMISSION);
    /** Margem para os pontos deslocados ficarem estritamente dentro do bloco da fonte. */
    private static final double EDGE = 1e-3;

    private OcclusionTracer() {}

    /**
     * Transmissão (0..1) no segmento de A até B, sem contar o bloco de A nem o de B. {@code steps[0]} acumula os
     * blocos visitados (orçamento de quem chama); o raio para depois de {@code maxSteps} blocos.
     */
    public static double transmission(BlockAbsorption world, double ax, double ay, double az, double bx, double by,
        double bz, int maxSteps, int[] steps) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1e-9)) return 1; // também pega NaN
        int x = floor(ax), y = floor(ay), z = floor(az);
        int ex = floor(bx), ey = floor(by), ez = floor(bz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? 1.0 / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? 1.0 / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = dz != 0 ? 1.0 / Math.abs(dz) : Double.POSITIVE_INFINITY;
        double tMaxX = dx != 0 ? (stepX > 0 ? x + 1 - ax : ax - x) * tDeltaX : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0 ? (stepY > 0 ? y + 1 - ay : ay - y) * tDeltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = dz != 0 ? (stepZ > 0 ? z + 1 - az : az - z) * tDeltaZ : Double.POSITIVE_INFINITY;
        double depth = 0; // profundidade óptica acumulada: transmissão = e^-depth
        double tEnter = 0;
        boolean inStart = true;
        for (int i = 0; i <= maxSteps; i++) {
            double tExit = Math.min(1.0, Math.min(tMaxX, Math.min(tMaxY, tMaxZ)));
            if (!inStart) {
                double a = world.absorption(x, y, z);
                if (a > 0) {
                    depth += (tExit - tEnter) * len * -Math.log(1 - Math.min(MAX_ABSORPTION, a));
                    if (depth > MAX_OPTICAL_DEPTH) return 0;
                }
            }
            if (tExit >= 1.0 || i == maxSteps) break;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            tEnter = tExit;
            inStart = false;
            if (x == ex && y == ey && z == ez) break; // chegou no bloco da fonte
            if (steps != null) steps[0]++;
        }
        return Math.exp(-depth);
    }

    /**
     * Oclusão (0 = livre, 1 = bloqueada) da fonte em E para o ouvinte em L: 1 − média da transmissão dos 5 raios.
     */
    public static double occlusion(BlockAbsorption world, double lx, double ly, double lz, double ex, double ey,
        double ez, int maxSteps, int[] steps) {
        double dx = ex - lx, dy = ey - ly, dz = ez - lz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1e-6)) return 0;
        dx /= len;
        dy /= len;
        dz /= len;
        // Base perpendicular à direção: u = d × a (a = eixo X, ou Y se d está quase em X), v = d × u.
        double ax = Math.abs(dx) < 0.9 ? 1 : 0, ay = 1 - ax;
        double ux = -dz * ay, uy = dz * ax, uz = dx * ay - dy * ax;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;
        int bx = floor(ex), by = floor(ey), bz = floor(ez);
        double r = SPREAD;
        double sum = transmission(world, lx, ly, lz, ex, ey, ez, maxSteps, steps);
        sum += ray(world, lx, ly, lz, ex + ux * r, ey + uy * r, ez + uz * r, bx, by, bz, maxSteps, steps);
        sum += ray(world, lx, ly, lz, ex - ux * r, ey - uy * r, ez - uz * r, bx, by, bz, maxSteps, steps);
        sum += ray(world, lx, ly, lz, ex + vx * r, ey + vy * r, ez + vz * r, bx, by, bz, maxSteps, steps);
        sum += ray(world, lx, ly, lz, ex - vx * r, ey - vy * r, ez - vz * r, bx, by, bz, maxSteps, steps);
        return clamp01(1 - sum / 5);
    }

    /** Raio para um ponto deslocado, preso dentro do bloco da fonte (o bloco da fonte nunca conta). */
    private static double ray(BlockAbsorption world, double lx, double ly, double lz, double px, double py, double pz,
        int bx, int by, int bz, int maxSteps, int[] steps) {
        return transmission(world, lx, ly, lz, inside(px, bx), inside(py, by), inside(pz, bz), maxSteps, steps);
    }

    private static double inside(double v, int block) {
        return Math.max(block + EDGE, Math.min(block + 1 - EDGE, v));
    }

    /** Blocos que {@link #occlusion} pode visitar no pior caso, para o orçamento de quem chama. */
    public static int worstCaseSteps(double lx, double ly, double lz, double ex, double ey, double ez) {
        double l1 = Math.abs(ex - lx) + Math.abs(ey - ly) + Math.abs(ez - lz);
        if (!(l1 < 1e6)) return Integer.MAX_VALUE / 2;
        return 5 * ((int) Math.ceil(l1) + 4);
    }

    /** Ganho do caminho direto para uma oclusão: nunca zera (o som contorna e atravessa). */
    public static double directGain(double occlusion) {
        return 1 - 0.7 * clamp01(occlusion);
    }

    /** Ganho dos agudos (low-pass): paredes abafam muito mais os agudos que os graves. */
    public static double highFrequencyGain(double occlusion) {
        double t = 1 - clamp01(occlusion);
        return Math.max(0.02, t * t);
    }

    /**
     * Ganho do envio para o reverb: o som de uma fonte atrás da parede ainda chega ao ambiente do ouvinte por
     * frestas e reflexões, então é menos atenuado que o caminho direto.
     */
    public static double sendGain(double occlusion) {
        return 1 - 0.5 * clamp01(occlusion);
    }

    /** Agudos do envio para o reverb, menos abafados que os do caminho direto pelo mesmo motivo. */
    public static double sendHighFrequencyGain(double occlusion) {
        return Math.max(0.1, 1 - clamp01(occlusion));
    }

    /** Sem EFX (sem low-pass), só o ganho, um pouco mais forte para compensar a falta do abafado. */
    public static double gainOnly(double occlusion) {
        return 1 - 0.85 * clamp01(occlusion);
    }

    /**
     * Distância de L até a primeira superfície refletora no raio de direção d (unitária), ou -1 se não houver
     * até {@code maxDist}. O bloco de L não conta. {@code hitDamping[0]} recebe o amortecimento da superfície.
     */
    public static double firstHit(SurfaceDamping world, double lx, double ly, double lz, double dx, double dy,
        double dz, double maxDist, double[] hitDamping) {
        int x = floor(lx), y = floor(ly), z = floor(lz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? 1.0 / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? 1.0 / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = dz != 0 ? 1.0 / Math.abs(dz) : Double.POSITIVE_INFINITY;
        double tMaxX = dx != 0 ? (stepX > 0 ? x + 1 - lx : lx - x) * tDeltaX : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0 ? (stepY > 0 ? y + 1 - ly : ly - y) * tDeltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = dz != 0 ? (stepZ > 0 ? z + 1 - lz : lz - z) * tDeltaZ : Double.POSITIVE_INFINITY;
        while (true) {
            double t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (!(t <= maxDist)) return -1; // também termina com direção nula ou NaN
            double d = world.damping(x, y, z);
            if (d >= 0) {
                if (hitDamping != null) hitDamping[0] = Math.min(1, d);
                return t;
            }
        }
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
