package com.akashiic.fm.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.FrequencyResolver;
import com.akashiic.fm.common.PortableState;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.ItemPortableRadio;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CPortableSources;
import com.akashiic.fm.server.relay.RelayService;

/**
 * Rádios portáteis tocando. A cada {@link #INTERVAL_TICKS}, para cada jogador conectado:
 * <ul>
 * <li>acha o primeiro portátil ligado nos 36 slots do inventário;</li>
 * <li>resolve o que ele toca: a URL do item, ou o transmissor mais forte na posição do jogador (modo FM); a URL passa
 * de novo pela política (o NBT de um item pode vir de qualquer lugar) e o transporte é escolhido como o da rádio;</li>
 * <li>decide quem ouve: o portador sempre; os outros jogadores da mesma dimensão dentro do alcance, com histerese,
 * só se ele não estiver de fone;</li>
 * <li>manda a cada ouvinte a lista do que ele ouve (quando muda, e renovada a cada {@link #REFRESH_CYCLES}
 * ciclos);</li>
 * <li>diz ao relay quem precisa de quais estações (as mesmas estações das rádios: tudo sincronizado).</li>
 * </ul>
 * Thread principal do servidor.
 */
public final class PortableSources {

    static final int INTERVAL_TICKS = 10;
    /** Renova a lista mesmo sem mudança (o cliente esquece uma lista velha). */
    static final int REFRESH_CYCLES = 4;
    static final double HYSTERESIS = 4.0;
    static final int SIGNAL_STEP = 5;

    /** O portátil de um jogador entre ciclos. */
    private static final class Carrier {

        Pos tuned;
        String url = "";
        Transport transport = Transport.NONE;
        int itemSession = Integer.MIN_VALUE;
        int session;
        int signal;
        Set<UUID> audience = new HashSet<>();
    }

    private static final Map<UUID, Carrier> CARRIERS = new HashMap<>();
    private static final Map<UUID, String> SENT = new HashMap<>();
    private static final Map<UUID, Integer> SENT_AGE = new HashMap<>();
    private static Map<UUID, Set<String>> relayWants = Collections.emptyMap();
    private static Set<String> relayedUrls = Collections.emptySet();
    private static int ticks;

    private PortableSources() {}

    public static void tick() {
        if (++ticks % INTERVAL_TICKS != 0) return;
        Map<UUID, EntityPlayerMP> online = onlinePlayers();
        Map<Integer, List<FrequencyResolver.Transmitter>> candidates = new HashMap<>();

        // 1) As fontes deste ciclo.
        Map<UUID, S2CPortableSources.Entry> sources = new HashMap<>();
        for (EntityPlayerMP p : online.values()) {
            S2CPortableSources.Entry e = FmConfig.Portable.enabled ? sourceOf(p, candidates) : null;
            if (e != null) sources.put(p.getUniqueID(), e);
        }
        CARRIERS.keySet()
            .retainAll(sources.keySet());

        // 2) Quem ouve cada uma.
        Map<UUID, List<S2CPortableSources.Entry>> perListener = new HashMap<>();
        Map<UUID, Set<String>> wants = new HashMap<>();
        Set<String> urls = new HashSet<>();
        for (Map.Entry<UUID, S2CPortableSources.Entry> se : sources.entrySet()) {
            EntityPlayerMP carrier = online.get(se.getKey());
            S2CPortableSources.Entry e = se.getValue();
            Carrier c = CARRIERS.get(se.getKey());
            Set<UUID> audience = new HashSet<>();
            audience.add(se.getKey());
            if (!e.headphones && audible(e)) {
                for (Object o : carrier.worldObj.playerEntities) {
                    if (!(o instanceof EntityPlayerMP) || o instanceof FakePlayer || o == carrier) continue;
                    EntityPlayerMP other = (EntityPlayerMP) o;
                    double limit = e.range + (c.audience.contains(other.getUniqueID()) ? HYSTERESIS : 0);
                    if (other.getDistanceToEntity(carrier) <= limit) audience.add(other.getUniqueID());
                }
            }
            c.audience = audience;
            for (UUID l : audience) perListener.computeIfAbsent(l, k -> new ArrayList<>())
                .add(e);
            if (e.transport == Transport.RELAY && !e.url.isEmpty()) {
                urls.add(e.url);
                for (UUID l : audience) wants.computeIfAbsent(l, k -> new HashSet<>())
                    .add(e.url);
            }
        }
        relayWants = wants;
        relayedUrls = urls;

        // 3) A lista de cada ouvinte, quando muda (e renovada de tempos em tempos).
        for (EntityPlayerMP p : online.values()) {
            UUID id = p.getUniqueID();
            List<S2CPortableSources.Entry> list = perListener.getOrDefault(id, Collections.emptyList());
            String sig = signature(list);
            String last = SENT.get(id);
            int age = SENT_AGE.getOrDefault(id, 0) + 1;
            if (last == null && list.isEmpty()) {
                SENT.put(id, sig); // nunca ouviu nada: não precisa avisar que continua sem nada
            } else if (!sig.equals(last) || (!list.isEmpty() && age >= REFRESH_CYCLES)) {
                FmNetwork.sendTo(new S2CPortableSources(list), p);
                SENT.put(id, sig);
                age = 0;
            }
            SENT_AGE.put(id, age);
        }
        SENT.keySet()
            .retainAll(online.keySet());
        SENT_AGE.keySet()
            .retainAll(online.keySet());
    }

    private static boolean audible(S2CPortableSources.Entry e) {
        return !e.url.isEmpty() && e.transport != Transport.NONE;
    }

    /** O portátil ligado do jogador e o que ele toca agora, ou null se não há. */
    private static S2CPortableSources.Entry sourceOf(EntityPlayerMP p,
        Map<Integer, List<FrequencyResolver.Transmitter>> candidates) {
        if (p.isDead || p.getHealth() <= 0) return null; // morto (com keepInventory) não toca no lugar da morte
        PortableState s = firstOn(p);
        if (s == null) return null;
        Carrier c = CARRIERS.computeIfAbsent(p.getUniqueID(), k -> new Carrier());
        String url, station = "";
        int signal = 0;
        if (s.mode == TuneMode.FREQUENCY) {
            int dim = p.worldObj.provider.dimensionId;
            List<FrequencyResolver.Transmitter> list = candidates.get(dim);
            if (list == null) {
                list = FrequencyService.candidates(p.worldObj, dim);
                candidates.put(dim, list);
            }
            FrequencyResolver.Tuning t = FrequencyResolver.resolve(list, s.frequency, p.posX, p.posY, p.posZ, c.tuned);
            c.tuned = t == null ? null : t.transmitter.pos;
            url = t == null ? "" : t.transmitter.url;
            station = t == null ? "" : t.transmitter.name;
            signal = t == null ? 0 : (int) Math.round(t.signal * 100);
        } else {
            c.tuned = null;
            url = s.url;
        }
        Transport transport;
        String status = "";
        if (url.isEmpty()) {
            transport = Transport.NONE;
            status = FrequencyService.NO_SIGNAL;
        } else {
            UrlPolicy.PolicyException e = ServerPolicy.rejection(url);
            if (e != null) {
                transport = Transport.NONE;
                status = e.translationKey() + (e.detail.isEmpty() ? "" : "|" + TextSanitizer.clean(e.detail, 64));
            } else {
                transport = ServerPolicy.chooseTransport(url);
                if (transport == Transport.NONE) status = FrequencyService.NO_TRANSPORT;
                else if (transport == Transport.RELAY) status = RelayService.statusOf(url);
            }
        }
        boolean newSource = !url.equals(c.url) || transport != c.transport || s.session != c.itemSession;
        if (newSource) {
            c.session++;
            c.url = url;
            c.transport = transport;
            c.itemSession = s.session;
        }
        if (newSource || Math.abs(signal - c.signal) >= SIGNAL_STEP || (signal == 0) != (c.signal == 0)) {
            c.signal = signal;
        }
        S2CPortableSources.Entry e = new S2CPortableSources.Entry();
        e.entityId = p.getEntityId();
        e.session = c.session;
        e.url = url;
        e.transport = transport;
        e.volume = s.volume;
        e.range = RadioLimits.clamp(FmConfig.Portable.range, 4, 64);
        e.headphones = Headphones.isWorn(p);
        e.title = transport == Transport.RELAY ? RelayService.titleFor(url) : "";
        e.mode = s.mode;
        e.frequency = s.frequency;
        e.signal = c.signal;
        e.station = station;
        e.status = status;
        e.x = p.posX;
        e.y = p.boundingBox.minY;
        e.z = p.posZ;
        return e;
    }

    /** O primeiro portátil ligado do inventário (barra e mochila), ou null. */
    static PortableState firstOn(EntityPlayerMP p) {
        if (p.inventory == null) return null;
        for (int slot = 0; slot < PortableActionHandler.SLOTS; slot++) {
            ItemStack st = p.inventory.mainInventory[slot];
            if (st == null || !(st.getItem() instanceof ItemPortableRadio)) continue;
            PortableState s = ItemPortableRadio.state(st);
            if (s.on) return s;
        }
        return null;
    }

    private static String signature(List<S2CPortableSources.Entry> list) {
        StringBuilder b = new StringBuilder();
        for (S2CPortableSources.Entry e : list) b.append(e.signature())
            .append('\n');
        return b.toString();
    }

    private static Map<UUID, EntityPlayerMP> onlinePlayers() {
        Map<UUID, EntityPlayerMP> out = new HashMap<>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return out;
        for (Object o : server.getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP && !(o instanceof FakePlayer)) {
                EntityPlayerMP p = (EntityPlayerMP) o;
                out.put(p.getUniqueID(), p);
            }
        }
        return out;
    }

    // ---- Para o relay ----

    /** Jogador → URLs de portáteis que ele precisa receber pelo relay (último ciclo). */
    public static Map<UUID, Set<String>> relayWants() {
        return relayWants;
    }

    /** URLs que algum portátil toca pelo relay (último ciclo; contam no limite de estações). */
    public static Set<String> relayedUrls() {
        return relayedUrls;
    }

    /** Servidor parando. */
    public static void clear() {
        CARRIERS.clear();
        SENT.clear();
        SENT_AGE.clear();
        relayWants = Collections.emptyMap();
        relayedUrls = Collections.emptySet();
        ticks = 0;
    }
}
