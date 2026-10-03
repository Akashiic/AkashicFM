package com.akashiic.fm.compat.oc;

import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.server.RadioScripting;

import li.cil.oc.api.Network;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;

/**
 * O componente {@code akashicfm_transmitter}. A lógica e a segurança estão em {@link RadioScripting}. Pública: o OC
 * chama os callbacks de uma classe gerada no pacote dele.
 */
public final class TransmitterEnvironment extends li.cil.oc.api.prefab.ManagedEnvironment implements NamedBlock {

    static final String NAME = "akashicfm_transmitter";
    private static final Object[] GONE = { false, "the transmitter was removed" };
    private final TileTransmitter transmitter;

    TransmitterEnvironment(TileTransmitter transmitter) {
        this.transmitter = transmitter;
        setNode(
            Network.newNode(this, Visibility.Network)
                .withComponent(NAME, Visibility.Network)
                .create());
    }

    /**
     * O Adaptador junta os drivers do bloco num componente só, e o nome dele sai daqui (sem isto, o OC usaria o nome
     * do bloco, e os scripts do OpenFM não achariam o componente; achado pelo E2E).
     */
    @Override
    public String preferredName() {
        return NAME;
    }

    @Override
    public int priority() {
        return 10;
    }

    private boolean gone() {
        return transmitter.isInvalid();
    }

    @Callback(doc = "function():boolean[, string] -- Goes on air.")
    public Object[] start(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.start(transmitter);
    }

    @Callback(doc = "function():boolean -- Goes off air.")
    public Object[] stop(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.stop(transmitter);
    }

    @Callback(doc = "function():boolean, boolean -- Switched on, and actually on air (URL and energy).")
    public Object[] isBroadcasting(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.isBroadcasting(transmitter);
    }

    @Callback(doc = "function(url:string):boolean[, string] -- Sets the stream URL (checked by the server policy).")
    public Object[] setURL(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setURL(transmitter, args.checkAny(0), RadioEnvironment.actor(context));
    }

    @Callback(doc = "function():string -- The stream URL.")
    public Object[] getURL(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getURL(transmitter);
    }

    @Callback(doc = "function(mhz:number):number -- Frequency, 87.5 to 108.0.")
    public Object[] setFrequency(Context context, Arguments args) {
        return gone() ? GONE
            : RadioScripting.setFrequency(transmitter, args.checkDouble(0), RadioEnvironment.actor(context));
    }

    @Callback(doc = "function():number -- Frequency in MHz.")
    public Object[] getFrequency(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getFrequency(transmitter);
    }

    @Callback(doc = "function(name:string):boolean -- Station name.")
    public Object[] setName(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setName(transmitter, args.checkString(0));
    }

    @Callback(doc = "function():string -- Station name.")
    public Object[] getName(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getName(transmitter);
    }

    @Callback(doc = "function():number, number -- Range in blocks and antennas that count.")
    public Object[] getRange(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getRange(transmitter);
    }

    @Callback(doc = "function():number, number|boolean -- Stored EU and capacity, or false if energy is not required.")
    public Object[] getEnergy(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getEnergy(transmitter);
    }
}
