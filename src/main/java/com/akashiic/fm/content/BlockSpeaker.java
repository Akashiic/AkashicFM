package com.akashiic.fm.content;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.server.SpeakerLinks;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class BlockSpeaker extends BlockContainer {

    @SideOnly(Side.CLIENT)
    private IIcon iconFront, iconSide;

    public BlockSpeaker() {
        super(Material.wood);
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(soundTypeWood);
        setBlockName(AkashicFM.MODID + ".speaker");
        setBlockTextureName(AkashicFM.MODID + ":speaker_side");
        setCreativeTab(FmContent.TAB);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileSpeaker();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        iconFront = reg.registerIcon(AkashicFM.MODID + ":speaker_front");
        iconSide = reg.registerIcon(AkashicFM.MODID + ":speaker_side");
        blockIcon = iconSide;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return side == Facing.sanitize(meta) ? iconFront : iconSide;
    }

    /** O metadata depois de colocado: a frente virada para quem colocou. */
    protected int placedMeta(World world, int x, int y, int z, EntityLivingBase placer) {
        return Facing.fromPlacer(placer);
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        world.setBlockMetadataWithNotify(x, y, z, placedMeta(world, x, y, z, placer), 2);
        if (world.isRemote || !(placer instanceof EntityPlayer)) return;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileSpeaker && !(placer instanceof net.minecraftforge.common.util.FakePlayer)) {
            TileSpeaker speaker = (TileSpeaker) te;
            speaker.owner = placer.getUniqueID();
            speaker.ownerName = ((EntityPlayer) placer).getCommandSenderName();
            speaker.markChanged();
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hx, float hy,
        float hz) {
        ItemStack held = player.getHeldItem();
        // Só reage ao tuner (tratado no servidor pelo próprio item); sem tuner, deixa o clique passar.
        return held != null && held.getItem() instanceof ItemTuner;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        if (!world.isRemote) {
            TileEntity te = world.getTileEntity(x, y, z);
            if (te instanceof TileSpeaker) SpeakerLinks.onSpeakerRemoved(world, (TileSpeaker) te);
        }
        super.breakBlock(world, x, y, z, block, meta);
    }
}
