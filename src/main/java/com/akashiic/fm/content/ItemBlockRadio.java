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
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.Permissions;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.server.RadioIndex;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Item da rádio: aplica os limites de rádios por jogador e por chunk e restaura as configurações guardadas. */
public class ItemBlockRadio extends ItemBlock {

    public ItemBlockRadio(Block block) {
        super(block);
        setMaxStackSize(16);
    }

    @Override
    public boolean placeBlockAt(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ, int metadata) {
        boolean realPlayer = player != null && !(player instanceof FakePlayer);
        if (!world.isRemote && !withinLimits(player, realPlayer, world, x, z)) return false;
        if (!super.placeBlockAt(stack, player, world, x, y, z, side, hitX, hitY, hitZ, metadata)) return false;
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileRadio) {
            TileRadio radio = (TileRadio) te;
            if (stack.hasTagCompound() && stack.getTagCompound()
                .hasKey(BlockRadio.SETTINGS_KEY, 10)) {
                radio.state.readSettings(
                    stack.getTagCompound()
                        .getCompoundTag(BlockRadio.SETTINGS_KEY),
                    FmConfig.Limits.maxRange,
                    FmConfig.Limits.maxSpeakersPerRadio);
            }
            if (realPlayer) {
                radio.state.owner = player.getUniqueID();
                radio.state.ownerName = player.getCommandSenderName();
            }
            // Começa já sabendo se há sinal, para não tocar/alternar sozinha logo ao ser colocada.
            radio.state.lastPowered = world.isBlockIndirectlyGettingPowered(x, y, z);
            radio.state.playing = false;
            RadioIndex.get(world)
                .put(radio.dimension(), radio.pos(), radio.state.owner);
            radio.markStateChanged();
        }
        return true;
    }

    private static boolean withinLimits(EntityPlayer player, boolean realPlayer, World world, int x, int z) {
        Chunk chunk = world.getChunkFromBlockCoords(x, z);
        int inChunk = 0;
        for (Object o : chunk.chunkTileEntityMap.values()) {
            if (o instanceof TileRadio) inChunk++;
        }
        if (inChunk >= FmConfig.Limits.maxRadiosPerChunk) {
            if (player != null) player.addChatMessage(
                new ChatComponentTranslation("akashicfm.limit.chunk", FmConfig.Limits.maxRadiosPerChunk));
            return false;
        }
        if (realPlayer && !(FmConfig.Protection.opsBypass && Permissions.isOp(player))) {
            int owned = RadioIndex.get(world)
                .ownedBy(player.getUniqueID())
                .size();
            if (owned >= FmConfig.Limits.maxRadiosPerPlayer) {
                player.addChatMessage(
                    new ChatComponentTranslation("akashicfm.limit.player", FmConfig.Limits.maxRadiosPerPlayer));
                return false;
            }
        }
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        if (!stack.hasTagCompound() || !stack.getTagCompound()
            .hasKey(BlockRadio.SETTINGS_KEY, 10)) return;
        NBTTagCompound s = stack.getTagCompound()
            .getCompoundTag(BlockRadio.SETTINGS_KEY);
        String url = TextSanitizer.cleanUrl(s.hasKey("url", 8) ? s.getString("url") : "", RadioLimits.MAX_URL_LENGTH);
        if (!url.isEmpty()) {
            String shown = url.length() > 40 ? url.substring(0, 37) + "..." : url;
            lines.add(EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted("akashicfm.item.url", shown));
        }
        if (s.getByte("mode") == TuneMode.FREQUENCY.ordinal() && s.hasKey("frequency", 2)) {
            lines.add(
                EnumChatFormatting.GRAY + StatCollector
                    .translateToLocalFormatted("akashicfm.item.frequency", Frequency.format(s.getShort("frequency"))));
        }
        int stations = Math.min(
            s.getTagList("stations", 8)
                .tagCount(),
            RadioLimits.MAX_STATIONS);
        if (stations > 0) {
            lines.add(
                EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted("akashicfm.item.stations", stations));
        }
    }
}
