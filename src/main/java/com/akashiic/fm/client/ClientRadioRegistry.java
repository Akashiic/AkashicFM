package com.akashiic.fm.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.World;

import com.akashiic.fm.common.Pos;
import com.akashiic.fm.content.TileRadio;

/**
 * Rádios carregadas no mundo do cliente. Entra quando o TE é validado e sai quando o bloco some ou o chunk
 * descarrega (o OpenFM não tratava o descarregamento, e daí vinha o som tocando a 1000 blocos).
 * <p>
 * Quase tudo acontece na thread principal, mas o mapa é concorrente: mods de render com montagem de chunk
 * em threads (Angelica, por exemplo) podem criar o TE fora dela, e um HashMap corrompido aqui seria som
 * fantasma ou exceção no tick.
 */
public final class ClientRadioRegistry {

    private static final ConcurrentHashMap<Pos, TileRadio> RADIOS = new ConcurrentHashMap<>();

    private ClientRadioRegistry() {}

    public static void add(TileRadio radio) {
        RADIOS.put(radio.pos(), radio);
    }

    /** Só remove se a entrada ainda for este TE (um TE novo na mesma posição não é apagado por engano). */
    public static void remove(TileRadio radio) {
        RADIOS.remove(radio.pos(), radio);
    }

    public static TileRadio get(Pos pos) {
        return RADIOS.get(pos);
    }

    public static List<TileRadio> snapshot() {
        return new ArrayList<>(RADIOS.values());
    }

    /** Tira as rádios de um mundo que está sendo descarregado (troca de dimensão, saída do servidor). */
    public static void removeWorld(World world) {
        RADIOS.values()
            .removeIf(r -> r.getWorldObj() == world || r.getWorldObj() == null);
    }

    /** Fica só com as rádios de {@code current} (null: tira todas). */
    public static void retainWorld(World current) {
        RADIOS.values()
            .removeIf(r -> current == null || r.getWorldObj() != current);
    }

    public static void clear() {
        RADIOS.clear();
    }

    public static int size() {
        return RADIOS.size();
    }
}
