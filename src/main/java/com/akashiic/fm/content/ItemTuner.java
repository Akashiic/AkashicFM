package com.akashiic.fm.content;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.server.SpeakerLinks;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liga caixas de som a rádios. Clique numa caixa para selecionar, depois numa rádio para ligar.
 * Agachado numa caixa: troca o canal (MIX, L, R, estéreo). Toda a lógica roda no servidor: no cliente
 * {@link #onItemUseFirst} devolve false para o clique virar pacote (devolver true lá cancelaria o envio).
 */
public class ItemTuner extends Item {

    private static final String LINK_KEY = "akashicfm_link";

    public ItemTuner() {
        setMaxStackSize(1);
        setUnlocalizedName(AkashicFM.MODID + ".tuner");
        setTextureName(AkashicFM.MODID + ":tuner");
        setCreativeTab(FmContent.TAB);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister reg) {
        itemIcon = reg.registerIcon(AkashicFM.MODID + ":tuner");
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (world.isRemote) return false;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileSpeaker) {
            onSpeaker(stack, player, world, (TileSpeaker) te);
            return true;
        }
        if (te instanceof TileRadio) {
            onRadio(stack, player, world, (TileRadio) te);
            return true;
        }
        return false;
    }

    private void onSpeaker(ItemStack stack, EntityPlayer player, World world, TileSpeaker speaker) {
        if (player.isSneaking()) {
            if (!SpeakerLinks.canAdminSpeaker(speaker, player)) {
                chat(player, "akashicfm.tuner.speaker_not_yours");
                return;
            }
            speaker.channel = speaker.channel.next();
            speaker.markChanged();
            chat(
                player,
                "akashicfm.tuner.channel",
                "akashicfm.channel." + speaker.channel.name()
                    .toLowerCase());
            return;
        }
        NBTTagCompound tag = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        NBTTagCompound link = speaker.pos()
            .toNbt();
        link.setInteger("dim", world.provider.dimensionId);
        tag.setTag(LINK_KEY, link);
        stack.setTagCompound(tag);
        chat(
            player,
            "akashicfm.tuner.selected",
            speaker.pos()
                .toString());
    }

    private void onRadio(ItemStack stack, EntityPlayer player, World world, TileRadio radio) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(LINK_KEY, 10)) {
            chat(player, "akashicfm.tuner.nothing_selected");
            return;
        }
        NBTTagCompound link = tag.getCompoundTag(LINK_KEY);
        if (link.getInteger("dim") != world.provider.dimensionId) {
            chat(player, "akashicfm.tuner.other_dimension");
            return;
        }
        SpeakerLinks.Result r = SpeakerLinks.link(player, world, Pos.fromNbt(link), radio);
        if (r.ok) {
            tag.removeTag(LINK_KEY);
            if (tag.hasNoTags()) stack.setTagCompound(null);
        }
        chat(player, r.key, r.arg);
    }

    private static void chat(EntityPlayer player, String key, Object... args) {
        Object[] translated = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            translated[i] = a instanceof String && ((String) a).startsWith("akashicfm.")
                ? new ChatComponentTranslation((String) a)
                : a;
        }
        player.addChatMessage(new ChatComponentTranslation(key, translated));
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && tag.hasKey(LINK_KEY, 10)) {
            Pos p = Pos.fromNbt(tag.getCompoundTag(LINK_KEY));
            lines.add(
                EnumChatFormatting.AQUA
                    + StatCollector.translateToLocalFormatted("akashicfm.tuner.tooltip_selected", p.toString()));
        }
        lines.add(EnumChatFormatting.GRAY + StatCollector.translateToLocal("akashicfm.tuner.tooltip_help1"));
        lines.add(EnumChatFormatting.GRAY + StatCollector.translateToLocal("akashicfm.tuner.tooltip_help2"));
    }
}
