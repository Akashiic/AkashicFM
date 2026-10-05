package com.akashiic.fm.content;

import net.minecraft.nbt.NBTTagCompound;

import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.server.RadioActionHandler;

/**
 * O bloco do iPod: uma rádio (dono, acesso, alcance, volume, caixas, tela, redstone) que toca uma fila de músicas, a
 * mesma do iPod item, pela estação do relay com a chave do bloco ({@link #stationKey}). O modo da rádio fica preso em
 * {@link TuneMode#IPOD}; a fila ({@link #ipod}) vai no disco e no pacote de descrição, e quem resolve e toca é o
 * {@code IPodService}.
 * <p>
 * Ligado/desligado: o interruptor da rádio ({@code state.playing}) é o que todos veem, e {@code ipod.on} o acompanha.
 * Mudanças pelo iPod passam por {@link #commitIPod()}; paradas de rádio ({@code /fm stop}, {@code stopall}, bloqueio)
 * desligam a rádio, e o serviço desliga a fila no ciclo seguinte (desligado vence).
 */
public class TileIPodPlayer extends TileRadio {

    public final IPodState ipod = new IPodState();

    public TileIPodPlayer() {
        super(true);
    }

    /** A chave da estação do relay: pela posição (dois blocos nunca dividem, nem cópias com a mesma identidade). */
    public String stationKey() {
        return stationKey(dimension(), xCoord, yCoord, zCoord);
    }

    public static String stationKey(int dim, int x, int y, int z) {
        return "ipod:b" + dim + "_" + x + "_" + y + "_" + z;
    }

    /**
     * Servidor: a fila mudou (ação, avanço, falha). A rádio acompanha o iPod: tocando, a chave da estação e o relay;
     * parado, nada; e o estado novo vai para quem vê o bloco.
     */
    public void commitIPod() {
        ipod.sanitize();
        boolean on = ipod.on;
        if (on && !state.playing) state.session++;
        state.playing = on;
        state.transport = on ? Transport.RELAY : Transport.NONE;
        state.tunedUrl = on ? stationKey() : "";
        if (!on) {
            state.nowPlaying = "";
            state.status = "";
        }
        markStateChanged();
    }

    /**
     * Servidor: uma parada de rádio (applyStop) desligou o bloco: a fila também desliga. Devolve true se mudou algo
     * (quem chama grava).
     */
    public boolean reconcile() {
        if (!state.playing && ipod.on) {
            ipod.on = false;
            ipod.paused = false;
            return true;
        }
        if (state.playing && !ipod.on) {
            RadioActionHandler.applyStop(state);
            return true;
        }
        return false;
    }

    @Override
    protected void writeExtra(NBTTagCompound tag, boolean forClient) {
        NBTTagCompound t = new NBTTagCompound();
        ipod.writeToNbt(t);
        tag.setTag(IPodState.KEY, t);
    }

    @Override
    protected void readExtra(NBTTagCompound tag, boolean fromServer) {
        IPodState read = new IPodState();
        if (tag.hasKey(IPodState.KEY, 10)) read.readFromNbt(tag.getCompoundTag(IPodState.KEY));
        else read.sanitize();
        copy(read, ipod);
        // Do disco: o interruptor da rádio manda (uma parada gravada sem o serviço ter visto ainda).
        if (!fromServer && !state.playing) {
            ipod.on = false;
            ipod.paused = false;
        }
    }

    /** A fila viaja com o item, desligada. */
    @Override
    public void writeItemSettings(NBTTagCompound settings) {
        super.writeItemSettings(settings);
        IPodState s = new IPodState();
        copy(ipod, s);
        s.on = false;
        s.paused = false;
        NBTTagCompound t = new NBTTagCompound();
        s.writeToNbt(t);
        settings.setTag(IPodState.KEY, t);
    }

    @Override
    public void readItemSettings(NBTTagCompound settings) {
        super.readItemSettings(settings);
        if (!settings.hasKey(IPodState.KEY, 10)) return;
        IPodState read = new IPodState();
        read.readFromNbt(settings.getCompoundTag(IPodState.KEY));
        read.on = false;
        read.paused = false;
        copy(read, ipod);
    }

    private static void copy(IPodState from, IPodState to) {
        to.id = from.id;
        to.on = from.on;
        to.paused = from.paused;
        to.index = from.index;
        to.session = from.session;
        to.volume = from.volume;
        to.repeat = from.repeat;
        to.queue.clear();
        to.queue.addAll(from.queue);
    }
}
