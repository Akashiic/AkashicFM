package com.akashiic.fm.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TextSanitizer;

/**
 * Jogadores bloqueados por um admin ({@code /fm block}): não controlam rádio, transmissor nem portátil, os
 * transmissores deles saem do ar e os portáteis deles ficam mudos. Persistido no mundo (global).
 */
public final class Moderation extends WorldSavedData {

    public static final String NAME = "akashicfm_moderation";
    private static final int MAX_ENTRIES = 10_000;

    /** Cache do servidor atual (limpo ao parar). */
    private static Moderation instance;

    private final Map<UUID, String> blocked = new LinkedHashMap<>();

    public Moderation(String name) {
        super(name);
    }

    /** A instância do servidor rodando, ou null sem servidor. */
    public static Moderation get() {
        if (instance != null) return instance;
        MinecraftServer server = MinecraftServer.getServer();
        World overworld = server == null ? null : server.worldServerForDimension(0);
        if (overworld == null) return null;
        MapStorage storage = overworld.mapStorage;
        Moderation m = (Moderation) storage.loadData(Moderation.class, NAME);
        if (m == null) {
            m = new Moderation(NAME);
            storage.setData(NAME, m);
        }
        instance = m;
        return m;
    }

    public static boolean isBlocked(UUID player) {
        Moderation m = player == null ? null : get();
        return m != null && m.blocked.containsKey(player);
    }

    public static boolean isBlocked(EntityPlayer player) {
        return player != null && isBlocked(player.getUniqueID());
    }

    /** Devolve false se já estava. */
    public boolean block(UUID id, String name) {
        if (blocked.containsKey(id) || blocked.size() >= MAX_ENTRIES) return false;
        blocked.put(id, TextSanitizer.clean(name == null ? "" : name, RadioLimits.MAX_OWNER_NAME));
        markDirty();
        return true;
    }

    /** Devolve false se não estava. */
    public boolean unblock(UUID id) {
        if (blocked.remove(id) == null) return false;
        markDirty();
        return true;
    }

    /** UUID → nome guardado, na ordem do bloqueio. */
    public Map<UUID, String> blocked() {
        return Collections.unmodifiableMap(blocked);
    }

    /** Acha um bloqueado pelo nome guardado (sem diferenciar maiúsculas), ou null. */
    public UUID byName(String name) {
        for (Map.Entry<UUID, String> e : blocked.entrySet()) if (e.getValue()
            .equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    public List<String> names() {
        return new ArrayList<>(blocked.values());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        blocked.clear();
        NBTTagList list = tag.getTagList("blocked", 10);
        for (int i = 0; i < Math.min(list.tagCount(), MAX_ENTRIES); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            if (!c.hasKey("most") || !c.hasKey("least")) continue;
            blocked.put(
                new UUID(c.getLong("most"), c.getLong("least")),
                TextSanitizer.clean(RadioState.str(c, "name"), RadioLimits.MAX_OWNER_NAME));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, String> e : blocked.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setLong(
                "most",
                e.getKey()
                    .getMostSignificantBits());
            c.setLong(
                "least",
                e.getKey()
                    .getLeastSignificantBits());
            c.setString("name", e.getValue());
            list.appendTag(c);
        }
        tag.setTag("blocked", list);
    }

    /** Servidor parando. */
    public static void clear() {
        instance = null;
    }
}
