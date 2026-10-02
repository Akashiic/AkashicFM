package com.akashiic.fm.client.spatial;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.akashiic.fm.audio.spatial.OcclusionTracer;

/**
 * O mundo do cliente visto pelos raios de som. Guarda o último chunk consultado (um raio anda por blocos
 * vizinhos, quase sempre no mesmo chunk), não carrega nada: chunk que o cliente não tem é o chunk vazio do
 * Minecraft, só ar. Só a thread principal usa, e só dentro de {@link #begin}/{@link #end} de um mesmo tick
 * (entre ticks o chunk pode ter sido descarregado).
 */
final class WorldAcoustics implements OcclusionTracer.BlockAbsorption, OcclusionTracer.SurfaceDamping {

    private World world;
    private Chunk chunk;
    private int chunkX, chunkZ;
    /** Consultas ao mundo desde o {@link #begin} (diagnóstico do custo). */
    int lookups;

    void begin(World w) {
        world = w;
        chunk = null;
        lookups = 0;
    }

    /** Solta o mundo e o chunk: nenhuma referência sobrevive ao tick. */
    void end() {
        world = null;
        chunk = null;
    }

    @Override
    public double absorption(int x, int y, int z) {
        Block b = block(x, y, z);
        if (b == Blocks.air) return 0;
        return BlockAcoustics.absorption(b, chunk.getBlockMetadata(x & 15, y, z & 15), world, x, y, z);
    }

    @Override
    public double damping(int x, int y, int z) {
        Block b = block(x, y, z);
        if (b == Blocks.air) return -1;
        return BlockAcoustics.damping(b, chunk.getBlockMetadata(x & 15, y, z & 15), world, x, y, z);
    }

    private Block block(int x, int y, int z) {
        // Chunk.getBlock com y negativo estoura o array (crash report); acima do topo devolve ar.
        if (y < 0 || y > 255 || world == null) return Blocks.air;
        int cx = x >> 4, cz = z >> 4;
        if (chunk == null || cx != chunkX || cz != chunkZ) {
            chunk = world.getChunkFromChunkCoords(cx, cz);
            chunkX = cx;
            chunkZ = cz;
        }
        lookups++;
        Block b = chunk.getBlock(x & 15, y, z & 15);
        return b == null ? Blocks.air : b;
    }
}
