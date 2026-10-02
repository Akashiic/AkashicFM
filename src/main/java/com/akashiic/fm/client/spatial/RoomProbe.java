package com.akashiic.fm.client.spatial;

import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.spatial.OcclusionTracer;
import com.akashiic.fm.audio.spatial.RoomModel;

/**
 * Sondagem da sala do ouvinte para o reverb: uma vez por segundo, 16 raios a partir da cabeça do jogador até a
 * primeira superfície refletora (no máximo 48 blocos). As direções giram a cada sondagem (ângulo áureo), então
 * com a suavização o resultado é a média de muitas direções. Thread principal, dentro do tick.
 */
public final class RoomProbe {

    public static final RoomProbe INSTANCE = new RoomProbe();

    static final int RAYS = 16;
    static final int PERIOD_TICKS = 20;
    static final double MAX_DISTANCE = 48;
    /** Fração do caminho até a nova leitura a cada tick (~90% em meio segundo). */
    static final double SMOOTHING = 0.2;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

    private final WorldAcoustics acoustics = new WorldAcoustics();
    private final double[] distances = new double[RAYS];
    private final double[] dampings = new double[RAYS];
    private final double[] hit = new double[1];
    private RoomModel.Params target = RoomModel.DRY, current = RoomModel.DRY;
    private int countdown;
    private double phase;
    private int probes;
    private boolean failureLogged;

    private RoomProbe() {}

    /** Um tick: sonda quando é hora e devolve a sala suavizada. */
    public RoomModel.Params tick(World world, double lx, double ly, double lz) {
        if (--countdown <= 0) {
            countdown = PERIOD_TICKS;
            try {
                target = probe(world, lx, ly, lz);
            } catch (RuntimeException e) {
                // Nunca derruba o tick do cliente: sem leitura, volta para o campo aberto.
                if (!failureLogged) {
                    failureLogged = true;
                    AkashicFM.LOG.warn("AkashicFM: falha na sondagem da sala; reverb desligado nesta leitura", e);
                }
                target = RoomModel.DRY;
            }
            probes++;
        }
        current = RoomModel.blend(current, target, SMOOTHING);
        return current;
    }

    private RoomModel.Params probe(World world, double lx, double ly, double lz) {
        double[][] dirs = RoomModel.directions(RAYS, phase);
        phase = (phase + GOLDEN_ANGLE) % (2 * Math.PI);
        acoustics.begin(world);
        try {
            for (int i = 0; i < RAYS; i++) {
                hit[0] = 0;
                distances[i] = OcclusionTracer
                    .firstHit(acoustics, lx, ly, lz, dirs[i][0], dirs[i][1], dirs[i][2], MAX_DISTANCE, hit);
                dampings[i] = hit[0];
            }
        } finally {
            acoustics.end();
        }
        return RoomModel.fromProbe(distances, dampings);
    }

    /** Volta ao campo aberto (mundo descarregado, reverb desligado, nada tocando). */
    public void reset() {
        target = current = RoomModel.DRY;
        countdown = 0;
    }

    /** Última leitura (sem suavização) e quantas sondagens já rodaram (diagnóstico). */
    public RoomModel.Params target() {
        return target;
    }

    public RoomModel.Params current() {
        return current;
    }

    public int probes() {
        return probes;
    }
}
