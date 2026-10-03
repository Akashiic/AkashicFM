package com.akashiic.fm.server.relay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.relay.FrameRing;
import com.akashiic.fm.audio.stream.StreamPump;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.RateLimiter;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CAudio;
import com.akashiic.fm.network.S2CClockPong;
import com.akashiic.fm.network.S2CListen;
import com.akashiic.fm.server.PortableSources;
import com.akashiic.fm.server.ServerRadioRegistry;

/**
 * Relay do servidor. Roda no tick principal (só a rotina de ping chega pela rede):
 * <ul>
 * <li><b>audiência</b>, a cada {@link #AUDIENCE_INTERVAL_TICKS}: cada jogador recebe as estações das rádios em
 * RELAY que têm alguma fonte (rádio ou caixa) dentro do alcance dele, com histerese; quem sai do alcance para de
 * receber na hora (é o servidor quem decide, não o cliente);</li>
 * <li><b>envio</b>, todo tick: frames com PTS até {@link #SEND_LEAD_MS} à frente do relógio. Quem entra começa
 * em {@code agora − latência + }{@link #JOIN_MARGIN_MS} e já toca sincronizado, com folga no buffer;</li>
 * <li><b>relógio</b>: responde os pings de sincronia com t2 carimbado na hora do envio;</li>
 * <li><b>status</b>: erro/reconexão da estação e o título ICY vão para o estado das rádios.</li>
 * </ul>
 */
public final class RelayService {

    static final int AUDIENCE_INTERVAL_TICKS = 10;
    static final long SEND_LEAD_MS = 100;
    static final long JOIN_MARGIN_MS = 200;
    static final double HYSTERESIS = 4.0;
    static final int MAX_FRAMES_PER_PACKET = 32;
    /** Título ICY: no máximo uma atualização por rádio a cada 5 s (cada uma vira um pacote para quem vê). */
    static final long TITLE_INTERVAL_MS = 5000;
    private static final int MAX_PENDING_PINGS = 1024;

    private static final class Subscription {

        final Station station;
        long lastSentSeq;

        Subscription(Station station, long lastSentSeq) {
            this.station = station;
            this.lastSentSeq = lastSentSeq;
        }
    }

    private static final class Ping {

        final EntityPlayerMP player;
        final long t0, t1;

        Ping(EntityPlayerMP player, long t0, long t1) {
            this.player = player;
            this.t0 = t0;
            this.t1 = t1;
        }
    }

    private static final StationHub HUB = new StationHub();
    /** Assinaturas por jogador, por id de estação. Só a thread principal. */
    private static final Map<UUID, Map<Integer, Subscription>> SUBS = new HashMap<>();
    /** Jogadores com o som do mod desligado no cliente ({@code C2SListening}): fora de toda audiência. */
    private static final Set<UUID> NOT_LISTENING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> BYTES_SENT = new HashMap<>();
    private static final Map<Pos, Long> TITLE_UPDATED_MS = new HashMap<>();
    private static final ConcurrentLinkedQueue<Ping> PINGS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_PINGS = new AtomicInteger();
    private static final RateLimiter PING_LIMITER = new RateLimiter();
    private static int tickCounter;

    private RelayService() {}

    // ---- Rede (thread do netty) ----

    public static void offerClockPing(EntityPlayerMP player, long t0, long t1) {
        if (player == null || !PING_LIMITER.tryAcquire(player.getUniqueID(), 4)) return;
        if (PENDING_PINGS.incrementAndGet() > MAX_PENDING_PINGS) {
            PENDING_PINGS.decrementAndGet();
            return;
        }
        PINGS.add(new Ping(player, t0, t1));
    }

    // ---- Tick principal ----

    public static void tick() {
        answerPings();
        if (!FmConfig.Relay.enabled) {
            // Relay desligado no config com o servidor rodando: avisa os ouvintes e fecha tudo.
            if (HUB.size() > 0 || !SUBS.isEmpty()) {
                notifyStopAll();
                shutdown();
            }
            return;
        }
        if (++tickCounter % AUDIENCE_INTERVAL_TICKS == 0) updateAudience();
        sendFrames();
    }

    private static void answerPings() {
        for (int i = 0; i < 256; i++) {
            Ping p = PINGS.poll();
            if (p == null) return;
            PENDING_PINGS.decrementAndGet();
            if (p.player.playerNetServerHandler == null) continue;
            FmNetwork.sendTo(new S2CClockPong(p.t0, p.t1, ServerClock.nowMicros()), p.player);
        }
    }

    private static int latencyMs() {
        return RadioLimits.clamp(FmConfig.Relay.latencyTargetMs, 300, 5000);
    }

    private static boolean relayed(TileRadio r) {
        RadioState s = r.state;
        return !r.isInvalid() && s.playing
            && s.transport == Transport.RELAY
            && !s.effectiveUrl()
                .isEmpty();
    }

    /** Distância do jogador até a fonte mais próxima da rádio (ela mesma e as caixas, sem carregar chunk). */
    static double nearestEmitter(TileRadio radio, double px, double py, double pz) {
        double best = radio.pos()
            .distanceSqTo(px, py, pz);
        for (Pos p : radio.state.speakers) best = Math.min(best, p.distanceSqTo(px, py, pz));
        return Math.sqrt(best);
    }

    private static void updateAudience() {
        long now = ServerClock.nowMs();
        Map<UUID, EntityPlayerMP> online = onlinePlayers();
        Map<UUID, Set<String>> wanted = new HashMap<>();
        Set<String> wantedUrls = new HashSet<>();

        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) continue;
            List<TileRadio> radios = new ArrayList<>();
            for (TileRadio r : ServerRadioRegistry.inDimension(world.provider.dimensionId))
                if (relayed(r)) radios.add(r);
            if (radios.isEmpty()) continue;
            for (Object o : world.playerEntities) {
                if (!(o instanceof EntityPlayerMP) || o instanceof FakePlayer) continue;
                EntityPlayerMP player = (EntityPlayerMP) o;
                UUID id = player.getUniqueID();
                if (NOT_LISTENING.contains(id)) continue; // som desligado no cliente: como quem está longe
                for (TileRadio r : radios) {
                    String url = r.state.effectiveUrl();
                    boolean already = isSubscribedTo(id, url);
                    double limit = r.state.range + (already ? HYSTERESIS : 0);
                    if (nearestEmitter(r, player.posX, player.posY, player.posZ) <= limit) {
                        wanted.computeIfAbsent(id, k -> new HashSet<>())
                            .add(url);
                        wantedUrls.add(url);
                    }
                }
            }
        }

        // Rádios portáteis: o PortableSources já decidiu quem ouve cada uma (com histerese própria).
        for (Map.Entry<UUID, Set<String>> e : PortableSources.relayWants()
            .entrySet()) {
            if (!online.containsKey(e.getKey()) || NOT_LISTENING.contains(e.getKey())) continue;
            wanted.computeIfAbsent(e.getKey(), k -> new HashSet<>())
                .addAll(e.getValue());
            wantedUrls.addAll(e.getValue());
        }

        // Quem não quer mais (ou saiu do jogo / da dimensão) para de receber.
        int total = 0;
        Iterator<Map.Entry<UUID, Map<Integer, Subscription>>> pit = SUBS.entrySet()
            .iterator();
        while (pit.hasNext()) {
            Map.Entry<UUID, Map<Integer, Subscription>> e = pit.next();
            EntityPlayerMP player = online.get(e.getKey());
            Set<String> urls = wanted.get(e.getKey());
            Iterator<Subscription> sit = e.getValue()
                .values()
                .iterator();
            while (sit.hasNext()) {
                Subscription sub = sit.next();
                boolean keep = player != null && urls != null
                    && urls.contains(sub.station.url)
                    && HUB.byId(sub.station.id) == sub.station;
                if (!keep) {
                    if (player != null) FmNetwork.sendTo(S2CListen.stop(sub.station.id), player);
                    sit.remove();
                }
            }
            if (e.getValue()
                .isEmpty()) pit.remove();
            else total += e.getValue()
                .size();
        }

        // Quem quer e ainda não recebe começa, dentro dos limites.
        int maxListeners = Math.max(1, FmConfig.Relay.maxListeners);
        for (Map.Entry<UUID, Set<String>> e : wanted.entrySet()) {
            EntityPlayerMP player = online.get(e.getKey());
            if (player == null) continue;
            for (String url : e.getValue()) {
                if (isSubscribedTo(e.getKey(), url)) continue;
                if (total >= maxListeners) break;
                Station station = HUB.acquire(url, now);
                if (station == null) continue; // limite de estações (a escolha do transporte já evita isto)
                long start = station.ring.seqBeforePts(now - latencyMs() + JOIN_MARGIN_MS);
                SUBS.computeIfAbsent(e.getKey(), k -> new HashMap<>())
                    .put(station.id, new Subscription(station, start));
                FmNetwork.sendTo(S2CListen.start(station.id, url, latencyMs()), player);
                total++;
            }
        }
        for (String url : wantedUrls) {
            Station s = HUB.get(url);
            if (s != null) s.lastWantedMs = now;
        }
        HUB.expire(now);
        updateRadioStatus(now);
    }

    private static boolean isSubscribedTo(UUID player, String url) {
        Map<Integer, Subscription> subs = SUBS.get(player);
        if (subs == null) return false;
        for (Subscription s : subs.values()) if (s.station.url.equals(url)) return true;
        return false;
    }

    private static void sendFrames() {
        if (SUBS.isEmpty()) return;
        long now = ServerClock.nowMs();
        long oldestUseful = now - latencyMs() + SEND_LEAD_MS;
        Map<UUID, EntityPlayerMP> online = onlinePlayers();
        List<FrameRing.Frame> frames = new ArrayList<>(MAX_FRAMES_PER_PACKET);
        for (Map.Entry<UUID, Map<Integer, Subscription>> e : SUBS.entrySet()) {
            EntityPlayerMP player = online.get(e.getKey());
            if (player == null || player.playerNetServerHandler == null) continue;
            for (Subscription sub : e.getValue()
                .values()) {
                FrameRing ring = sub.station.ring;
                // Depois de um lag do servidor, frames velhos demais não servem para ninguém: pula para o útil.
                long minSeq = ring.seqBeforePts(oldestUseful);
                if (sub.lastSentSeq < minSeq) sub.lastSentSeq = minSeq;
                while (true) {
                    frames.clear();
                    int n = ring.collect(sub.lastSentSeq, now + SEND_LEAD_MS, MAX_FRAMES_PER_PACKET, frames);
                    if (n == 0) break;
                    FmNetwork.sendTo(new S2CAudio(sub.station.id, frames), player);
                    sub.lastSentSeq = frames.get(n - 1).seq;
                    long bytes = 13;
                    for (FrameRing.Frame f : frames) bytes += f.data.length + 3;
                    BYTES_SENT.merge(e.getKey(), bytes, Long::sum);
                    if (n < MAX_FRAMES_PER_PACKET) break;
                }
            }
        }
    }

    private static void updateRadioStatus(long now) {
        PlaylistService.beginCycle();
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) continue;
            for (TileRadio r : ServerRadioRegistry.inDimension(world.provider.dimensionId)) {
                if (!relayed(r)) continue;
                PlaylistService.keep(r);
                Station s = HUB.get(r.state.effectiveUrl());
                if (s == null) continue;
                // Com playlist, o fim da faixa não é "terminou": o fim ainda está soando e a próxima vem.
                String status = PlaylistService.willAdvance(r, s) ? "" : statusFor(s);
                boolean changed = false;
                if (!status.equals(r.state.status)) {
                    r.state.status = status;
                    changed = true;
                }
                String title = TextSanitizer.clean(s.streamTitle(), RadioLimits.MAX_TITLE_LENGTH);
                if (!title.equals(r.state.nowPlaying)) {
                    Long last = TITLE_UPDATED_MS.get(r.pos());
                    if (last == null || now - last >= TITLE_INTERVAL_MS) {
                        r.state.nowPlaying = title;
                        TITLE_UPDATED_MS.put(r.pos(), now);
                        changed = true;
                    }
                }
                if (PlaylistService.update(r, s, now, latencyMs())) changed = true;
                if (changed) r.markStateChanged();
            }
        }
        PlaylistService.endCycle();
        if (TITLE_UPDATED_MS.size() > 4096) TITLE_UPDATED_MS.clear();
    }

    /** Status da estação como chave de tradução (com argumento depois de '|'), ou vazio quando está tudo bem. */
    static String statusFor(Station s) {
        StreamPump.Status st = s.status();
        switch (st) {
            case RECONNECTING:
                return "akashicfm.status.reconnecting";
            case ERROR:
                return "akashicfm.status.error|" + TextSanitizer.clean(s.detail(), 64);
            case ENDED:
                return "akashicfm.status.ended";
            default:
                return "";
        }
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

    // ---- Usado pela escolha de transporte e pelas ações ----

    /** A URL pode usar o relay sem passar do limite de estações (conta as URLs já tocando em RELAY). */
    public static boolean canRelay(String url) {
        if (HUB.has(url)) return true;
        Set<String> urls = new HashSet<>();
        for (TileRadio r : ServerRadioRegistry.snapshot()) if (relayed(r)) urls.add(r.state.effectiveUrl());
        urls.addAll(PortableSources.relayedUrls());
        for (Station s : HUB.all()) urls.add(s.url);
        return urls.contains(url) || urls.size() < Math.max(1, FmConfig.Relay.maxStations);
    }

    /** Título ICY atual da estação da URL, ou vazio se ninguém a está ouvindo pelo relay. */
    public static String titleFor(String url) {
        if (url == null || url.isEmpty()) return "";
        Station s = HUB.get(url);
        if (s == null) return "";
        String title = s.streamTitle();
        return title == null ? "" : title;
    }

    /** Status da estação da URL (reconectando, erro, fim) como chave de tradução, ou vazio. */
    public static String statusOf(String url) {
        Station s = url == null || url.isEmpty() ? null : HUB.get(url);
        return s == null ? "" : statusFor(s);
    }

    /**
     * Fecha já a estação da URL se nenhuma rádio nem portátil a toca mais pelo relay (a playlist trocou de faixa: a
     * vaga do limite de estações volta na hora, sem esperar o prazo de expiração).
     */
    static void releaseIfUnused(String url) {
        if (url == null || url.isEmpty() || HUB.get(url) == null) return;
        for (TileRadio r : ServerRadioRegistry.snapshot()) if (relayed(r) && url.equals(r.state.effectiveUrl())) return;
        if (PortableSources.relayedUrls()
            .contains(url)) return;
        HUB.restart(url);
    }

    /** A estação da URL terminou com erro ou fim: um novo "tocar" tenta de novo com outra conexão. */
    public static boolean retryIfFailed(String url) {
        Station s = HUB.get(url);
        if (s == null || !s.failed()) return false;
        HUB.restart(url);
        AkashicFM.LOG.info("Relay: nova tentativa para {}", url);
        return true;
    }

    /** Diagnóstico: bytes de áudio enviados ao jogador desde o início do servidor. */
    public static long bytesSentTo(UUID player) {
        Long b = BYTES_SENT.get(player);
        return b == null ? 0 : b;
    }

    public static int stationCount() {
        return HUB.size();
    }

    public static int listenerCount() {
        int n = 0;
        for (Map<Integer, Subscription> m : SUBS.values()) n += m.size();
        return n;
    }

    /** O cliente disse se está ouvindo (rede: thread-safe). Sem aviso, ouve. */
    public static void setListening(UUID player, boolean listening) {
        if (player == null) return;
        if (listening) NOT_LISTENING.remove(player);
        else NOT_LISTENING.add(player);
    }

    public static boolean isListening(UUID player) {
        return !NOT_LISTENING.contains(player);
    }

    public static void forget(UUID player) {
        NOT_LISTENING.remove(player);
        SUBS.remove(player);
        PING_LIMITER.forget(player);
    }

    /** Manda "parar" de todas as estações para cada ouvinte conectado (os clientes fecham os decoders). */
    private static void notifyStopAll() {
        Map<UUID, EntityPlayerMP> online = onlinePlayers();
        for (Map.Entry<UUID, Map<Integer, Subscription>> e : SUBS.entrySet()) {
            EntityPlayerMP player = online.get(e.getKey());
            if (player == null) continue;
            for (Integer id : e.getValue()
                .keySet()) FmNetwork.sendTo(S2CListen.stop(id), player);
        }
    }

    public static void shutdown() {
        HUB.closeAll();
        SUBS.clear();
        BYTES_SENT.clear();
        TITLE_UPDATED_MS.clear();
        PlaylistService.clear();
        NOT_LISTENING.clear();
        PINGS.clear();
        PENDING_PINGS.set(0);
        PING_LIMITER.clear();
        tickCounter = 0;
    }
}
