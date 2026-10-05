package com.akashiic.fm.content;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * O bloco do iPod: como a rádio (colocado no chão, virado para quem colocou, com caixas, tela e redstone), mas toca
 * uma fila de músicas em vez de uma URL. Botão direito abre a tela do iPod do bloco.
 */
public class BlockIPodPlayer extends BlockRadio {

    @SideOnly(Side.CLIENT)
    private IIcon iconFront, iconSide, iconTop;

    public BlockIPodPlayer() {
        setBlockName(AkashicFM.MODID + ".ipod_player");
        setBlockTextureName(AkashicFM.MODID + ":ipod_player_side");
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileIPodPlayer();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        iconFront = reg.registerIcon(AkashicFM.MODID + ":ipod_player_front");
        iconSide = reg.registerIcon(AkashicFM.MODID + ":ipod_player_side");
        iconTop = reg.registerIcon(AkashicFM.MODID + ":ipod_player_top");
        blockIcon = iconSide;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        if (side == Facing.sanitize(meta)) return iconFront;
        if (side == 0 || side == 1) return iconTop;
        return iconSide;
    }

    @Override
    protected void openGui(World world, int x, int y, int z) {
        AkashicFM.proxy.openIPodBlockGui(world, x, y, z);
    }
}
