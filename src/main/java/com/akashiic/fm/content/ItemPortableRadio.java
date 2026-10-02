package com.akashiic.fm.content;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.PortableState;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.server.PortableActionHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Rádio portátil: toca de qualquer slot do inventário. Botão direito abre a tela; agachado, liga e desliga. A
 * configuração fica no NBT do item ({@link PortableState}), escrita só pelo servidor. Quem ouve (o portador, e os
 * jogadores perto quando ele não usa fone) é decidido pelo servidor em {@code PortableSources}.
 */
public class ItemPortableRadio extends Item {

    public ItemPortableRadio() {
        setMaxStackSize(1);
        setUnlocalizedName(AkashicFM.MODID + ".portable_radio");
        setTextureName(AkashicFM.MODID + ":portable_radio");
        setCreativeTab(FmContent.TAB);
    }

    public static PortableState state(ItemStack stack) {
        return PortableState.fromItemTag(stack.getTagCompound());
    }

    /** Servidor: grava a configuração no item (o inventário sincroniza com o cliente sozinho). */
    public static void save(ItemStack stack, PortableState s) {
        stack.setTagCompound(s.writeToItemTag(stack.getTagCompound()));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister reg) {
        itemIcon = reg.registerIcon(AkashicFM.MODID + ":portable_radio");
    }

    /**
     * Servidor: dá identidade ao portátil assim que ele entra num inventário (criado, /give, criativo), para a tela
     * já abrir sabendo qual item é (as ações conferem a identidade).
     */
    @Override
    public void onUpdate(ItemStack stack, World world, Entity entity, int slot, boolean held) {
        if (world.isRemote || !(entity instanceof EntityPlayerMP)) return;
        PortableState s = state(stack);
        if (s.id != 0) return;
        s.id = PortableActionHandler.newId();
        save(stack, s);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            if (!world.isRemote && player instanceof EntityPlayerMP) {
                PortableActionHandler.toggle((EntityPlayerMP) player, player.inventory.currentItem);
            }
        } else if (world.isRemote) {
            AkashicFM.proxy.openPortableGui(player.inventory.currentItem);
        }
        return stack;
    }

    /** Brilha enquanto está ligado (dá para ver no inventário qual está tocando). */
    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasEffect(ItemStack stack, int pass) {
        return state(stack).on;
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        PortableState s = state(stack);
        lines.add(
            (s.on ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + StatCollector.translateToLocal(s.on ? "akashicfm.portable.on" : "akashicfm.portable.off"));
        if (s.mode == TuneMode.FREQUENCY) {
            lines.add(
                EnumChatFormatting.GRAY + StatCollector
                    .translateToLocalFormatted("akashicfm.item.frequency", Frequency.format(s.frequency)));
        } else if (!s.url.isEmpty()) {
            String shown = s.url.length() > 40 ? s.url.substring(0, 37) + "..." : s.url;
            lines.add(EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted("akashicfm.item.url", shown));
        }
        lines.add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("akashicfm.portable.help"));
    }
}
