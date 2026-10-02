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

import com.akashiic.fm.common.Pos;

/**
 * Índice persistente de todas as rádios do mundo (carregadas ou não), guardado no armazenamento global
 * (compartilhado entre dimensões). Serve para o limite de rádios por jogador e para a listagem de admin.
 * Entra quando o TE é validado no servidor e sai quando o bloco é removido (invalidate), não quando o
 * chunk descarrega.
 */
public final class RadioIndex extends WorldSavedData {

    public static final String NAME = "akashicfm_radios";
    private static final int MAX_ENTRIES = 100_000;

    public static final class Entry {

        public final int dim;
        public final Pos pos;
        public final UUID owner;

        Entry(int dim, Pos pos, UUID owner) {
            this.dim = dim;
            this.pos = pos;
            this.owner = owner;
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

    public RadioIndex(String name) {
        super(name);
    }

    public static RadioIndex get(World world) {
        MapStorage storage = world.mapStorage;
        RadioIndex idx = (RadioIndex) storage.loadData(RadioIndex.class, NAME);
        if (idx == null) {
            idx = new RadioIndex(NAME);
            storage.setData(NAME, idx);
        }
        return idx;
    }

    public void put(int dim, Pos pos, UUID owner) {
        Key key = new Key(dim, pos);
        Entry old = entries.get(key);
        if (old != null && (old.owner == null ? owner == null : old.owner.equals(owner))) return;
        if (old == null && entries.size() >= MAX_ENTRIES) return;
        entries.put(key, new Entry(dim, pos, owner));
        markDirty();
    }

    public void remove(int dim, Pos pos) {
        if (entries.remove(new Key(dim, pos)) != null) markDirty();
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
        NBTTagList list = tag.getTagList("radios", 10);
        int n = Math.min(list.tagCount(), MAX_ENTRIES);
        for (int i = 0; i < n; i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            Pos pos = new Pos(e.getInteger("x"), e.getInteger("y"), e.getInteger("z"));
            int dim = e.getInteger("dim");
            UUID owner = e.hasKey("ownerMost") ? new UUID(e.getLong("ownerMost"), e.getLong("ownerLeast")) : null;
            entries.put(new Key(dim, pos), new Entry(dim, pos, owner));
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
            if (e.owner != null) {
                c.setLong("ownerMost", e.owner.getMostSignificantBits());
                c.setLong("ownerLeast", e.owner.getLeastSignificantBits());
            }
            list.appendTag(c);
        }
        tag.setTag("radios", list);
    }
}
