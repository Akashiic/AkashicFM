package com.akashiic.fm.content;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.akashiic.fm.common.IPodState;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Item do bloco do iPod: os mesmos limites da rádio (conta como uma rádio, por jogador e por chunk) e a fila guardada
 * quando o bloco foi quebrado.
 */
public class ItemBlockIPodPlayer extends ItemBlockRadio {

    public ItemBlockIPodPlayer(Block block) {
        super(block);
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        NBTTagCompound settings = stack.hasTagCompound() && stack.getTagCompound()
            .hasKey(BlockRadio.SETTINGS_KEY, 10) ? stack.getTagCompound()
                .getCompoundTag(BlockRadio.SETTINGS_KEY) : null;
        int tracks = 0;
        if (settings != null && settings.hasKey(IPodState.KEY, 10)) {
            tracks = Math.min(
                settings.getCompoundTag(IPodState.KEY)
                    .getTagList("q", 10)
                    .tagCount(),
                IPodState.HARD_MAX_QUEUE);
        }
        lines.add(EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted("akashicfm.ipod.queue", tracks));
        lines.add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("akashicfm.ipod_player.help"));
    }
}
