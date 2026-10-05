package com.akashiic.fm.content;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.akashiic.fm.AkashicFM;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Alto-falante de teto ou de parede (arandela): uma placa fina presa embaixo de um bloco ou na lateral dele, ligada
 * pelo sintonizador a uma rádio ou a um bloco do iPod, como a caixa de chão. Não vai em cima de bloco; precisa de uma
 * face sólida atrás e cai se o apoio sumir (desligando-se da rádio). A forma e o apoio vêm de {@link CeilingMount}.
 */
public class BlockCeilingSpeaker extends BlockSpeaker {

    @SideOnly(Side.CLIENT)
    private IIcon iconFront, iconSide;

    public BlockCeilingSpeaker() {
        setBlockName(AkashicFM.MODID + ".ceiling_speaker");
        setBlockTextureName(AkashicFM.MODID + ":ceiling_speaker_side");
        setHardness(0.8F);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileCeilingSpeaker();
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public void setBlockBoundsBasedOnState(IBlockAccess world, int x, int y, int z) {
        setBounds(CeilingMount.bounds(world.getBlockMetadata(x, y, z)));
    }

    /** No inventário, a placa deitada e centrada (com a grade em cima, ver {@link #getIcon}). */
    @Override
    public void setBlockBoundsForItemRender() {
        double half = CeilingMount.THICKNESS / 2;
        double[] b = CeilingMount.bounds(CeilingMount.DEFAULT);
        setBlockBounds(
            (float) b[0],
            (float) (0.5 - half),
            (float) b[2],
            (float) b[3],
            (float) (0.5 + half),
            (float) b[5]);
    }

    private void setBounds(double[] b) {
        setBlockBounds((float) b[0], (float) b[1], (float) b[2], (float) b[3], (float) b[4], (float) b[5]);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        setBlockBoundsBasedOnState(world, x, y, z);
        return super.getCollisionBoundingBoxFromPool(world, x, y, z);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int x, int y, int z) {
        setBlockBoundsBasedOnState(world, x, y, z);
        return super.getSelectedBoundingBoxFromPool(world, x, y, z);
    }

    /** Clicando embaixo de um bloco (teto) ou na lateral (parede), com a face de apoio sólida. */
    @Override
    public boolean canPlaceBlockOnSide(World world, int x, int y, int z, int side) {
        int meta = CeilingMount.forSide(side);
        return meta >= 0 && supported(world, x, y, z, meta);
    }

    @Override
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        for (int side : new int[] { 0, 2, 3, 4, 5 }) if (canPlaceBlockOnSide(world, x, y, z, side)) return true;
        return false;
    }

    @Override
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ, int meta) {
        int m = CeilingMount.forSide(side);
        return m >= 0 ? m : CeilingMount.DEFAULT;
    }

    /** No teto, a orientação (do estéreo) é a de quem colocou; na parede, fica a do lado clicado. */
    @Override
    protected int placedMeta(World world, int x, int y, int z, EntityLivingBase placer) {
        int meta = world.getBlockMetadata(x, y, z);
        return CeilingMount.isCeiling(meta) ? CeilingMount.ceiling(Facing.fromPlacer(placer))
            : CeilingMount.sanitize(meta);
    }

    /** O apoio sumiu (quebrado, empurrado, explodido): cai como item, e o breakBlock desliga da rádio. */
    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        if (world.isRemote) return;
        int meta = world.getBlockMetadata(x, y, z);
        if (supported(world, x, y, z, meta)) return;
        dropBlockAsItem(world, x, y, z, meta, 0);
        world.setBlockToAir(x, y, z);
    }

    /** A face do bloco de apoio (em cima no teto, atrás na parede) é sólida. Nunca carrega chunk. */
    static boolean supported(World world, int x, int y, int z, int meta) {
        int[] d = CeilingMount.supportOffset(meta);
        int sx = x + d[0], sy = y + d[1], sz = z + d[2];
        if (sy < 0 || sy > 255 || !world.blockExists(sx, sy, sz)) return false;
        return world.isSideSolid(sx, sy, sz, ForgeDirection.getOrientation(CeilingMount.supportSide(meta)));
    }

    @Override
    public int damageDropped(int meta) {
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        iconFront = reg.registerIcon(AkashicFM.MODID + ":ceiling_speaker_front");
        iconSide = reg.registerIcon(AkashicFM.MODID + ":ceiling_speaker_side");
        blockIcon = iconSide;
    }

    /** A grade na face exposta; no item (metadata 0), em cima, para aparecer no inventário. */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        int front = meta >= 2 && meta <= 9 ? CeilingMount.frontSide(meta) : 1;
        return side == front ? iconFront : iconSide;
    }
}
