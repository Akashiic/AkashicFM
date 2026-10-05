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
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.server.RadioActionHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class BlockRadio extends BlockContainer {

    public static final String SETTINGS_KEY = "akashicfm";

    @SideOnly(Side.CLIENT)
    private IIcon iconFront, iconSide, iconTop;

    public BlockRadio() {
        super(Material.wood);
        setHardness(2.0F);
        setResistance(10.0F);
        setStepSound(soundTypeWood);
        setBlockName(AkashicFM.MODID + ".radio");
        setBlockTextureName(AkashicFM.MODID + ":radio_side");
        setCreativeTab(FmContent.TAB);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileRadio();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        iconFront = reg.registerIcon(AkashicFM.MODID + ":radio_front");
        iconSide = reg.registerIcon(AkashicFM.MODID + ":radio_side");
        iconTop = reg.registerIcon(AkashicFM.MODID + ":radio_top");
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
        // Com o tuner na mão, quem trata o clique é o tuner no servidor (onItemUseFirst). Devolver true aqui
        // faz o cliente balançar o braço e mandar o pacote, sem abrir a GUI.
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemTuner) return true;
        if (world.isRemote) openGui(world, x, y, z);
        return true;
    }

    /** Cliente: a tela do bloco. */
    protected void openGui(World world, int x, int y, int z) {
        AkashicFM.proxy.openRadioGui(world, x, y, z);
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        if (world.isRemote) return;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileRadio) {
            RadioActionHandler.onRedstone((TileRadio) te, world.isBlockIndirectlyGettingPowered(x, y, z));
        }
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        if (!world.isRemote) {
            TileEntity te = world.getTileEntity(x, y, z);
            if (te instanceof TileRadio) {
                TileRadio radio = (TileRadio) te;
                for (Pos p : radio.state.speakers.toArray(new Pos[0])) {
                    RadioActionHandler.unlinkSpeaker(world, radio, p);
                }
            }
        }
        super.breakBlock(world, x, y, z, block, meta);
    }

    /**
     * Adia a remoção quando vai haver drop: assim o TE ainda existe em {@link #getDrops} e as configurações
     * vão para o item. No criativo não há drop (willHarvest=false) e o bloco sai na hora.
     */
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
        if (te instanceof TileRadio) {
            NBTTagCompound settings = new NBTTagCompound();
            ((TileRadio) te).writeItemSettings(settings);
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
