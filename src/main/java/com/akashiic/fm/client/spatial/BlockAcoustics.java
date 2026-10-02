package com.akashiic.fm.client.spatial;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.IFluidBlock;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.spatial.AcousticOverrides;
import com.akashiic.fm.content.BlockAntenna;

/**
 * Propriedades acústicas dos blocos, em duas grandezas diferentes:
 * <ul>
 * <li><b>absorção</b> (0..1, por bloco de caminho): quanto do som que <i>atravessa</i> o bloco se perde. Lã é o
 * isolante clássico do Minecraft; vidro deixa passar quase tudo;</li>
 * <li><b>amortecimento</b> (0..1) da superfície: quanto do som que <i>bate</i> nela não volta, para o reverb da
 * sala. Pedra, metal e vidro refletem; lã, neve e terra absorvem. -1 = não é superfície (ar, plantas, líquidos,
 * cercas).</li>
 * </ul>
 * O valor vem do material, ajustado pela forma (laje, escada, cerca, porta aberta ou fechada...), e pode ser
 * trocado por bloco no config ({@code modid:nome=absorção[,amortecimento]}). Só a thread principal do cliente usa.
 */
public final class BlockAcoustics {

    /** Forma do bloco: quanto do volume dele o som realmente encontra. */
    enum Shape {
        NONE,
        FULL,
        SLAB,
        STAIRS,
        THIN,
        CARPET,
        SNOW_LAYER,
        DOOR,
        TRAPDOOR,
        GATE,
        PARTIAL
    }

    static final class Entry {

        final Shape shape;
        final float absorption;
        final float damping;
        /** Absorção do config: substitui a absorção final, em qualquer estado do bloco. */
        final boolean absorptionOverridden;
        /** Amortecimento do config: vale para qualquer forma (até cercas podem virar superfície). */
        final boolean dampingOverridden;

        Entry(Shape shape, float absorption, float damping, boolean absorptionOverridden, boolean dampingOverridden) {
            this.shape = shape;
            this.absorption = absorption;
            this.damping = damping;
            this.absorptionOverridden = absorptionOverridden;
            this.dampingOverridden = dampingOverridden;
        }
    }

    private static final Map<Material, float[]> MATERIALS = new IdentityHashMap<>();
    /** Material sólido desconhecido (de outro mod). */
    private static final float[] UNKNOWN_SOLID = { 0.5f, 0.3f };

    static {
        // material, absorção, amortecimento (-1 = não reflete)
        put(Material.air, 0f, -1f);
        put(Material.glass, 0.1f, 0.05f);
        put(Material.ice, 0.2f, 0.05f);
        put(Material.packedIce, 0.25f, 0.05f);
        put(Material.leaves, 0.15f, 0.7f);
        put(Material.water, 0.3f, -1f);
        put(Material.lava, 0.5f, -1f);
        put(Material.wood, 0.4f, 0.35f);
        put(Material.cloth, 0.9f, 0.9f);
        put(Material.carpet, 0.9f, 0.9f);
        put(Material.sponge, 0.95f, 0.95f);
        put(Material.rock, 0.6f, 0.15f);
        put(Material.piston, 0.6f, 0.15f);
        put(Material.iron, 0.75f, 0.08f);
        put(Material.anvil, 0.75f, 0.08f);
        put(Material.ground, 0.5f, 0.55f);
        put(Material.grass, 0.5f, 0.55f);
        put(Material.sand, 0.5f, 0.6f);
        put(Material.clay, 0.5f, 0.5f);
        put(Material.craftedSnow, 0.45f, 0.85f);
        put(Material.snow, 0.45f, 0.85f);
        put(Material.gourd, 0.4f, 0.5f);
        put(Material.cactus, 0.3f, 0.5f);
        put(Material.coral, 0.5f, 0.4f);
        put(Material.tnt, 0.4f, 0.5f);
        put(Material.redstoneLight, 0.5f, 0.15f);
        put(Material.cake, 0.3f, 0.6f);
        put(Material.dragonEgg, 0.5f, 0.2f);
        put(Material.web, 0.1f, -1f);
        put(Material.plants, 0f, -1f);
        put(Material.vine, 0f, -1f);
        put(Material.circuits, 0f, -1f);
        put(Material.portal, 0f, -1f);
        put(Material.fire, 0f, -1f);
    }

    private static final IdentityHashMap<Block, Entry> CACHE = new IdentityHashMap<>();
    private static String[] overridesSource;
    private static Map<String, AcousticOverrides.Value> overrides = new HashMap<>();

    private BlockAcoustics() {}

    private static void put(Material m, float absorption, float damping) {
        MATERIALS.put(m, new float[] { absorption, damping });
    }

    /**
     * Aplica a lista de overrides do config. Só refaz o cache se a lista mudou (o GTNHLib troca o array quando o
     * config é salvo). Entradas inválidas são ignoradas com aviso no log.
     */
    public static void configure(String[] entries) {
        if (entries == overridesSource) return;
        overridesSource = entries;
        overrides = AcousticOverrides
            .parse(entries, bad -> AkashicFM.LOG.warn("AkashicFM: override acústico inválido ignorado: \"{}\"", bad));
        CACHE.clear();
    }

    /** Absorção por bloco de caminho, 0..1, já com a forma e o estado (porta aberta, camadas de neve...). */
    public static double absorption(Block block, int meta, IBlockAccess world, int x, int y, int z) {
        Entry e = entry(block);
        if (e.absorptionOverridden) return e.absorption;
        switch (e.shape) {
            case NONE:
                return 0;
            case FULL:
                return e.absorption;
            case SLAB:
                return e.absorption * 0.5;
            case STAIRS:
                return e.absorption * 0.75;
            case THIN:
                return e.absorption * 0.25;
            case CARPET:
                return e.absorption * 0.1;
            case SNOW_LAYER:
                return e.absorption * ((meta & 7) + 1) / 8.0;
            case DOOR:
                return e.absorption * (doorOpen(block, world, x, y, z) ? 0.1 : 0.75);
            case TRAPDOOR:
                return e.absorption * (BlockTrapDoor.func_150118_d(meta) ? 0.1 : 0.5);
            case GATE:
                return e.absorption * (BlockFenceGate.isFenceGateOpen(meta) ? 0.05 : 0.25);
            default: // PARTIAL
                return e.absorption * 0.5;
        }
    }

    /** Amortecimento da superfície para o reverb, ou -1 se o bloco não reflete som. */
    public static double damping(Block block, int meta, IBlockAccess world, int x, int y, int z) {
        Entry e = entry(block);
        if (e.damping < 0) return -1;
        switch (e.shape) {
            case FULL:
            case SLAB:
            case STAIRS:
            case PARTIAL:
                return e.damping;
            case DOOR:
                return doorOpen(block, world, x, y, z) ? -1 : e.damping;
            case TRAPDOOR:
                return BlockTrapDoor.func_150118_d(meta) ? -1 : e.damping;
            default:
                return e.dampingOverridden ? e.damping : -1;
        }
    }

    private static boolean doorOpen(Block block, IBlockAccess world, int x, int y, int z) {
        return block instanceof BlockDoor && ((BlockDoor) block).func_150015_f(world, x, y, z);
    }

    static Entry entry(Block block) {
        Entry e = CACHE.get(block);
        if (e == null) {
            try {
                e = classify(block);
            } catch (RuntimeException ex) {
                // Bloco de outro mod que falha ao responder material/forma: trata como sólido comum.
                AkashicFM.LOG.warn("AkashicFM: acústica do bloco {} indisponível; usando o padrão", block, ex);
                e = new Entry(Shape.FULL, UNKNOWN_SOLID[0], UNKNOWN_SOLID[1], false, false);
            }
            CACHE.put(block, e);
        }
        return e;
    }

    private static Entry classify(Block block) {
        Material m = block.getMaterial();
        Shape shape = shapeOf(block, m);
        float[] v = MATERIALS.get(m);
        if (v == null) {
            if (m != null && m.isLiquid()) v = MATERIALS.get(Material.water);
            else if (m == null || !m.blocksMovement()) v = MATERIALS.get(Material.air);
            else v = UNKNOWN_SOLID;
        }
        AcousticOverrides.Value o = overrides.isEmpty() ? null : overrides.get(registryName(block));
        if (o == null) return new Entry(shape, v[0], v[1], false, false);
        float damping = o.hasDamping() ? o.damping : v[1];
        return new Entry(shape, o.absorption, damping, true, o.hasDamping());
    }

    private static Shape shapeOf(Block block, Material m) {
        if (m == null || m == Material.air) return Shape.NONE;
        if (block instanceof BlockDoor) return Shape.DOOR;
        if (block instanceof BlockTrapDoor) return Shape.TRAPDOOR;
        if (block instanceof BlockFenceGate) return Shape.GATE;
        if (block instanceof BlockSlab) return block.isOpaqueCube() ? Shape.FULL : Shape.SLAB;
        if (block instanceof BlockStairs) return Shape.STAIRS;
        if (block instanceof BlockFence || block instanceof BlockWall
            || block instanceof BlockPane
            || block instanceof BlockAntenna) return Shape.THIN;
        if (block instanceof BlockCarpet) return Shape.CARPET;
        if (block instanceof BlockSnow) return Shape.SNOW_LAYER;
        if (block instanceof BlockLiquid || block instanceof IFluidBlock) return Shape.FULL;
        if (m == Material.web) return Shape.FULL;
        if (!m.blocksMovement()) return Shape.NONE;
        if (block.isOpaqueCube()) return Shape.FULL;
        // Blocos inteiros translúcidos (o isOpaqueCube das folhas ainda depende do gráfico "fancy").
        if (m == Material.glass || m == Material.ice || m == Material.packedIce || m == Material.leaves)
            return Shape.FULL;
        if (block.renderAsNormalBlock()) return Shape.FULL;
        return Shape.PARTIAL; // baús, canos, máquinas com modelo próprio...
    }

    private static String registryName(Block block) {
        try {
            String name = Block.blockRegistry.getNameForObject(block);
            return name == null ? "" : name.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
