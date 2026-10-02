package com.akashiic.fm.common;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Estado de um transmissor: o que ele transmite (URL), em que frequência, com que nome, e quem manda nele. No
 * servidor é a fonte da verdade; no cliente é a cópia do pacote do bloco. Toda leitura de NBT passa por
 * {@link #sanitize}. O alcance, as antenas e a energia são calculados pelo servidor e só viajam para o cliente.
 */
public final class TransmitterState {

    public String url = "";
    public int frequency = Frequency.DEFAULT;
    public boolean broadcasting;
    /** Nome que os ouvintes veem (como o nome de uma estação de verdade). */
    public String name = "";
    public RadioAccess access = RadioAccess.PRIVATE;
    public UUID owner;
    public String ownerName = "";
    public RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    public boolean lastPowered;
    public int epoch;

    // Transitórios: calculados pelo servidor, sincronizados com o cliente, nunca gravados.
    public int antennas;
    public int range;
    /** Tem energia para transmitir (sempre true quando a energia não é exigida). */
    public boolean powered = true;
    public boolean energyRequired;
    public int energy;
    public int energyCapacity;
    /** Título da faixa atual da estação (relay). */
    public String nowPlaying = "";

    public void sanitize() {
        url = TextSanitizer.cleanUrl(url, RadioLimits.MAX_URL_LENGTH);
        frequency = Frequency.clamp(frequency);
        name = TextSanitizer.clean(name, RadioLimits.MAX_SCREEN_TEXT);
        if (access == null) access = RadioAccess.PRIVATE;
        ownerName = TextSanitizer.clean(ownerName, RadioLimits.MAX_OWNER_NAME);
        if (redstoneMode == null) redstoneMode = RedstoneMode.IGNORE;
        if (url.isEmpty()) broadcasting = false;
        antennas = RadioLimits.clamp(antennas, 0, 64);
        range = RadioLimits.clamp(range, 0, 100_000);
        energy = Math.max(0, energy);
        energyCapacity = Math.max(0, energyCapacity);
        nowPlaying = TextSanitizer.clean(nowPlaying, RadioLimits.MAX_TITLE_LENGTH);
    }

    public boolean isOwner(UUID player) {
        return owner != null && owner.equals(player);
    }

    /** Transmite de fato: ligado, com URL e com energia. */
    public boolean active() {
        return broadcasting && !url.isEmpty() && powered;
    }

    public void writeToNbt(NBTTagCompound tag, boolean forClient) {
        tag.setString("url", url);
        tag.setShort("frequency", (short) frequency);
        tag.setBoolean("broadcasting", broadcasting);
        tag.setString("name", name);
        tag.setByte("access", (byte) access.ordinal());
        if (owner != null) {
            tag.setLong("ownerMost", owner.getMostSignificantBits());
            tag.setLong("ownerLeast", owner.getLeastSignificantBits());
        }
        tag.setString("ownerName", ownerName);
        tag.setByte("redstoneMode", (byte) redstoneMode.ordinal());
        tag.setBoolean("lastPowered", lastPowered);
        tag.setInteger("epoch", epoch);
        if (forClient) {
            tag.setByte("antennas", (byte) antennas);
            tag.setInteger("range", range);
            tag.setBoolean("powered", powered);
            tag.setBoolean("energyRequired", energyRequired);
            tag.setInteger("energy", energy);
            tag.setInteger("energyCapacity", energyCapacity);
            tag.setString("nowPlaying", nowPlaying);
        }
    }

    public void readFromNbt(NBTTagCompound tag) {
        url = RadioState.str(tag, "url");
        frequency = tag.hasKey("frequency", 2) ? tag.getShort("frequency") : Frequency.DEFAULT;
        broadcasting = tag.getBoolean("broadcasting");
        name = RadioState.str(tag, "name");
        access = RadioAccess.byOrdinal(tag.getByte("access"));
        owner = tag.hasKey("ownerMost") ? new UUID(tag.getLong("ownerMost"), tag.getLong("ownerLeast")) : null;
        ownerName = RadioState.str(tag, "ownerName");
        redstoneMode = RedstoneMode.byOrdinal(tag.getByte("redstoneMode"));
        lastPowered = tag.getBoolean("lastPowered");
        epoch = tag.getInteger("epoch");
        antennas = tag.getByte("antennas");
        range = tag.getInteger("range");
        powered = !tag.hasKey("powered") || tag.getBoolean("powered");
        energyRequired = tag.getBoolean("energyRequired");
        energy = tag.getInteger("energy");
        energyCapacity = tag.getInteger("energyCapacity");
        nowPlaying = RadioState.str(tag, "nowPlaying");
        sanitize();
    }

    /** O que viaja com o item quando o transmissor é quebrado (sem dono nem estado). */
    public void writeSettings(NBTTagCompound tag) {
        tag.setString("url", url);
        tag.setShort("frequency", (short) frequency);
        tag.setString("name", name);
        tag.setByte("access", (byte) access.ordinal());
        tag.setByte("redstoneMode", (byte) redstoneMode.ordinal());
    }

    public void readSettings(NBTTagCompound tag) {
        url = RadioState.str(tag, "url");
        if (tag.hasKey("frequency", 2)) frequency = tag.getShort("frequency");
        name = RadioState.str(tag, "name");
        access = RadioAccess.byOrdinal(tag.getByte("access"));
        redstoneMode = RedstoneMode.byOrdinal(tag.getByte("redstoneMode"));
        sanitize();
    }
}
