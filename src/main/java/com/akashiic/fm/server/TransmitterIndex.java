package com.akashiic.fm.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TextSanitizer;

/**
 * Índice persistente dos transmissores do mundo (carregados ou não), no armazenamento global (compartilhado entre
 * dimensões). É por ele que uma rádio sintonizada acha o transmissor mais forte sem carregar chunk nenhum. O TE
 * atualiza a entrada quando muda (frequência, URL, alcance, nome, ativo) e a remove quando o bloco sai.
 */
public final class TransmitterIndex extends WorldSavedData {

    public static final String NAME = "akashicfm_transmitters";
    private static final int MAX_ENTRIES = 10_000;

    public static final class Entry {

        public final int dim;
        public final Pos pos;
        public final int frequency;
        public final int range;
        public final String url;
        public final String name;
        public final UUID owner;
        public final boolean active;
        /** Exigia energia quando foi gravado: só vale com o chunk carregado (parado no tempo não transmite). */
        public final boolean needsLoadedChunk;

        public Entry(int dim, Pos pos, int frequency, int range, String url, String name, UUID owner, boolean active,
            boolean needsLoadedChunk) {
            this.dim = dim;
            this.pos = pos;
            this.frequency = Frequency.clamp(frequency);
            this.range = Math.max(0, range);
            this.url = TextSanitizer.cleanUrl(url == null ? "" : url, RadioLimits.MAX_URL_LENGTH);
            this.name = TextSanitizer.clean(name == null ? "" : name, RadioLimits.MAX_SCREEN_TEXT);
            this.owner = owner;
            this.active = active;
            this.needsLoadedChunk = needsLoadedChunk;
        }

        boolean sameAs(Entry o) {
            return o != null && o.frequency == frequency
                && o.range == range
                && o.url.equals(url)
                && o.name.equals(name)
                && (o.owner == null ? owner == null : o.owner.equals(owner))
                && o.active == active
                && o.needsLoadedChunk == needsLoadedChunk;
        }
    }

    private static final class Key {

        final int dim;
        final Pos pos;

        Key(int dim, Pos pos) {
            this.dim = dim;
            this.pos = pos;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return dim == k.dim && pos.equals(k.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode() * 31 + dim;
        }
    }

    private final Map<Key, Entry> entries = new HashMap<>();

    public TransmitterIndex(String name) {
        super(name);
    }

    public static TransmitterIndex get(World world) {
        MapStorage storage = world.mapStorage;
        TransmitterIndex idx = (TransmitterIndex) storage.loadData(TransmitterIndex.class, NAME);
        if (idx == null) {
            idx = new TransmitterIndex(NAME);
            storage.setData(NAME, idx);
        }
        return idx;
    }

    /** Grava a entrada (só marca para salvar se mudou). */
    public void put(Entry e) {
        Key key = new Key(e.dim, e.pos);
        Entry old = entries.get(key);
        if (e.sameAs(old)) return;
        if (old == null && entries.size() >= MAX_ENTRIES) return;
        entries.put(key, e);
        markDirty();
    }

    public void remove(int dim, Pos pos) {
        if (entries.remove(new Key(dim, pos)) != null) markDirty();
    }

    public List<Entry> inDimension(int dim) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.dim == dim) out.add(e);
        return out;
    }

    public List<Entry> ownedBy(UUID owner) {
        List<Entry> out = new ArrayList<>();
        if (owner == null) return out;
        for (Entry e : entries.values()) if (owner.equals(e.owner)) out.add(e);
        return out;
    }

    public List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        entries.clear();
        NBTTagList list = tag.getTagList("transmitters", 10);
        int n = Math.min(list.tagCount(), MAX_ENTRIES);
        for (int i = 0; i < n; i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            Pos pos = new Pos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z"));
            if (pos.y < 0 || pos.y > 255) continue;
            int dim = c.getInteger("dim");
            UUID owner = c.hasKey("ownerMost") ? new UUID(c.getLong("ownerMost"), c.getLong("ownerLeast")) : null;
            Entry e = new Entry(
                dim,
                pos,
                c.getShort("frequency"),
                c.getInteger("range"),
                RadioState.str(c, "url"),
                RadioState.str(c, "name"),
                owner,
                c.getBoolean("active"),
                c.getBoolean("needsLoaded"));
            entries.put(new Key(dim, pos), e);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Entry e : entries.values()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("dim", e.dim);
            c.setInteger("x", e.pos.x);
            c.setInteger("y", e.pos.y);
            c.setInteger("z", e.pos.z);
            c.setShort("frequency", (short) e.frequency);
            c.setInteger("range", e.range);
            c.setString("url", e.url);
            c.setString("name", e.name);
            if (e.owner != null) {
                c.setLong("ownerMost", e.owner.getMostSignificantBits());
                c.setLong("ownerLeast", e.owner.getLeastSignificantBits());
            }
            c.setBoolean("active", e.active);
            c.setBoolean("needsLoaded", e.needsLoadedChunk);
            list.appendTag(c);
        }
        tag.setTag("transmitters", list);
    }
}
