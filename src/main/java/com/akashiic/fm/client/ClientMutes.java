package com.akashiic.fm.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.akashiic.fm.common.Pos;

/**
 * O que este jogador silenciou só para si nesta sessão (tecla "silenciar esta rádio"):
 * <ul>
 * <li>uma rádio, pela posição (olhando a rádio ou uma caixa dela);</li>
 * <li>uma estação, pela URL (olhando um transmissor: as rádios e portáteis sintonizados nele);</li>
 * <li>o portátil de outro jogador, pelo UUID (o id da entidade muda ao trocar de dimensão).</li>
 * </ul>
 * Limpo ao desconectar. Thread principal do cliente.
 */
public final class ClientMutes {

    private static final Set<String> RADIOS = new HashSet<>();
    private static final Set<String> URLS = new HashSet<>();
    private static final Set<UUID> CARRIERS = new HashSet<>();
    /** Id de entidade → UUID dos portadores já vistos (o portátil chega só com o id da entidade). */
    private static final Map<Integer, UUID> SEEN = new HashMap<>();

    private ClientMutes() {}

    private static String key(int dim, Pos p) {
        return dim + "@" + p;
    }

    /** Alterna; devolve true se ficou silenciada. */
    public static boolean toggleRadio(int dim, Pos p) {
        String k = key(dim, p);
        if (RADIOS.remove(k)) return false;
        RADIOS.add(k);
        return true;
    }

    public static boolean toggleUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (URLS.remove(url)) return false;
        URLS.add(url);
        return true;
    }

    public static boolean toggleCarrier(int entityId, UUID player) {
        if (player == null) return false;
        SEEN.put(entityId, player);
        if (CARRIERS.remove(player)) return false;
        CARRIERS.add(player);
        return true;
    }

    /** A entidade do portador foi vista com este UUID (para reconhecer o portátil dele pelo id). */
    public static void sawCarrier(int entityId, UUID player) {
        if (player != null && !CARRIERS.isEmpty()) SEEN.put(entityId, player);
        if (SEEN.size() > 4096) SEEN.clear();
    }

    /** Chamado por rádio a cada tick: sem nada silenciado (o normal), não monta chave nenhuma. */
    public static boolean radioMuted(int dim, Pos p, String effectiveUrl) {
        if (RADIOS.isEmpty() && URLS.isEmpty()) return false;
        return (!RADIOS.isEmpty() && RADIOS.contains(key(dim, p))) || urlMuted(effectiveUrl);
    }

    public static boolean urlMuted(String url) {
        return url != null && !url.isEmpty() && URLS.contains(url);
    }

    public static boolean carrierMuted(int entityId) {
        if (CARRIERS.isEmpty()) return false;
        UUID id = SEEN.get(entityId);
        return id != null && CARRIERS.contains(id);
    }

    public static boolean any() {
        return !RADIOS.isEmpty() || !URLS.isEmpty() || !CARRIERS.isEmpty();
    }

    public static void clear() {
        RADIOS.clear();
        URLS.clear();
        CARRIERS.clear();
        SEEN.clear();
    }
}
