package com.akashiic.fm.content;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Item do alto-falante de teto/parede: a dica de onde ele vai e de como ligar. */
public class ItemBlockCeilingSpeaker extends ItemBlock {

    public ItemBlockCeilingSpeaker(Block block) {
        super(block);
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        lines.add(EnumChatFormatting.GRAY + StatCollector.translateToLocal("akashicfm.ceiling_speaker.help"));
        lines.add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("akashicfm.ceiling_speaker.help2"));
    }
}
