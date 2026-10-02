package com.akashiic.fm.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.akashiic.fm.common.Pos;
import com.akashiic.fm.content.TileRadio;

/**
 * Rádios carregadas no servidor, por dimensão. Só a thread principal do servidor mexe aqui
 * (validate/invalidate/onChunkUnload dos TEs e os ticks do mod rodam nela).
 */
public final class ServerRadioRegistry {

    private static final Map<Integer, Map<Pos, TileRadio>> BY_DIM = new HashMap<>();

    private ServerRadioRegistry() {}

    public static void add(TileRadio radio) {
        BY_DIM.computeIfAbsent(radio.dimension(), d -> new HashMap<>())
            .put(radio.pos(), radio);
    }

    public static void remove(TileRadio radio) {
        Map<Pos, TileRadio> m = BY_DIM.get(radio.dimension());
        if (m == null) return;
        // Só remove se for o mesmo objeto: um TE novo na mesma posição não pode ser apagado pelo velho.
        if (m.get(radio.pos()) == radio) m.remove(radio.pos());
        if (m.isEmpty()) BY_DIM.remove(radio.dimension());
    }

    public static TileRadio get(int dim, Pos pos) {
        Map<Pos, TileRadio> m = BY_DIM.get(dim);
        return m == null ? null : m.get(pos);
    }

    public static Collection<TileRadio> inDimension(int dim) {
        Map<Pos, TileRadio> m = BY_DIM.get(dim);
        return m == null ? Collections.emptyList() : m.values();
    }

    /** Cópia de todas as rádios carregadas (seguro para iterar enquanto TEs entram e saem). */
    public static List<TileRadio> snapshot() {
        List<TileRadio> all = new ArrayList<>();
        for (Map<Pos, TileRadio> m : BY_DIM.values()) all.addAll(m.values());
        return all;
    }

    public static void clear() {
        BY_DIM.clear();
    }
}
