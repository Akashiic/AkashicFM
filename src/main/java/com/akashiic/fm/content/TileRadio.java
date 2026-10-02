package com.akashiic.fm.content;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.server.RadioIndex;
import com.akashiic.fm.server.ServerRadioRegistry;

/**
 * Tile entity da rádio. Não tem tick: o servidor processa ações na fila do tick global e o cliente é
 * dirigido pelo controlador de áudio. O estado chega ao cliente pelo pacote de descrição (só para quem
 * está vendo o chunk), e o cliente nunca muda o estado sozinho.
 */
public class TileRadio extends TileEntity {

    private static final String NBT_KEY = "radio";

    public final RadioState state = new RadioState();

    public Pos pos() {
        return new Pos(xCoord, yCoord, zCoord);
    }

    public int dimension() {
        return worldObj != null && worldObj.provider != null ? worldObj.provider.dimensionId : 0;
    }

    @Override
    public boolean canUpdate() {
        return false;
    }

    /**
     * O padrão do Forge para TEs de mod devolve true até quando só o metadata muda, o que destruiria o TE
     * (e o dono, a URL e as caixas) ao girar o bloco. Só recria se o bloco em si mudar.
     */
    @Override
    public boolean shouldRefresh(Block oldBlock, Block newBlock, int oldMeta, int newMeta, World world, int x, int y,
        int z) {
        return oldBlock != newBlock;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        NBTTagCompound radio = new NBTTagCompound();
        state.writeToNbt(radio, false);
        tag.setTag(NBT_KEY, radio);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        // hasKey com tipo: o getCompoundTag do 1.7.10 derruba o jogo se a tag existir com outro tipo.
        NBTTagCompound radio = tag.hasKey(NBT_KEY, 10) ? tag.getCompoundTag(NBT_KEY) : new NBTTagCompound();
        state.readFromNbt(radio, FmConfig.Limits.maxRange, FmConfig.Limits.maxSpeakersPerRadio);
        state.status = "";
        state.nowPlaying = "";
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        state.writeToNbt(tag, true);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound tag = pkt.func_148857_g();
        if (tag == null) return;
        RadioState incoming = new RadioState();
        // No cliente o limite é o rígido: o servidor já aplicou o config dele.
        incoming.readFromNbt(tag, RadioLimits.RANGE_HARD_MAX, RadioLimits.MAX_SPEAKERS_HARD);
        // TCP entrega em ordem, mas um TE recriado pode receber um estado mais velho guardado em cache.
        if (incoming.epoch < state.epoch && incoming.session <= state.session) return;
        NBTTagCompound copy = new NBTTagCompound();
        incoming.writeToNbt(copy, true);
        state.readFromNbt(copy, RadioLimits.RANGE_HARD_MAX, RadioLimits.MAX_SPEAKERS_HARD);
        AkashicFM.proxy.onClientRadioUpdated(this);
    }

    @Override
    public void validate() {
        super.validate();
        if (worldObj == null) return;
        if (worldObj.isRemote) {
            AkashicFM.proxy.onClientRadioLoaded(this);
        } else {
            ServerRadioRegistry.add(this);
            RadioIndex.get(worldObj)
                .put(dimension(), pos(), state.owner);
        }
    }

    /** Chamado quando o bloco é removido (não quando o chunk descarrega). */
    @Override
    public void invalidate() {
        super.invalidate();
        if (worldObj == null) return;
        if (worldObj.isRemote) {
            AkashicFM.proxy.onClientRadioUnloaded(this);
        } else {
            ServerRadioRegistry.remove(this);
            RadioIndex.get(worldObj)
                .remove(dimension(), pos());
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (worldObj == null) return;
        if (worldObj.isRemote) {
            AkashicFM.proxy.onClientRadioUnloaded(this);
        } else {
            ServerRadioRegistry.remove(this);
        }
    }

    /** Servidor: registra uma alteração do estado, salva e manda o estado novo para quem vê o chunk. */
    public void markStateChanged() {
        if (worldObj == null || worldObj.isRemote) return;
        state.epoch++;
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }
}
