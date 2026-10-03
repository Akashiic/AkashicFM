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
import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.server.PortableActionHandler;
import com.akashiic.fm.server.PortableSources;
import com.akashiic.fm.server.ipod.IPodActionHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * iPod: toca uma fila de músicas (links do SoundCloud, do YouTube e do Spotify, ou buscas) de qualquer slot do
 * inventário, como o rádio portátil. Botão direito abre a tela; agachado, toca/pausa. A fila fica no NBT do item
 * ({@link IPodState}), escrita só pelo servidor. Quem ouve é decidido pelo servidor ({@code PortableSources}), com o
 * mesmo fone do portátil.
 */
public class ItemIPod extends Item {

    public ItemIPod() {
        setMaxStackSize(1);
        setUnlocalizedName(AkashicFM.MODID + ".ipod");
        setTextureName(AkashicFM.MODID + ":ipod");
        setCreativeTab(FmContent.TAB);
    }

    public static IPodState state(ItemStack stack) {
        return IPodState.fromItemTag(stack.getTagCompound());
    }

    /** Servidor: grava no item (o inventário sincroniza com o cliente sozinho). */
    public static void save(ItemStack stack, IPodState s) {
        stack.setTagCompound(s.writeToItemTag(stack.getTagCompound()));
    }

    /** O iPod do slot (barra e mochila), ou null. */
    public static ItemStack at(EntityPlayer player, int slot) {
        if (player == null || player.inventory == null || slot < 0 || slot >= PortableActionHandler.SLOTS) return null;
        ItemStack st = player.inventory.mainInventory[slot];
        return st != null && st.getItem() instanceof ItemIPod ? st : null;
    }

    /** O iPod com esta identidade no inventário do jogador, ou null. */
    public static ItemStack findById(EntityPlayer player, long id) {
        if (id == 0) return null;
        for (int slot = 0; slot < PortableActionHandler.SLOTS; slot++) {
            ItemStack st = at(player, slot);
            if (st != null && state(st).id == id) return st;
        }
        return null;
    }

    /** O iPod que toca agora: o primeiro aparelho ligado do inventário, se for um iPod. */
    public static ItemStack activeStack(EntityPlayerMP player) {
        ItemStack st = PortableSources.firstOnStack(player);
        return st != null && st.getItem() instanceof ItemIPod ? st : null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister reg) {
        itemIcon = reg.registerIcon(AkashicFM.MODID + ":ipod");
    }

    /** Servidor: dá identidade ao iPod assim que ele entra num inventário (a tela já abre sabendo qual é). */
    @Override
    public void onUpdate(ItemStack stack, World world, Entity entity, int slot, boolean held) {
        if (world.isRemote || !(entity instanceof EntityPlayerMP)) return;
        if (IPodState.idOf(stack.getTagCompound()) != 0) return; // a cada tick: sem ler a fila inteira
        IPodState s = state(stack);
        s.id = PortableActionHandler.newId();
        save(stack, s);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            if (!world.isRemote && player instanceof EntityPlayerMP) {
                IPodActionHandler.toggle((EntityPlayerMP) player, player.inventory.currentItem);
            }
        } else if (world.isRemote) {
            AkashicFM.proxy.openIPodGui(player.inventory.currentItem);
        }
        return stack;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasEffect(ItemStack stack, int pass) {
        return IPodState.playingOf(stack.getTagCompound());
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        IPodState s = state(stack);
        String state = !s.on ? "akashicfm.ipod.off" : s.paused ? "akashicfm.ipod.paused" : "akashicfm.ipod.on";
        lines.add(
            (s.on && !s.paused ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + StatCollector.translateToLocal(state));
        IPodTrack t = s.current();
        if (t != null) {
            String shown = t.display();
            if (shown.length() > 40) shown = shown.substring(0, 37) + "...";
            lines.add(EnumChatFormatting.GRAY + "♪ " + shown);
        }
        lines.add(
            EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted("akashicfm.ipod.queue", s.queue.size()));
        lines.add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("akashicfm.ipod.help"));
    }
}
