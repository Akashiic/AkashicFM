package com.akashiic.fm.content;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.Permissions;
import com.akashiic.fm.server.TransmitterIndex;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Item do transmissor: limite por jogador e configurações guardadas ao quebrar. */
public class ItemBlockTransmitter extends ItemBlock {

    public ItemBlockTransmitter(Block block) {
        super(block);
        setMaxStackSize(16);
    }

    @Override
    public boolean placeBlockAt(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ, int metadata) {
        boolean realPlayer = player != null && !(player instanceof FakePlayer);
        if (!world.isRemote && realPlayer && !(FmConfig.Protection.opsBypass && Permissions.isOp(player))) {
            int owned = TransmitterIndex.get(world)
                .ownedBy(player.getUniqueID())
                .size();
            if (owned >= FmConfig.Transmitter.maxPerPlayer) {
                player.addChatMessage(
                    new ChatComponentTranslation("akashicfm.limit.transmitters", FmConfig.Transmitter.maxPerPlayer));
                return false;
            }
        }
        if (!super.placeBlockAt(stack, player, world, x, y, z, side, hitX, hitY, hitZ, metadata)) return false;
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileTransmitter) {
            TileTransmitter t = (TileTransmitter) te;
            if (stack.hasTagCompound() && stack.getTagCompound()
                .hasKey(BlockTransmitter.SETTINGS_KEY, 10)) {
                t.state.readSettings(
                    stack.getTagCompound()
                        .getCompoundTag(BlockTransmitter.SETTINGS_KEY));
            }
            if (realPlayer) {
                t.state.owner = player.getUniqueID();
                t.state.ownerName = player.getCommandSenderName();
            }
            t.state.lastPowered = world.isBlockIndirectlyGettingPowered(x, y, z);
            t.state.broadcasting = false;
            t.markStateChanged();
        }
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        if (!stack.hasTagCompound() || !stack.getTagCompound()
            .hasKey(BlockTransmitter.SETTINGS_KEY, 10)) return;
        NBTTagCompound s = stack.getTagCompound()
            .getCompoundTag(BlockTransmitter.SETTINGS_KEY);
        if (s.hasKey("frequency", 2)) {
            lines.add(
                EnumChatFormatting.GRAY + StatCollector
                    .translateToLocalFormatted("akashicfm.item.frequency", Frequency.format(s.getShort("frequency"))));
        }
    }
}
