package com.akashiic.fm.content;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import com.akashiic.fm.AkashicFM;

/**
 * Antena: mastro fino que se empilha em cima do transmissor. Cada uma soma alcance (config). É só um bloco: quem
 * conta é o transmissor, olhando a coluna acima dele.
 */
public class BlockAntenna extends Block {

    public BlockAntenna() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(5.0F);
        setStepSound(soundTypeMetal);
        setBlockName(AkashicFM.MODID + ".antenna");
        setBlockTextureName(AkashicFM.MODID + ":antenna");
        setCreativeTab(FmContent.TAB);
        setBlockBounds(6f / 16f, 0f, 6f / 16f, 10f / 16f, 1f, 10f / 16f);
        setLightOpacity(0);
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }
}
