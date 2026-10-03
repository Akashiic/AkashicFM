package com.akashiic.fm.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * O iPod guardado no NBT do próprio item (sob {@link #KEY}): a fila, a faixa atual, pausa, volume e repetição. Só o
 * servidor escreve, sempre saneando; o cliente só lê (tela e dica do item). O que está tocando de fato (resolução,
 * estação, posição) fica no servidor.
 * <p>
 * Tamanho: o 1.7.10 manda o NBT de um item comprimido com o tamanho num {@code short} (até 32767 bytes). A fila tem
 * um orçamento de {@link #MAX_TEXT_BYTES} de texto (UTF-8), além do limite de faixas: mesmo texto que não comprime
 * (títulos aleatórios) cabe no pacote.
 */
public final class IPodState {

    public static final String KEY = "ipod";
    /** Teto da fila, acima do config ({@code ipod.maxQueue} vai até aqui). */
    public static final int HARD_MAX_QUEUE = 200;
    /** Texto da fila inteira (links, títulos, artistas), em bytes UTF-8. */
    public static final int MAX_TEXT_BYTES = 24_000;

    /** Repetir: nada, a fila inteira ou a faixa atual. */
    public enum Repeat {

        OFF,
        ALL,
        ONE;

        public static Repeat byOrdinal(int i) {
            Repeat[] v = values();
            return i >= 0 && i < v.length ? v[i] : OFF;
        }

        public Repeat next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Identidade do item (0 = ainda sem): confere que a tela fala do mesmo iPod que está no slot. */
    public long id;
    /** Tocando (ou pausado). Desligado, nada toca e a fila fica. */
    public boolean on;
    public boolean paused;
    /** Faixa atual (-1 = nenhuma). */
    public int index = -1;
    /** Muda a cada troca de faixa (pedida ou automática): o servidor recomeça a resolução. */
    public int session;
    public int volume = RadioLimits.VOLUME_DEFAULT;
    public Repeat repeat = Repeat.OFF;
    public final List<IPodTrack> queue = new ArrayList<>();

    public void sanitize() {
        if (repeat == null) repeat = Repeat.OFF;
        volume = RadioLimits.clamp(volume, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
        int bytes = 0;
        List<IPodTrack> kept = new ArrayList<>(Math.min(queue.size(), HARD_MAX_QUEUE));
        for (IPodTrack t : queue) {
            if (t == null || !t.valid()) continue;
            int b = textBytes(t);
            if (kept.size() >= HARD_MAX_QUEUE || bytes + b > MAX_TEXT_BYTES) break;
            kept.add(t);
            bytes += b;
        }
        queue.clear();
        queue.addAll(kept);
        if (queue.isEmpty()) {
            index = -1;
            on = false;
        } else if (index < 0 || index >= queue.size()) {
            index = 0;
        }
        if (!on) paused = false;
    }

    /** A faixa atual, ou null. */
    public IPodTrack current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    /**
     * Acrescenta ao fim, até {@code maxQueue} faixas e o orçamento de texto. Devolve quantas entraram (as que não
     * couberam ficam de fora).
     */
    public int append(List<IPodTrack> tracks, int maxQueue) {
        int limit = Math.max(1, Math.min(HARD_MAX_QUEUE, maxQueue));
        int bytes = textBytes();
        int added = 0;
        for (IPodTrack t : tracks) {
            if (t == null || !t.valid()) continue;
            int b = textBytes(t);
            if (queue.size() >= limit || bytes + b > MAX_TEXT_BYTES) break;
            queue.add(t);
            bytes += b;
            added++;
        }
        return added;
    }

    /** Tira a faixa {@code i}; a atual continua a mesma (o índice acompanha). Devolve true se tirou a atual. */
    public boolean remove(int i) {
        if (i < 0 || i >= queue.size()) return false;
        queue.remove(i);
        boolean wasCurrent = i == index;
        if (i < index) index--;
        if (queue.isEmpty()) {
            index = -1;
            on = false;
            paused = false;
        } else if (index >= queue.size()) {
            index = queue.size() - 1;
        }
        return wasCurrent;
    }

    public void clear() {
        queue.clear();
        index = -1;
        on = false;
        paused = false;
    }

    /** Embaralha as faixas depois da atual (a atual e as já tocadas ficam onde estão). */
    public void shuffleAfterCurrent(Random rng) {
        int from = Math.max(0, index + 1);
        if (queue.size() - from < 2) return;
        List<IPodTrack> rest = new ArrayList<>(queue.subList(from, queue.size()));
        Collections.shuffle(rest, rng);
        for (int i = 0; i < rest.size(); i++) queue.set(from + i, rest.get(i));
    }

    /**
     * A próxima faixa depois da atual, pela repetição: a mesma (uma), a seguinte, a primeira de novo (todas) ou -1 (fim
     * da fila). {@code manual}: o jogador pediu "próxima" (repetir uma não prende o botão).
     */
    public int nextIndex(boolean manual) {
        if (queue.isEmpty()) return -1;
        if (repeat == Repeat.ONE && !manual && index >= 0) return index;
        int next = index + 1;
        if (next < queue.size()) return next;
        return repeat == Repeat.OFF ? -1 : 0;
    }

    /** A anterior (no começo: a última com repetir todas, senão a primeira). */
    public int previousIndex() {
        if (queue.isEmpty()) return -1;
        if (index > 0) return index - 1;
        return repeat == Repeat.ALL ? queue.size() - 1 : 0;
    }

    /** Vai para a faixa {@code i} (ligado, sem pausa, sessão nova). */
    public void play(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        on = true;
        paused = false;
        session++;
    }

    public int textBytes() {
        int b = 0;
        for (IPodTrack t : queue) b += textBytes(t);
        return b;
    }

    static int textBytes(IPodTrack t) {
        return utf8(t.link) + utf8(t.title) + utf8(t.artist) + 8;
    }

    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---- NBT ----

    public static IPodState fromItemTag(NBTTagCompound root) {
        IPodState s = new IPodState();
        if (root != null && root.hasKey(KEY, 10)) s.readFromNbt(root.getCompoundTag(KEY));
        else s.sanitize();
        return s;
    }

    /** Só a identidade, sem ler a fila (o {@code onUpdate} do item roda a cada tick). */
    public static long idOf(NBTTagCompound root) {
        return root != null && root.hasKey(KEY, 10) ? root.getCompoundTag(KEY)
            .getLong("id") : 0;
    }

    /** Tocando e sem pausa, sem ler a fila (o brilho do item é desenhado a cada quadro). */
    public static boolean playingOf(NBTTagCompound root) {
        if (root == null || !root.hasKey(KEY, 10)) return false;
        NBTTagCompound tag = root.getCompoundTag(KEY);
        return tag.getBoolean("on") && !tag.getBoolean("paused");
    }

    public void readFromNbt(NBTTagCompound tag) {
        id = tag.getLong("id");
        on = tag.getBoolean("on");
        paused = tag.getBoolean("paused");
        index = tag.hasKey("index", 99) ? tag.getInteger("index") : -1;
        session = tag.getInteger("session");
        volume = tag.hasKey("volume", 99) ? tag.getByte("volume") : RadioLimits.VOLUME_DEFAULT;
        repeat = Repeat.byOrdinal(tag.getByte("repeat"));
        queue.clear();
        NBTTagList list = tag.getTagList("q", 10);
        for (int i = 0; i < list.tagCount() && i < HARD_MAX_QUEUE; i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            IPodTrack.Source src = IPodTrack.Source.byOrdinal(e.getByte("s"));
            if (src == null) continue;
            queue.add(
                new IPodTrack(
                    src,
                    RadioState.str(e, "l"),
                    RadioState.str(e, "t"),
                    RadioState.str(e, "a"),
                    e.getInteger("d")));
        }
        sanitize();
    }

    public void writeToNbt(NBTTagCompound tag) {
        tag.setLong("id", id);
        tag.setBoolean("on", on);
        tag.setBoolean("paused", paused);
        tag.setInteger("index", index);
        tag.setInteger("session", session);
        tag.setByte("volume", (byte) volume);
        tag.setByte("repeat", (byte) repeat.ordinal());
        NBTTagList list = new NBTTagList();
        for (IPodTrack t : queue) {
            NBTTagCompound e = new NBTTagCompound();
            e.setByte("s", (byte) t.source.ordinal());
            e.setString("l", t.link);
            e.setString("t", t.title);
            e.setString("a", t.artist);
            e.setInteger("d", t.durationSec);
            list.appendTag(e);
        }
        tag.setTag("q", list);
    }

    public NBTTagCompound writeToItemTag(NBTTagCompound root) {
        NBTTagCompound r = root == null ? new NBTTagCompound() : root;
        NBTTagCompound tag = new NBTTagCompound();
        writeToNbt(tag);
        r.setTag(KEY, tag);
        return r;
    }
}
