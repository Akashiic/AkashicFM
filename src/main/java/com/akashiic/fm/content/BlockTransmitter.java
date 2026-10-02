package com.akashiic.fm.content;

import java.util.ArrayList;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.server.TransmitterActionHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Transmissor de FM. As antenas vão em cima dele, empilhadas. */
public class BlockTransmitter extends BlockContainer {

    public static final String SETTINGS_KEY = "akashicfm";

    @SideOnly(Side.CLIENT)
    private IIcon iconFront, iconSide, iconTop;

    public BlockTransmitter() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(15.0F);
        setStepSound(soundTypeMetal);
        setBlockName(AkashicFM.MODID + ".transmitter");
        setBlockTextureName(AkashicFM.MODID + ":transmitter_side");
        setCreativeTab(FmContent.TAB);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileTransmitter();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        iconFront = reg.registerIcon(AkashicFM.MODID + ":transmitter_front");
        iconSide = reg.registerIcon(AkashicFM.MODID + ":transmitter_side");
        iconTop = reg.registerIcon(AkashicFM.MODID + ":transmitter_top");
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
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        world.setBlockMetadataWithNotify(x, y, z, Facing.fromPlacer(placer), 2);
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hx, float hy,
        float hz) {
        // Com uma antena na mão o clique coloca a antena (sem precisar agachar), em vez de abrir a tela.
        ItemStack held = player.getHeldItem();
        if (held != null && Block.getBlockFromItem(held.getItem()) == FmContent.antenna) return false;
        if (world.isRemote) AkashicFM.proxy.openTransmitterGui(world, x, y, z);
        return true;
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        if (world.isRemote) return;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileTransmitter) {
            TransmitterActionHandler.onRedstone((TileTransmitter) te, world.isBlockIndirectlyGettingPowered(x, y, z));
        }
    }

    /** Adia a remoção quando vai haver drop, para as configurações irem para o item (como a rádio). */
    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean willHarvest) {
        if (willHarvest) return true;
        return super.removedByPlayer(world, player, x, y, z, willHarvest);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int meta) {
        super.harvestBlock(world, player, x, y, z, meta);
        world.setBlockToAir(x, y, z);
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<>();
        ItemStack stack = new ItemStack(this, 1, 0);
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileTransmitter) {
            NBTTagCompound settings = new NBTTagCompound();
            ((TileTransmitter) te).state.writeSettings(settings);
            NBTTagCompound root = new NBTTagCompound();
            root.setTag(SETTINGS_KEY, settings);
            stack.setTagCompound(root);
        }
        drops.add(stack);
        return drops;
    }

    @Override
    public int damageDropped(int meta) {
        return 0;
    }
}
