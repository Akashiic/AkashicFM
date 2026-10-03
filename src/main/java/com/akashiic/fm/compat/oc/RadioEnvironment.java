package com.akashiic.fm.compat.oc;

import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.server.RadioScripting;

import li.cil.oc.api.Network;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;

/**
 * O componente {@code openfm_radio}: os métodos do OpenFM 1.7.10 (mesmos nomes, mesma escala de volume) e alguns
 * novos. Callbacks não diretos: o OC os roda na thread do servidor, um por tick. Toda a lógica e a segurança estão
 * em {@link RadioScripting}; aqui só os argumentos.
 * <p>
 * Pública, como os métodos: o OC gera no pacote dele uma classe que chama cada callback direto (sem ser pública,
 * {@code IllegalAccessError} em toda chamada, achado pelo E2E).
 */
public final class RadioEnvironment extends li.cil.oc.api.prefab.ManagedEnvironment implements NamedBlock {

    static final String NAME = "openfm_radio";
    private static final Object[] GONE = { false, "the radio was removed" };
    private final TileRadio radio;

    RadioEnvironment(TileRadio radio) {
        this.radio = radio;
        setNode(
            Network.newNode(this, Visibility.Network)
                .withComponent(NAME, Visibility.Network)
                .create());
    }

    static String actor(Context context) {
        return "oc:" + (context == null || context.node() == null ? "?"
            : context.node()
                .address());
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
        return radio.isInvalid();
    }

    @Callback(doc = "function():boolean -- Starts playing (the Play button).")
    public Object[] start(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.start(radio);
    }

    @Callback(doc = "function():boolean -- Same as start().")
    public Object[] play(Context context, Arguments args) {
        return start(context, args);
    }

    @Callback(doc = "function():boolean -- Stops playing.")
    public Object[] stop(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.stop(radio);
    }

    @Callback(doc = "function():boolean -- Whether the radio is playing.")
    public Object[] isPlaying(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.isPlaying(radio);
    }

    @Callback(doc = "function(url:string):boolean[, string] -- Sets the stream URL (checked by the server policy).")
    public Object[] setURL(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setURL(radio, args.checkAny(0), actor(context));
    }

    @Callback(doc = "function():string -- The stream URL.")
    public Object[] getURL(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getURL(radio);
    }

    @Callback(doc = "function(volume:number):number|boolean -- Volume from 0 to 10 (OpenFM scale).")
    public Object[] setVol(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setVol(radio, args.checkDouble(0));
    }

    @Callback(doc = "function():number -- Volume from 0 to 1 (OpenFM scale).")
    public Object[] getVol(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getVol(radio);
    }

    @Callback(doc = "function():number|boolean -- Volume up one step (0.1).")
    public Object[] volUp(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.volUp(radio);
    }

    @Callback(doc = "function():number|boolean -- Volume down one step (0.1).")
    public Object[] volDown(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.volDown(radio);
    }

    @Callback(doc = "function(color:number):boolean -- Screen color, 0xRRGGBB.")
    public Object[] setScreenColor(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setScreenColor(radio, args.checkInteger(0));
    }

    @Callback(doc = "function():number -- Screen color, 0xRRGGBB.")
    public Object[] getScreenColor(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getScreenColor(radio);
    }

    @Callback(doc = "function(text:string):boolean -- Screen text.")
    public Object[] setScreenText(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setScreenText(radio, args.checkString(0));
    }

    @Callback(doc = "function():string -- Screen text.")
    public Object[] getScreenText(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getScreenText(radio);
    }

    @Callback(doc = "function():number -- Number of linked speakers.")
    public Object[] getAttachedSpeakerCount(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.speakerCount(radio);
    }

    @Callback(doc = "function():number -- Number of linked speakers (same as getAttachedSpeakerCount).")
    public Object[] getAttachedSpeakers(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.speakerCount(radio);
    }

    @Callback(doc = "function(listen:boolean):boolean -- Play while powered by redstone.")
    public Object[] setListenRedstone(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setListenRedstone(radio, args.checkBoolean(0));
    }

    @Callback(doc = "function():boolean -- Whether the radio follows redstone.")
    public Object[] getListenRedstone(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getListenRedstone(radio);
    }

    @Callback(doc = "function():string -- A greeting.")
    public Object[] greet(Context context, Arguments args) {
        return RadioScripting.greet();
    }

    @Callback(doc = "function(mode:string):string -- \"url\" or \"fm\".")
    public Object[] setMode(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setMode(radio, args.checkString(0));
    }

    @Callback(doc = "function():string -- \"url\" or \"fm\".")
    public Object[] getMode(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getMode(radio);
    }

    @Callback(doc = "function(mhz:number):number -- FM frequency, 87.5 to 108.0.")
    public Object[] setFrequency(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setFrequency(radio, args.checkDouble(0));
    }

    @Callback(doc = "function():number -- FM frequency in MHz.")
    public Object[] getFrequency(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getFrequency(radio);
    }

    @Callback(doc = "function():string -- Title playing now (stream metadata).")
    public Object[] getNowPlaying(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getNowPlaying(radio);
    }

    @Callback(doc = "function():number, string -- FM signal (0-100) and the tuned station name.")
    public Object[] getSignal(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getSignal(radio);
    }

    @Callback(doc = "function(on:boolean):boolean -- Plays the favorites in order.")
    public Object[] setPlaylist(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.setPlaylist(radio, args.checkBoolean(0));
    }

    @Callback(doc = "function():boolean -- Whether the playlist is on.")
    public Object[] getPlaylist(Context context, Arguments args) {
        return gone() ? GONE : RadioScripting.getPlaylist(radio);
    }
}
