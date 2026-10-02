package com.akashiic.fm.client.spatial;

import java.lang.ref.WeakReference;

import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.spatial.OcclusionScheduler;
import com.akashiic.fm.audio.spatial.OcclusionTracer;
import com.akashiic.fm.common.FmConfig;

/**
 * Oclusão dos emissores das rádios para o ouvinte, a cada tick do cliente (thread principal). O
 * {@link OcclusionScheduler} decide quem recalcular dentro do orçamento; os raios andam pelo mundo do cliente via
 * {@link WorldAcoustics}, sem carregar chunk nenhum.
 */
public final class OcclusionField {

    public static final OcclusionField INSTANCE = new OcclusionField();

    /**
     * Blocos visitados por tick, somando todos os emissores. Uma consulta custa ~50 ns (array do chunk): o
     * orçamento cheio fica abaixo de 0,5 ms por tick.
     */
    static final int STEP_BUDGET = 8192;

    private final OcclusionScheduler scheduler = new OcclusionScheduler();
    private final WorldAcoustics acoustics = new WorldAcoustics();
    private WeakReference<World> lastWorld = new WeakReference<>(null);
    private boolean failureLogged;

    private OcclusionField() {}

    /** Oclusão (0..1) de cada emissor ({@code xyz} em trios) para o ouvinte em L. Desligada no config: zeros. */
    public double[] resolve(World world, double lx, double ly, double lz, double[] xyz) {
        if (!FmConfig.Client.enableOcclusion || world == null) {
            clear();
            return new double[xyz.length / 3];
        }
        if (lastWorld.get() != world) {
            scheduler.clear();
            lastWorld = new WeakReference<>(world);
        }
        BlockAcoustics.configure(FmConfig.Client.acousticOverrides);
        acoustics.begin(world);
        try {
            return scheduler.resolve(
                xyz,
                lx,
                ly,
                lz,
                STEP_BUDGET,
                (ex, ey, ez, steps) -> OcclusionTracer.occlusion(
                    acoustics,
                    lx,
                    ly,
                    lz,
                    ex,
                    ey,
                    ez,
                    OcclusionTracer.worstCaseSteps(lx, ly, lz, ex, ey, ez),
                    steps));
        } catch (RuntimeException e) {
            // Nunca derruba o tick do cliente: segue sem oclusão neste tick.
            if (!failureLogged) {
                failureLogged = true;
                AkashicFM.LOG.warn("AkashicFM: falha ao calcular a oclusão; seguindo sem ela", e);
            }
            scheduler.clear();
            return new double[xyz.length / 3];
        } finally {
            acoustics.end();
        }
    }

    /** Esquece o cache (mundo descarregado, oclusão desligada). */
    public void clear() {
        if (scheduler.size() > 0) scheduler.clear();
        lastWorld = new WeakReference<>(null);
    }

    /** Diagnóstico do último tick: blocos visitados, emissores traçados e adiados. */
    public int lastSteps() {
        return scheduler.lastSteps;
    }

    public int lastTraced() {
        return scheduler.lastTraced;
    }

    public int lastDeferred() {
        return scheduler.lastDeferred;
    }
}
