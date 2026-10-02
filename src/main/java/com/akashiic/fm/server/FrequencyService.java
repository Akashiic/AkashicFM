package com.akashiic.fm.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkProviderServer;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.FrequencyResolver;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileRadio;

/**
 * Sintonia das rádios em modo FREQUENCY. A cada {@link #INTERVAL_TICKS}, cada rádio ligada procura no
 * {@link TransmitterIndex} o transmissor mais forte na frequência dela (sem carregar chunk) e passa a tocar a URL
 * dele: URL, transporte e status vão para o estado e chegam ao cliente pelo pacote do bloco. Sem transmissor
 * cobrindo o lugar, a rádio fica ligada "sem sinal" e pega sozinha quando um aparecer. Thread principal.
 */
public final class FrequencyService {

    static final int INTERVAL_TICKS = 10;
    /** O sinal só vai para o cliente quando muda pelo menos isto (cada mudança é um pacote para quem vê). */
    static final int SIGNAL_STEP = 5;
    static final String NO_SIGNAL = "akashicfm.status.no_signal";
    static final String NO_TRANSPORT = "akashicfm.status.no_transport";

    /** Transmissor ouvido por cada rádio (histerese entre dois transmissores). */
    private static final Map<TileRadio, Pos> CURRENT = new WeakHashMap<>();
    private static int ticks;

    private FrequencyService() {}

    public static void tick() {
        if (++ticks % INTERVAL_TICKS != 0) return;
        // Candidatos de cada dimensão montados uma vez por ciclo (não uma vez por rádio).
        Map<Integer, List<FrequencyResolver.Transmitter>> byDim = new HashMap<>();
        for (TileRadio radio : ServerRadioRegistry.snapshot()) {
            RadioState s = radio.state;
            World world = radio.getWorldObj();
            if (world == null || radio.isInvalid() || s.mode != TuneMode.FREQUENCY || !s.playing) continue;
            List<FrequencyResolver.Transmitter> list = byDim.get(radio.dimension());
            if (list == null) {
                list = candidates(world, radio.dimension());
                byDim.put(radio.dimension(), list);
            }
            if (retune(radio, list)) radio.markStateChanged();
        }
    }

    /** Sintoniza agora (tocar, trocar a frequência). Devolve true se o estado mudou (quem chama manda o pacote). */
    public static boolean retune(TileRadio radio) {
        World world = radio.getWorldObj();
        if (world == null) return false;
        return retune(radio, candidates(world, radio.dimension()));
    }

    private static boolean retune(TileRadio radio, List<FrequencyResolver.Transmitter> candidates) {
        FrequencyResolver.Tuning t = FrequencyResolver.resolve(
            candidates,
            radio.state.frequency,
            radio.xCoord + 0.5,
            radio.yCoord + 0.5,
            radio.zCoord + 0.5,
            CURRENT.get(radio));
        if (t == null) CURRENT.remove(radio);
        else CURRENT.put(radio, t.transmitter.pos);
        return apply(radio.state, t);
    }

    /** Transmissores ativos da dimensão; os que exigem energia só contam com o chunk carregado. */
    static List<FrequencyResolver.Transmitter> candidates(World world, int dim) {
        List<FrequencyResolver.Transmitter> out = new ArrayList<>();
        for (TransmitterIndex.Entry e : TransmitterIndex.get(world)
            .inDimension(dim)) {
            if (eligible(e, Moderation.isBlocked(e.owner), () -> chunkLoaded(world, e.pos))) {
                out.add(new FrequencyResolver.Transmitter(e.pos, e.frequency, e.range, e.url, e.name, true));
            }
        }
        return out;
    }

    /**
     * O transmissor do índice conta: ativo, dono não bloqueado por um admin e, se exige energia, com o chunk
     * carregado (só consultado quando precisa).
     */
    static boolean eligible(TransmitterIndex.Entry e, boolean ownerBlocked, BooleanSupplier chunkLoaded) {
        if (!e.active || ownerBlocked) return false;
        return !e.needsLoadedChunk || chunkLoaded.getAsBoolean();
    }

    private static boolean chunkLoaded(World world, Pos p) {
        if (!(world.getChunkProvider() instanceof ChunkProviderServer)) return world.blockExists(p.x, p.y, p.z);
        // chunkExists nunca carrega (blockExists no servidor também não, mas este é explícito).
        return ((ChunkProviderServer) world.getChunkProvider()).chunkExists(p.x >> 4, p.z >> 4);
    }

    /** Aplica o resultado da sintonia no estado; devolve true se mudou algo. */
    static boolean apply(RadioState s, FrequencyResolver.Tuning t) {
        String url = t == null ? "" : t.transmitter.url;
        String name = t == null ? "" : t.transmitter.name;
        int signal = t == null ? 0 : (int) Math.round(t.signal * 100);
        boolean changed = false;
        boolean newSource = false;
        if (!url.equals(s.tunedUrl)) {
            s.tunedUrl = url;
            s.nowPlaying = ""; // o título era da estação anterior
            newSource = true;
            changed = true;
        }
        // Transporte para a URL atual: muda com a URL, a allowlist do admin e o limite de estações do relay.
        Transport wanted;
        String status;
        if (url.isEmpty()) {
            wanted = Transport.NONE;
            status = NO_SIGNAL;
        } else {
            UrlPolicy.PolicyException e = ServerPolicy.rejection(url);
            if (e != null) {
                wanted = Transport.NONE;
                status = e.translationKey() + (e.detail.isEmpty() ? "" : "|" + TextSanitizer.clean(e.detail, 64));
            } else {
                wanted = ServerPolicy.chooseTransport(url);
                status = wanted == Transport.NONE ? NO_TRANSPORT : "";
            }
        }
        if (wanted != s.transport) {
            s.transport = wanted;
            newSource = true;
            changed = true;
        }
        if (newSource) {
            s.session++; // clientes recomeçam com a fonte nova
            // O status (erro/reconexão do relay) era da fonte anterior; o da nova vem do relay em seguida.
            if (!s.status.isEmpty()) {
                s.status = "";
                changed = true;
            }
        }
        if (wanted == Transport.NONE) {
            if (!status.equals(s.status)) {
                s.status = status;
                changed = true;
            }
        } else if (isTuningStatus(s.status)) {
            s.status = ""; // voltou o sinal: o status agora é o do relay
            changed = true;
        }
        if (!name.equals(s.tunedName)) {
            s.tunedName = name;
            changed = true;
        }
        // Fonte nova: o sinal é outro (mesmo que perto do anterior). Mesma fonte: só passos de SIGNAL_STEP.
        if (signal != s.signal
            && (newSource || Math.abs(signal - s.signal) >= SIGNAL_STEP || (signal == 0) != (s.signal == 0))) {
            s.signal = signal;
            changed = true;
        }
        return changed;
    }

    private static boolean isTuningStatus(String status) {
        return status.startsWith(NO_SIGNAL) || status.startsWith(NO_TRANSPORT)
            || status.startsWith("akashicfm.policy.");
    }

    /** Esquece a memória de histerese (servidor parando). */
    public static void clear() {
        CURRENT.clear();
        ticks = 0;
    }
}
