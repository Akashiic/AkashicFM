package com.akashiic.fm.content;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

import com.akashiic.fm.common.EnergyBuffer;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.server.TransmitterIndex;
import com.akashiic.fm.server.relay.RelayService;

import cofh.api.energy.IEnergyReceiver;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModAPIManager;
import cpw.mods.fml.common.Optional;
import ic2.api.energy.event.EnergyTileLoadEvent;
import ic2.api.energy.event.EnergyTileUnloadEvent;
import ic2.api.energy.tile.IEnergySink;

/**
 * Transmissor de FM. Transmite a URL dele numa frequência, com alcance que cresce com as antenas empilhadas em
 * cima. Com IC2 (EU, cabos do GregTech) ou um mod de RF instalado, e o config exigindo, precisa de energia para
 * transmitir. As interfaces de energia são opcionais: sem o mod, o FML as remove da classe.
 * <p>
 * O servidor mantém a entrada do transmissor no {@link TransmitterIndex}, que é onde as rádios sintonizadas o
 * procuram (sem carregar chunk).
 */
@Optional.InterfaceList({ @Optional.Interface(iface = "ic2.api.energy.tile.IEnergySink", modid = "IC2"),
    @Optional.Interface(iface = "cofh.api.energy.IEnergyReceiver", modid = "CoFHAPI|energy") })
public class TileTransmitter extends TileEntity implements IEnergySink, IEnergyReceiver {

    private static final String NBT_KEY = "transmitter";
    private static final int ANTENNA_INTERVAL_TICKS = 20;
    private static final int ENERGY_SYNC_INTERVAL_TICKS = 40;
    /** Sem energia, só volta a transmitir com este tanto de consumo guardado (2 s). */
    private static final int RESTART_RESERVE_TICKS = 40;
    /** Tier que o IC2 vê: alto o bastante para nenhum cabo explodir o transmissor (o limite é por tick). */
    private static final int SINK_TIER = 14;

    private static Boolean ic2Loaded, energyApiPresent;

    public final TransmitterState state = new TransmitterState();
    private final EnergyBuffer buffer = new EnergyBuffer(
        Math.max(100, FmConfig.Transmitter.energyCapacity),
        Math.max(1, FmConfig.Transmitter.maxInputPerTick));
    private boolean ic2Registered;
    private int ticks;
    private int syncedEnergy = -1;

    public Pos pos() {
        return new Pos(xCoord, yCoord, zCoord);
    }

    public int dimension() {
        return worldObj != null && worldObj.provider != null ? worldObj.provider.dimensionId : 0;
    }

    static boolean ic2Loaded() {
        if (ic2Loaded == null) ic2Loaded = Loader.isModLoaded("IC2");
        return ic2Loaded;
    }

    /** Há algum jeito de entregar energia (IC2 ou API de RF). Sem isso, a exigência do config não se aplica. */
    public static boolean energyApiPresent() {
        if (energyApiPresent == null) {
            energyApiPresent = ic2Loaded() || ModAPIManager.INSTANCE.hasAPI("CoFHAPI|energy");
        }
        return energyApiPresent;
    }

    public static boolean energyRequired() {
        return FmConfig.Transmitter.requireEnergy && energyApiPresent();
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    @Override
    public boolean shouldRefresh(Block oldBlock, Block newBlock, int oldMeta, int newMeta, World world, int x, int y,
        int z) {
        return oldBlock != newBlock;
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;
        buffer.startTick();
        if (!ic2Registered && ic2Loaded()) registerIc2();
        ticks++;
        boolean changed = false;
        if (ticks % ANTENNA_INTERVAL_TICKS == 1) changed = refreshAntennas() | refreshTitle();
        changed |= updateEnergy();
        if (changed) markStateChanged();
    }

    /** Conta as antenas empilhadas logo acima e recalcula o alcance. */
    private boolean refreshAntennas() {
        int max = RadioLimits.clamp(FmConfig.Transmitter.maxAntennas, 0, 64);
        int count = 0;
        for (int y = yCoord + 1; y <= 255 && count < max; y++) {
            if (worldObj.getBlock(xCoord, y, zCoord) != FmContent.antenna) break;
            count++;
        }
        int range = (int) Math.min(
            Math.max(8, FmConfig.Transmitter.maxRange),
            (long) Math.max(8, FmConfig.Transmitter.baseRange)
                + (long) count * Math.max(0, FmConfig.Transmitter.rangePerAntenna));
        if (count == state.antennas && range == state.range) return false;
        state.antennas = count;
        state.range = range;
        return true;
    }

    /** Título atual da estação (se alguma rádio a está ouvindo pelo relay). */
    private boolean refreshTitle() {
        String title = TextSanitizer.clean(RelayService.titleFor(state.url), RadioLimits.MAX_TITLE_LENGTH);
        if (title.equals(state.nowPlaying)) return false;
        state.nowPlaying = title;
        return true;
    }

    /** Consome energia transmitindo; devolve true quando algo visível mudou (tem/não tem energia, nível na GUI). */
    private boolean updateEnergy() {
        boolean required = energyRequired();
        boolean powered = !required
            || buffer.tickPower(state.powered, state.broadcasting && !state.url.isEmpty(), perTick(), reserve());
        boolean changed = powered != state.powered || required != state.energyRequired;
        state.powered = powered;
        state.energyRequired = required;
        state.energy = (int) buffer.stored();
        state.energyCapacity = (int) buffer.capacity();
        if (required && ticks % ENERGY_SYNC_INTERVAL_TICKS == 0
            && Math.abs(state.energy - syncedEnergy) > buffer.capacity() * 0.02) {
            changed = true;
        }
        if (changed) syncedEnergy = state.energy;
        return changed;
    }

    private static int perTick() {
        return Math.max(1, FmConfig.Transmitter.euPerTick);
    }

    private static double reserve() {
        return (double) perTick() * RESTART_RESERVE_TICKS;
    }

    /** Já pode transmitir (tem energia agora, ou a reserva para voltar). */
    public boolean hasEnergyReserve() {
        return state.powered || buffer.hasReserve(reserve());
    }

    /** Servidor: salva, manda o estado novo para quem vê o chunk e atualiza o índice das rádios. */
    public void markStateChanged() {
        if (worldObj == null || worldObj.isRemote) return;
        state.epoch++;
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        syncIndex();
    }

    private void syncIndex() {
        TransmitterIndex.get(worldObj)
            .put(
                new TransmitterIndex.Entry(
                    dimension(),
                    pos(),
                    state.frequency,
                    state.range,
                    state.url,
                    state.name,
                    state.owner,
                    state.active() && state.range > 0,
                    energyRequired()));
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        NBTTagCompound t = new NBTTagCompound();
        state.writeToNbt(t, false);
        t.setDouble("energy", buffer.stored());
        tag.setTag(NBT_KEY, t);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        NBTTagCompound t = tag.hasKey(NBT_KEY, 10) ? tag.getCompoundTag(NBT_KEY) : new NBTTagCompound();
        state.readFromNbt(t);
        buffer.setStored(t.getDouble("energy"));
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
        TransmitterState incoming = new TransmitterState();
        incoming.readFromNbt(tag);
        if (incoming.epoch < state.epoch) return;
        NBTTagCompound copy = new NBTTagCompound();
        incoming.writeToNbt(copy, true);
        state.readFromNbt(copy);
    }

    // Sem sync no validate: o alcance só existe depois da primeira contagem das antenas (primeiro tick, que já
    // atualiza o índice). Gravar antes deixaria o transmissor inativo no índice por um tick a cada chunk carregado.

    /** Bloco removido: sai do índice e da rede de energia. */
    @Override
    public void invalidate() {
        super.invalidate();
        if (worldObj == null || worldObj.isRemote) return;
        TransmitterIndex.get(worldObj)
            .remove(dimension(), pos());
        if (ic2Registered) unregisterIc2();
    }

    /** Chunk descarregado: sai da rede de energia (a entrada do índice fica). */
    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (worldObj != null && !worldObj.isRemote && ic2Registered) unregisterIc2();
    }

    // ---- IC2 (EU) ----

    @Optional.Method(modid = "IC2")
    private void registerIc2() {
        MinecraftForge.EVENT_BUS.post(new EnergyTileLoadEvent(this));
        ic2Registered = true;
    }

    @Optional.Method(modid = "IC2")
    private void unregisterIc2() {
        MinecraftForge.EVENT_BUS.post(new EnergyTileUnloadEvent(this));
        ic2Registered = false;
    }

    @Override
    @Optional.Method(modid = "IC2")
    public boolean acceptsEnergyFrom(TileEntity emitter, ForgeDirection direction) {
        return energyRequired();
    }

    @Override
    @Optional.Method(modid = "IC2")
    public double getDemandedEnergy() {
        return energyRequired() ? buffer.demanded() : 0;
    }

    @Override
    @Optional.Method(modid = "IC2")
    public int getSinkTier() {
        return SINK_TIER;
    }

    @Override
    @Optional.Method(modid = "IC2")
    public double injectEnergy(ForgeDirection direction, double amount, double voltage) {
        return energyRequired() ? buffer.offerEu(amount) : amount;
    }

    // ---- RF (CoFH) ----

    @Override
    @Optional.Method(modid = "CoFHAPI|energy")
    public boolean canConnectEnergy(ForgeDirection from) {
        return energyRequired();
    }

    @Override
    @Optional.Method(modid = "CoFHAPI|energy")
    public int receiveEnergy(ForgeDirection from, int maxReceive, boolean simulate) {
        return energyRequired() ? buffer.offerRf(maxReceive, Math.max(1, FmConfig.Transmitter.rfPerEu), simulate) : 0;
    }

    @Override
    @Optional.Method(modid = "CoFHAPI|energy")
    public int getEnergyStored(ForgeDirection from) {
        return buffer.storedRf(Math.max(1, FmConfig.Transmitter.rfPerEu));
    }

    @Override
    @Optional.Method(modid = "CoFHAPI|energy")
    public int getMaxEnergyStored(ForgeDirection from) {
        return buffer.capacityRf(Math.max(1, FmConfig.Transmitter.rfPerEu));
    }
}
