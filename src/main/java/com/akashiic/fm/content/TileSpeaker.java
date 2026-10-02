package com.akashiic.fm.content;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.SpeakerChannel;
import com.akashiic.fm.common.TextSanitizer;

/** Caixa de som. Guarda a rádio à qual está ligada (uma só) e o canal que reproduz. */
public class TileSpeaker extends TileEntity {

    /** Rádio ligada, ou null. Só o servidor muda. */
    public Pos linkedRadio;
    public SpeakerChannel channel = SpeakerChannel.MIX;
    public UUID owner;
    public String ownerName = "";

    public Pos pos() {
        return new Pos(xCoord, yCoord, zCoord);
    }

    @Override
    public boolean canUpdate() {
        return false;
    }

    @Override
    public boolean shouldRefresh(Block oldBlock, Block newBlock, int oldMeta, int newMeta, World world, int x, int y,
        int z) {
        return oldBlock != newBlock;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writeShared(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readShared(tag);
    }

    private void writeShared(NBTTagCompound tag) {
        if (linkedRadio != null) tag.setTag("linkedRadio", linkedRadio.toNbt());
        tag.setByte("channel", (byte) channel.ordinal());
        if (owner != null) {
            tag.setLong("ownerMost", owner.getMostSignificantBits());
            tag.setLong("ownerLeast", owner.getLeastSignificantBits());
        }
        tag.setString("ownerName", ownerName);
    }

    private void readShared(NBTTagCompound tag) {
        linkedRadio = tag.hasKey("linkedRadio") ? Pos.fromNbt(tag.getCompoundTag("linkedRadio")) : null;
        channel = SpeakerChannel.byOrdinal(tag.getByte("channel"));
        owner = tag.hasKey("ownerMost") ? new UUID(tag.getLong("ownerMost"), tag.getLong("ownerLeast")) : null;
        ownerName = TextSanitizer.clean(tag.getString("ownerName"), 16);
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeShared(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound tag = pkt.func_148857_g();
        if (tag != null) readShared(tag);
    }

    public boolean isOwner(UUID player) {
        return owner != null && owner.equals(player);
    }

    public void markChanged() {
        if (worldObj == null || worldObj.isRemote) return;
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }
}
