package com.akashiic.fm.common;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Configuração do rádio portátil, guardada no NBT do próprio item (sob {@link #KEY}). Só o servidor escreve, sempre
 * saneando; o cliente só lê (tela e dica do item). O que ele está tocando de fato (URL sintonizada, transporte,
 * quem ouve) é calculado pelo servidor a cada ciclo e não vai para o item.
 */
public final class PortableState {

    public static final String KEY = "portable";

    public TuneMode mode = TuneMode.URL;
    public String url = "";
    public int frequency = Frequency.DEFAULT;
    public int volume = RadioLimits.VOLUME_DEFAULT;
    public boolean on;
    /** Muda a cada ligar e a cada troca de fonte pedida pelo jogador (os clientes recomeçam a reprodução). */
    public int session;
    /** Identidade do item (0 = ainda sem): confere que a tela fala do mesmo portátil que está no slot. */
    public long id;

    public void sanitize() {
        if (mode == null) mode = TuneMode.URL;
        url = TextSanitizer.cleanUrl(url == null ? "" : url, RadioLimits.MAX_URL_LENGTH);
        frequency = Frequency.clamp(frequency);
        volume = RadioLimits.clamp(volume, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
        // Sem URL não há o que tocar no modo URL; sintonizado fica ligado "sem sinal".
        if (mode == TuneMode.URL && url.isEmpty()) on = false;
    }

    /** Lê do NBT do item (null ou sem a chave = portátil novo). */
    public static PortableState fromItemTag(NBTTagCompound root) {
        PortableState s = new PortableState();
        if (root != null && root.hasKey(KEY, 10)) s.readFromNbt(root.getCompoundTag(KEY));
        else s.sanitize();
        return s;
    }

    public void readFromNbt(NBTTagCompound tag) {
        mode = TuneMode.tunable(tag.getByte("mode"));
        url = RadioState.str(tag, "url");
        frequency = tag.hasKey("frequency", 2) ? tag.getShort("frequency") : Frequency.DEFAULT;
        volume = tag.hasKey("volume") ? tag.getByte("volume") : RadioLimits.VOLUME_DEFAULT;
        on = tag.getBoolean("on");
        session = tag.getInteger("session");
        id = tag.getLong("id");
        sanitize();
    }

    public void writeToNbt(NBTTagCompound tag) {
        tag.setByte("mode", (byte) mode.ordinal());
        tag.setString("url", url);
        tag.setShort("frequency", (short) frequency);
        tag.setByte("volume", (byte) volume);
        tag.setBoolean("on", on);
        tag.setInteger("session", session);
        tag.setLong("id", id);
    }

    /** Grava no NBT do item, criando a raiz se preciso; devolve a raiz. */
    public NBTTagCompound writeToItemTag(NBTTagCompound root) {
        NBTTagCompound r = root == null ? new NBTTagCompound() : root;
        NBTTagCompound tag = new NBTTagCompound();
        writeToNbt(tag);
        r.setTag(KEY, tag);
        return r;
    }
}
