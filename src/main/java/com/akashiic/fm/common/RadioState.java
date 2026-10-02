package com.akashiic.fm.common;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.akashiic.fm.audio.http.UrlPolicy;

/**
 * Estado de uma rádio. No servidor é a fonte da verdade; no cliente é uma cópia recebida pelo pacote de
 * descrição do tile entity. Toda leitura de NBT passa por {@link #sanitize}, seja do disco ou da rede.
 */
public final class RadioState {

    public String url = "";
    public final List<String> stations = new ArrayList<>();
    public boolean playing;
    public int volume = RadioLimits.VOLUME_DEFAULT;
    public int range = RadioLimits.RANGE_DEFAULT;
    public RadioAccess access = RadioAccess.PRIVATE;
    /** Dono. null = sem dono (colocada por máquina): qualquer jogador controla, só ops administram. */
    public UUID owner;
    public String ownerName = "";
    public String screenText = "";
    public int screenColor = RadioLimits.SCREEN_COLOR_DEFAULT;
    public RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    public boolean lastPowered;
    public final List<Pos> speakers = new ArrayList<>();
    /** Transporte escolhido pelo servidor quando a rádio começou a tocar. */
    public Transport transport = Transport.NONE;
    /** Muda sempre que a fonte de áudio muda (play, troca de URL). Clientes reiniciam a reprodução quando muda. */
    public int session;
    /** Muda a cada alteração. Clientes ignoram estados com epoch menor que o já aplicado. */
    public int epoch;

    // Transitórios: sincronizados com o cliente, nunca gravados no disco.
    /** Mensagem do servidor para a GUI (ex.: erro do relay). */
    public String status = "";
    /** Título da faixa atual (ICY StreamTitle), preenchido pelo relay. */
    public String nowPlaying = "";

    /**
     * Corta e normaliza todos os campos. {@code maxRange} e {@code maxSpeakers} vêm do config (ou do limite rígido).
     */
    public void sanitize(int maxRange, int maxSpeakers) {
        int rangeCap = RadioLimits.clamp(maxRange, RadioLimits.RANGE_MIN, RadioLimits.RANGE_HARD_MAX);
        int speakerCap = RadioLimits.clamp(maxSpeakers, 0, RadioLimits.MAX_SPEAKERS_HARD);
        url = TextSanitizer.cleanUrl(url, RadioLimits.MAX_URL_LENGTH);
        List<String> cleanStations = new ArrayList<>();
        for (String s : stations) {
            String c = TextSanitizer.cleanUrl(s, RadioLimits.MAX_URL_LENGTH);
            if (!c.isEmpty() && !cleanStations.contains(c) && cleanStations.size() < RadioLimits.MAX_STATIONS) {
                cleanStations.add(c);
            }
        }
        stations.clear();
        stations.addAll(cleanStations);
        volume = RadioLimits.clamp(volume, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
        range = RadioLimits.clamp(range, RadioLimits.RANGE_MIN, rangeCap);
        if (access == null) access = RadioAccess.PRIVATE;
        ownerName = TextSanitizer.clean(ownerName, RadioLimits.MAX_OWNER_NAME);
        screenText = TextSanitizer.clean(screenText, RadioLimits.MAX_SCREEN_TEXT);
        screenColor &= 0xFFFFFF;
        if (redstoneMode == null) redstoneMode = RedstoneMode.IGNORE;
        List<Pos> cleanSpeakers = new ArrayList<>();
        for (Pos p : speakers) {
            if (p != null && p.y >= 0 && p.y < 256 && !cleanSpeakers.contains(p) && cleanSpeakers.size() < speakerCap) {
                cleanSpeakers.add(p);
            }
        }
        speakers.clear();
        speakers.addAll(cleanSpeakers);
        if (transport == null) transport = Transport.NONE;
        if (url.isEmpty()) playing = false;
        status = TextSanitizer.clean(status, RadioLimits.MAX_STATUS_LENGTH);
        nowPlaying = TextSanitizer.clean(nowPlaying, RadioLimits.MAX_TITLE_LENGTH);
    }

    public boolean isOwner(UUID player) {
        return owner != null && owner.equals(player);
    }

    /** Grava o estado. {@code forClient} inclui os campos transitórios (pacote de descrição). */
    public void writeToNbt(NBTTagCompound tag, boolean forClient) {
        tag.setString("url", url);
        tag.setTag("stations", writeStrings(stations));
        tag.setBoolean("playing", playing);
        tag.setByte("volume", (byte) volume);
        tag.setShort("range", (short) range);
        tag.setByte("access", (byte) access.ordinal());
        if (owner != null) {
            tag.setLong("ownerMost", owner.getMostSignificantBits());
            tag.setLong("ownerLeast", owner.getLeastSignificantBits());
        }
        tag.setString("ownerName", ownerName);
        tag.setString("screenText", screenText);
        tag.setInteger("screenColor", screenColor);
        tag.setByte("redstoneMode", (byte) redstoneMode.ordinal());
        tag.setBoolean("lastPowered", lastPowered);
        NBTTagList spk = new NBTTagList();
        for (Pos p : speakers) spk.appendTag(p.toNbt());
        tag.setTag("speakers", spk);
        tag.setByte("transport", (byte) transport.ordinal());
        tag.setInteger("session", session);
        tag.setInteger("epoch", epoch);
        if (forClient) {
            tag.setString("status", status);
            tag.setString("nowPlaying", nowPlaying);
        }
    }

    /** Lê e saneia. Campos ausentes voltam ao padrão. */
    public void readFromNbt(NBTTagCompound tag, int maxRange, int maxSpeakers) {
        url = str(tag, "url");
        stations.clear();
        readStrings(tag.getTagList("stations", 8), stations, RadioLimits.MAX_STATIONS * 2);
        playing = tag.getBoolean("playing");
        volume = tag.hasKey("volume") ? tag.getByte("volume") : RadioLimits.VOLUME_DEFAULT;
        range = tag.hasKey("range") ? tag.getShort("range") : RadioLimits.RANGE_DEFAULT;
        access = RadioAccess.byOrdinal(tag.getByte("access"));
        owner = tag.hasKey("ownerMost") ? new UUID(tag.getLong("ownerMost"), tag.getLong("ownerLeast")) : null;
        ownerName = str(tag, "ownerName");
        screenText = str(tag, "screenText");
        screenColor = tag.hasKey("screenColor") ? tag.getInteger("screenColor") : RadioLimits.SCREEN_COLOR_DEFAULT;
        redstoneMode = RedstoneMode.byOrdinal(tag.getByte("redstoneMode"));
        lastPowered = tag.getBoolean("lastPowered");
        speakers.clear();
        NBTTagList spk = tag.getTagList("speakers", 10);
        int n = Math.min(spk.tagCount(), RadioLimits.MAX_SPEAKERS_HARD * 2);
        for (int i = 0; i < n; i++) speakers.add(Pos.fromNbt(spk.getCompoundTagAt(i)));
        transport = Transport.byOrdinal(tag.getByte("transport"));
        session = tag.getInteger("session");
        epoch = tag.getInteger("epoch");
        status = str(tag, "status");
        nowPlaying = str(tag, "nowPlaying");
        sanitize(maxRange, maxSpeakers);
    }

    /** Configurações que viajam com o item quando a rádio é quebrada (sem dono, caixas nem estado de reprodução). */
    public void writeSettings(NBTTagCompound tag) {
        tag.setString("url", url);
        tag.setTag("stations", writeStrings(stations));
        tag.setByte("volume", (byte) volume);
        tag.setShort("range", (short) range);
        tag.setString("screenText", screenText);
        tag.setInteger("screenColor", screenColor);
        tag.setByte("redstoneMode", (byte) redstoneMode.ordinal());
        tag.setByte("access", (byte) access.ordinal());
    }

    public void readSettings(NBTTagCompound tag, int maxRange, int maxSpeakers) {
        url = str(tag, "url");
        stations.clear();
        readStrings(tag.getTagList("stations", 8), stations, RadioLimits.MAX_STATIONS * 2);
        if (tag.hasKey("volume")) volume = tag.getByte("volume");
        if (tag.hasKey("range")) range = tag.getShort("range");
        screenText = str(tag, "screenText");
        if (tag.hasKey("screenColor")) screenColor = tag.getInteger("screenColor");
        redstoneMode = RedstoneMode.byOrdinal(tag.getByte("redstoneMode"));
        access = RadioAccess.byOrdinal(tag.getByte("access"));
        sanitize(maxRange, maxSpeakers);
    }

    /** true se a URL passa pelas regras sintáticas da política (sem DNS: seguro na thread principal). */
    public static boolean isPlausibleUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > RadioLimits.MAX_URL_LENGTH) return false;
        try {
            new UrlPolicy(new String[0], true).check(url);
            return true;
        } catch (UrlPolicy.PolicyException e) {
            return false;
        }
    }

    /** Só aceita tag de string: o getString do 1.7.10 devolveria o toString() de uma tag de outro tipo. */
    static String str(NBTTagCompound tag, String key) {
        return tag.hasKey(key, 8) ? tag.getString(key) : "";
    }

    private static NBTTagList writeStrings(List<String> values) {
        NBTTagList list = new NBTTagList();
        for (String s : values) list.appendTag(new NBTTagString(s));
        return list;
    }

    private static void readStrings(NBTTagList list, List<String> out, int max) {
        int n = Math.min(list.tagCount(), max);
        for (int i = 0; i < n; i++) out.add(list.getStringTagAt(i));
    }
}
