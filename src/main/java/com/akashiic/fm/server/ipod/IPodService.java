package com.akashiic.fm.server.ipod;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.stream.MediaLocator;
import com.akashiic.fm.audio.stream.StreamPump;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.content.ItemIPod;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CIPodStatus;
import com.akashiic.fm.server.AuditLog;
import com.akashiic.fm.server.Moderation;
import com.akashiic.fm.server.relay.RelayService;

/**
 * Os iPods tocando. Thread principal do servidor, a cada {@link #INTERVAL_TICKS}; o trabalho lento (yt-dlp, Spotify)
 * vai para um pool próprio e o resultado é conferido aqui ({@link Future#isDone()}), sem callback em outra thread.
 * <p>
 * Para cada jogador, o iPod que toca é o primeiro aparelho ligado do inventário (o mesmo critério do
 * {@code PortableSources}, que decide quem ouve). Por faixa:
 * <ol>
 * <li>resolve a URL do áudio ({@link MediaResolver#locate}: SoundCloud direto, YouTube e Spotify pelo espelho);</li>
 * <li>abre a estação do relay com a chave do iPod ({@code ipod:<id>}): quem está perto ouve sincronizado, como numa
 * rádio; com fone, só o dono;</li>
 * <li>no fim (o último áudio soou nos clientes), avança pela fila com a repetição; uma faixa que falha é pulada (com o
 * motivo na tela) e várias falhas seguidas param o iPod;</li>
 * <li>a próxima faixa é resolvida no último minuto da atual (as URLs dos CDNs expiram em minutos: nada antes
 * disso);</li>
 * <li>pausa: a estação guarda o que não foi enviado e o download para; depois de {@code ipod.pauseTimeoutMinutes}, o
 * iPod desliga.</li>
 * </ol>
 * O estado do item (fila, faixa, pausa) fica no NBT ({@link IPodState}); aqui só o que está tocando agora.
 */
public final class IPodService {

    static final int INTERVAL_TICKS = 5;
    /** Status para o dono (posição e motivo), a cada tantos ciclos. */
    static final int STATUS_CYCLES = 2;
    static final long PREFETCH_BEFORE_END_MS = 60_000;
    /** Depois de uma falha, o motivo fica na tela este tempo antes de pular para a próxima. */
    static final long SKIP_DELAY_MS = 2_500;
    static final int MAX_FAILURES_IN_ROW = 5;
    /** Confere as ferramentas (instalação, atualização diária) a cada tantos ciclos. */
    static final int TOOLS_CYCLES = 80;
    static final int POOL_THREADS = 4;
    static final int POOL_QUEUE = 32;
    /** "Anterior" com mais que isto tocado volta para o começo da faixa. */
    static final long RESTART_THRESHOLD_MS = 5_000;

    public static final String STATUS_RESOLVING = "akashicfm.ipod.status.resolving";
    public static final String STATUS_PAUSED = "akashicfm.ipod.status.paused";
    public static final String STATUS_RECONNECTING = "akashicfm.status.reconnecting";
    public static final String STATUS_DISABLED = "akashicfm.ipod.err.disabled";
    public static final String STATUS_NO_RELAY = "akashicfm.ipod.err.no_relay";
    public static final String STATUS_RELAY_FULL = "akashicfm.ipod.err.relay_full";
    public static final String STATUS_BUSY = "akashicfm.ipod.err.busy";

    /** Uma faixa sendo resolvida (a atual ou a próxima, adiantada). */
    private static final class Resolve {

        final String link;
        final Future<MediaResolver.Located> future;

        Resolve(String link, Future<MediaResolver.Located> future) {
            this.link = link;
            this.future = future;
        }
    }

    /** O que um iPod está tocando agora. */
    static final class Session {

        final String key;
        final long id;
        int session = Integer.MIN_VALUE;
        String link = "";
        Resolve current;
        Resolve next;
        MediaResolver.Located located;
        boolean open;
        String status = "";
        int failures;
        /** Quando a faixa falhou (-1: não falhou): pula depois de {@link #SKIP_DELAY_MS}. */
        long failedAtMs = -1;
        long pausedSinceMs = -1;
        IPodTrack shown;
        long positionMs;
        int cycles;

        Session(String key, long id) {
            this.key = key;
            this.id = id;
        }

        S2CIPodStatus.Phase phase(IPodState s) {
            if (!s.on) return S2CIPodStatus.Phase.STOPPED;
            if (failedAtMs >= 0) return S2CIPodStatus.Phase.ERROR;
            if (s.paused) return S2CIPodStatus.Phase.PAUSED;
            return open ? S2CIPodStatus.Phase.PLAYING : S2CIPodStatus.Phase.RESOLVING;
        }
    }

    /** Uma adição pendente (o link ou a busca sendo expandido). */
    private static final class PendingAdd {

        final UUID player;
        final IPodTarget target;
        final String input;
        final Future<YtDlpJson.Listing> future;

        PendingAdd(UUID player, IPodTarget target, String input, Future<YtDlpJson.Listing> future) {
            this.player = player;
            this.target = target;
            this.input = input;
            this.future = future;
        }
    }

    /**
     * Pela chave da estação do host: a do item é a do jogador (só um aparelho toca por jogador). Não pela identidade
     * do item: copiar um iPod no criativo copia a identidade, e duas cópias tocando em jogadores diferentes não podem
     * dividir a sessão nem a estação.
     */
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    /** Uma adição por vez por jogador. */
    private static final Map<UUID, PendingAdd> ADDS = new HashMap<>();
    private static ToolManager tools;
    /** Lido também pelas threads das estações (renovação da URL). */
    private static volatile MediaResolver resolver;
    private static ThreadPoolExecutor pool;
    private static int ticks;

    private IPodService() {}

    /** A chave da estação: pelo dono, única mesmo com identidades de item repetidas. */
    static String keyOf(UUID owner) {
        return "ipod:" + owner.toString()
            .replace("-", "");
    }

    /** A sessão da chave, se ela é deste aparelho. */
    private static Session sessionFor(String key, long id) {
        Session ses = key == null ? null : SESSIONS.get(key);
        return ses != null && ses.id == id ? ses : null;
    }

    private static Session sessionFor(UUID owner, long itemId) {
        return owner == null ? null : sessionFor(keyOf(owner), itemId);
    }

    // ---- Ciclo ----

    public static void tick() {
        if (++ticks % INTERVAL_TICKS != 0) return;
        if (!FmConfig.IPod.enabled) {
            if (!SESSIONS.isEmpty() || !ADDS.isEmpty()) stopAll();
            return;
        }
        if (ticks % (INTERVAL_TICKS * TOOLS_CYCLES) == INTERVAL_TICKS) ensureTools();
        finishAdds();
        IPodSearch.finish();
        Set<String> alive = new HashSet<>();
        for (IPodHost host : hosts()) {
            Session ses = SESSIONS.get(host.key());
            if (ses == null || ses.id != host.id()) { // outro aparelho na mesma chave: começa de novo
                if (ses != null) close(ses);
                ses = new Session(host.key(), host.id());
                SESSIONS.put(host.key(), ses);
            }
            alive.add(host.key());
            update(host, ses);
        }
        Iterator<Session> it = SESSIONS.values()
            .iterator();
        while (it.hasNext()) {
            Session ses = it.next();
            if (!alive.contains(ses.key)) {
                close(ses);
                it.remove();
            }
        }
    }

    /** Os iPods que tocam neste ciclo. */
    private static List<IPodHost> hosts() {
        List<IPodHost> out = new ArrayList<>();
        for (EntityPlayerMP p : onlinePlayers()) {
            // Morto ou bloqueado por um admin: mudo no PortableSources, e aqui nada resolve nem toca.
            if (p.isDead || p.getHealth() <= 0 || Moderation.isBlocked(p)) continue;
            ItemStack st = ItemIPod.activeStack(p);
            if (st == null) continue;
            IPodState s = ItemIPod.state(st);
            if (s.id == 0) continue; // a identidade chega no próximo onUpdate do item
            out.add(new ItemHost(p, st, s));
        }
        return out;
    }

    private static void update(IPodHost host, Session ses) {
        IPodState s = host.state();
        long now = RelayService.nowMs();
        IPodTrack cur = s.current();
        if (cur == null) return;
        if (!FmConfig.Relay.enabled) {
            // Relay desligado (no config ou pelo /fm reload): nada toca. Religado, a faixa recomeça (sessão zerada).
            close(ses);
            ses.session = Integer.MIN_VALUE;
            ses.status = STATUS_NO_RELAY;
            sendStatus(host, ses);
            return;
        }
        if (s.session != ses.session || !cur.link.equals(ses.link)) startTrack(ses, s, cur);

        // Pausa (antes de abrir a estação também: ela já abre pausada).
        if (s.paused) {
            if (ses.pausedSinceMs < 0) ses.pausedSinceMs = now;
            if (now - ses.pausedSinceMs > FmConfig.IPod.pauseTimeoutMinutes * 60_000L) {
                s.on = false;
                s.paused = false;
                host.save();
                closeStation(ses);
                return;
            }
        } else {
            ses.pausedSinceMs = -1;
        }
        if (ses.open) RelayService.setKeyedPaused(ses.key, s.paused);

        if (ses.failedAtMs >= 0) {
            if (now - ses.failedAtMs >= SKIP_DELAY_MS) skipAfterFailure(host, ses);
        } else if (!ses.open) {
            awaitResolution(host, ses);
        } else {
            follow(host, ses, cur, now);
        }
        if (++ses.cycles % STATUS_CYCLES == 0) sendStatus(host, ses);
    }

    /** A faixa mudou (pedido do jogador ou avanço): fecha a anterior e resolve a nova (ou usa a adiantada). */
    private static void startTrack(Session ses, IPodState s, IPodTrack cur) {
        AkashicFM.LOG.debug(
            "iPod {}: faixa {} ({}){}",
            ses.key,
            s.index,
            cur.link,
            ses.next != null && ses.next.link.equals(cur.link) ? " adiantada" : "");
        closeStation(ses);
        cancel(ses.current);
        ses.current = null;
        ses.located = null;
        ses.session = s.session;
        ses.link = cur.link;
        ses.shown = cur;
        ses.positionMs = 0;
        ses.failedAtMs = -1;
        ses.status = STATUS_RESOLVING;
        if (ses.next != null && ses.next.link.equals(cur.link)) {
            ses.current = ses.next;
            ses.next = null;
        } else {
            cancel(ses.next);
            ses.next = null;
            ses.current = submitLocate(cur);
            if (ses.current == null) fail(ses, STATUS_BUSY);
        }
    }

    private static void awaitResolution(IPodHost host, Session ses) {
        if (ses.current == null || !ses.current.future.isDone()) return;
        IPodState s = host.state();
        MediaResolver.Located located;
        try {
            located = ses.current.future.get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            fail(
                ses,
                c instanceof MediaResolver.ResolveException ? ((MediaResolver.ResolveException) c).status()
                    : "akashicfm.ipod.err.failed|" + c);
            return;
        } catch (Exception e) {
            fail(ses, "akashicfm.ipod.err.failed|" + e);
            return;
        }
        ses.current = null;
        ses.located = located;
        AkashicFM.LOG.debug("iPod {}: resolvida {} -> abrindo", ses.key, located.track.link);
        // Metadados que faltavam (título de item de set, duração): ficam na fila.
        if (!sameMetadata(located.track, s.current())) {
            s.queue.set(s.index, located.track);
            host.save();
        }
        ses.shown = located.track;
        if (!RelayService.openKeyed(ses.key, new TrackLocator(located))) {
            fail(ses, STATUS_RELAY_FULL);
            return;
        }
        ses.open = true;
        if (s.paused) RelayService.setKeyedPaused(ses.key, true);
        ses.status = "";
        AkashicFM.LOG.debug(
            "iPod {}: tocando {}{}",
            ses.key,
            located.track,
            located.mirror == null ? "" : " (espelho " + located.mirror.link + ")");
    }

    /** A estação está aberta: acompanha a posição, o fim, as falhas e adianta a próxima. */
    private static void follow(IPodHost host, Session ses, IPodTrack cur, long now) {
        IPodState s = host.state();
        RelayService.KeyedStatus ks = RelayService.keyedStatus(ses.key);
        if (ks == null) { // a estação sumiu (relay recarregado, limite): recomeça a faixa
            ses.open = false;
            ses.session = Integer.MIN_VALUE;
            return;
        }
        RelayService.touchKeyed(ses.key);
        ses.positionMs = ks.positionMs;
        boolean ended = ks.status == StreamPump.Status.ENDED && ks.hadAudio;
        boolean failed = ks.status == StreamPump.Status.ERROR || (ks.status == StreamPump.Status.ENDED && !ks.hadAudio);
        if (ks.status == StreamPump.Status.PLAYING && ks.hadAudio) ses.failures = 0;
        ses.status = s.paused ? STATUS_PAUSED : ks.status == StreamPump.Status.RECONNECTING ? STATUS_RECONNECTING : "";
        if (failed) {
            fail(ses, "akashicfm.ipod.err.failed|" + ks.detail);
            return;
        }
        if (ended && ks.endHeardAtMs >= 0 && now >= ks.endHeardAtMs) {
            advance(host, ses, false);
            return;
        }
        int durationSec = ses.shown != null && ses.shown.durationSec > 0 ? ses.shown.durationSec : cur.durationSec;
        if (ses.next == null && durationSec > 0 && durationSec * 1000L - ks.positionMs <= PREFETCH_BEFORE_END_MS) {
            int ni = s.nextIndex(false);
            if (ni >= 0 && ni != s.index) ses.next = submitLocate(s.queue.get(ni));
        }
    }

    /** Pula para a próxima depois de uma falha; falhas demais seguidas desligam o iPod (o motivo fica). */
    private static void skipAfterFailure(IPodHost host, Session ses) {
        IPodState s = host.state();
        ses.failures++;
        if (ses.failures >= Math.min(MAX_FAILURES_IN_ROW, s.queue.size())) {
            s.on = false;
            s.paused = false;
            host.save();
            closeStation(ses);
            return;
        }
        advance(host, ses, false);
    }

    /** Vai para a próxima pela repetição; no fim da fila, desliga. */
    private static void advance(IPodHost host, Session ses, boolean manual) {
        IPodState s = host.state();
        int next = s.nextIndex(manual);
        AkashicFM.LOG.debug("iPod {}: avança de {} para {}", ses.key, s.index, next);
        if (next < 0) {
            s.on = false;
            s.paused = false;
            s.index = 0;
            closeStation(ses);
        } else {
            s.index = next;
            s.session++;
        }
        host.save();
    }

    private static void fail(Session ses, String status) {
        AkashicFM.LOG.info("iPod {}: {} falhou: {}", ses.key, ses.link, status);
        closeStation(ses);
        ses.status = status;
        ses.failedAtMs = RelayService.nowMs();
    }

    private static boolean sameMetadata(IPodTrack a, IPodTrack b) {
        return b != null && a.link.equals(b.link)
            && a.title.equals(b.title)
            && a.artist.equals(b.artist)
            && a.durationSec == b.durationSec;
    }

    // ---- Ações (chamadas pelo IPodActionHandler, thread principal) ----

    /** Posição da faixa atual do iPod (0 se não está tocando). */
    static long positionMs(String key, long id) {
        Session ses = sessionFor(key, id);
        return ses == null || !ses.open ? 0 : ses.positionMs;
    }

    /**
     * Expande um link ou busca para a fila do iPod (em segundo plano). Devolve a chave do aviso de recusa, ou null se
     * começou.
     */
    static String add(EntityPlayerMP p, IPodTarget target, String input) {
        if (!FmConfig.IPod.enabled) return STATUS_DISABLED;
        UUID id = p.getUniqueID();
        PendingAdd pending = ADDS.get(id);
        if (pending != null && !pending.future.isDone()) return "akashicfm.ipod.notice.adding";
        YtDlp.Kind kind = YtDlp.classify(input);
        if (kind == YtDlp.Kind.INVALID) return "akashicfm.ipod.err.invalid";
        if (kind == YtDlp.Kind.SPOTIFY && !FmConfig.IPod.spotify) return "akashicfm.ipod.err.spotify_off";
        String tool = toolsRefusal();
        if (tool != null) return tool;
        Future<YtDlpJson.Listing> f = submit(() -> resolver.expand(input, Math.max(1, FmConfig.IPod.maxQueue)));
        if (f == null) return STATUS_BUSY;
        ADDS.put(id, new PendingAdd(id, target, input, f));
        AuditLog.log(p.getCommandSenderName(), id, "ipod.add", input);
        return null;
    }

    /** As adições que terminaram: entram na fila do iPod (se ele ainda está com o jogador). */
    private static void finishAdds() {
        if (ADDS.isEmpty()) return;
        Map<UUID, EntityPlayerMP> online = new HashMap<>();
        for (EntityPlayerMP p : onlinePlayers()) online.put(p.getUniqueID(), p);
        Iterator<PendingAdd> it = ADDS.values()
            .iterator();
        while (it.hasNext()) {
            PendingAdd a = it.next();
            if (!a.future.isDone()) continue;
            it.remove();
            EntityPlayerMP p = online.get(a.player);
            if (p == null) continue;
            YtDlpJson.Listing listing;
            try {
                listing = a.future.get();
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                String status = c instanceof MediaResolver.ResolveException
                    ? ((MediaResolver.ResolveException) c).status()
                    : "akashicfm.ipod.err.failed|" + c;
                a.target.notice(p, true, status);
                continue;
            } catch (Exception e) {
                continue;
            }
            IPodTarget.Binding b = a.target.bind(p);
            if (b == null) continue; // o iPod saiu do alcance (do inventário)
            IPodState s = b.state;
            boolean wasEmpty = s.queue.isEmpty();
            int first = s.queue.size();
            int added = s.append(listing.tracks, FmConfig.IPod.maxQueue);
            if (added == 0) {
                a.target.notice(p, true, "akashicfm.ipod.notice.queue_full");
                continue;
            }
            // Fila parada: começa pela primeira que entrou.
            if (!s.on) s.play(wasEmpty ? 0 : first);
            b.save.run();
            int left = listing.tracks.size() - added;
            a.target.notice(
                p,
                false,
                left > 0 ? "akashicfm.ipod.notice.added_partial|" + added + "|" + left
                    : "akashicfm.ipod.notice.added|" + added);
        }
    }

    // ---- Para o PortableSources (quem ouve) e a tela ----

    /** A chave da estação, se o iPod está tocando agora (senão vazio: nada a ouvir). */
    public static String stationKey(UUID owner, long itemId) {
        Session ses = sessionFor(owner, itemId);
        return ses != null && ses.open ? ses.key : "";
    }

    /** O que mostrar: a faixa atual. */
    public static String title(UUID owner, IPodState s) {
        Session ses = sessionFor(owner, s.id);
        IPodTrack t = ses != null && ses.shown != null ? ses.shown : s.current();
        return t == null ? "" : t.display();
    }

    /** O motivo de não estar tocando (chave de tradução, com argumento depois de '|'), ou vazio. */
    public static String status(UUID owner, long itemId) {
        if (!FmConfig.IPod.enabled) return STATUS_DISABLED;
        Session ses = sessionFor(owner, itemId);
        return ses == null ? STATUS_RESOLVING : ses.status;
    }

    private static void sendStatus(IPodHost host, Session ses) {
        IPodState s = host.state();
        IPodTrack t = ses.shown != null ? ses.shown : s.current();
        host.sendStatus(
            new S2CIPodStatus(
                ses.id,
                ses.phase(s),
                ses.positionMs,
                t == null ? 0 : t.durationSec * 1000L,
                s.index,
                ses.status));
    }

    // ---- Ferramentas e pool ----

    /** Null se o yt-dlp está pronto; senão a chave do aviso (falhou, ainda instalando). */
    static String toolsRefusal() {
        ensureTools();
        if (tools.state() == ToolManager.State.FAILED) return "akashicfm.ipod.err.tools";
        if (tools.ytDlp() == null) return "akashicfm.ipod.notice.installing";
        return null;
    }

    /** O resolvedor (depois de {@link #toolsRefusal()}). */
    static MediaResolver resolver() {
        return resolver;
    }

    private static void ensureTools() {
        if (tools == null) {
            MinecraftServer server = MinecraftServer.getServer();
            File dir = server != null ? server.getFile("akashicfm/tools") : new File("akashicfm/tools");
            tools = new ToolManager(dir);
            resolver = MediaResolver.create(tools);
        }
        tools.ensure(FmConfig.IPod.ytDlpPath, FmConfig.IPod.autoInstallTools, FmConfig.IPod.youtubeDirect);
    }

    private static Resolve submitLocate(IPodTrack t) {
        ensureTools();
        Future<MediaResolver.Located> f = submit(() -> resolver.locate(t));
        return f == null ? null : new Resolve(t.link, f);
    }

    /** No pool do iPod; null se ele está cheio (o servidor está ocupado resolvendo). */
    static <T> Future<T> submit(Callable<T> task) {
        if (pool == null) {
            AtomicInteger n = new AtomicInteger();
            pool = new ThreadPoolExecutor(
                POOL_THREADS,
                POOL_THREADS,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(POOL_QUEUE),
                r -> {
                    Thread t = new Thread(r, "AkashicFM-iPod-" + n.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
            pool.allowCoreThreadTimeOut(true);
        }
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private static void cancel(Resolve r) {
        if (r != null) r.future.cancel(true); // interrompe: o yt-dlp em andamento é morto
    }

    private static void closeStation(Session ses) {
        if (ses.open) RelayService.closeKeyed(ses.key);
        ses.open = false;
        ses.positionMs = 0;
    }

    private static void close(Session ses) {
        closeStation(ses);
        cancel(ses.current);
        cancel(ses.next);
        ses.current = null;
        ses.next = null;
    }

    private static void stopAll() {
        for (Session ses : SESSIONS.values()) close(ses);
        SESSIONS.clear();
        for (PendingAdd a : ADDS.values()) a.future.cancel(true);
        ADDS.clear();
        IPodSearch.clear();
    }

    /** O jogador saiu: esquece as buscas dele. */
    public static void forget(UUID player) {
        IPodSearch.forget(player);
    }

    static List<EntityPlayerMP> onlinePlayers() {
        List<EntityPlayerMP> out = new ArrayList<>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return out;
        for (Object o : server.getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP && !(o instanceof FakePlayer)) out.add((EntityPlayerMP) o);
        }
        return out;
    }

    /** Servidor parando. */
    public static void shutdown() {
        stopAll();
        if (pool != null) pool.shutdownNow();
        pool = null;
        tools = null;
        resolver = null;
        ticks = 0;
    }

    /** O iPod item no inventário de um jogador: estado no NBT do stack, status só para o dono. */
    private static final class ItemHost implements IPodHost {

        private final EntityPlayerMP player;
        private final ItemStack stack;
        private final IPodState state;
        private final String key;

        ItemHost(EntityPlayerMP player, ItemStack stack, IPodState state) {
            this.player = player;
            this.stack = stack;
            this.state = state;
            this.key = keyOf(player.getUniqueID());
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public long id() {
            return state.id;
        }

        @Override
        public IPodState state() {
            return state;
        }

        @Override
        public void save() {
            ItemIPod.save(stack, state);
        }

        @Override
        public void sendStatus(S2CIPodStatus status) {
            FmNetwork.sendTo(status, player);
        }
    }

    /** O áudio de uma faixa para a estação: a URL resolvida e, se ela expirar, uma nova (na thread da estação). */
    static final class TrackLocator implements MediaLocator {

        private final MediaResolver.Located located;
        private volatile String url;

        TrackLocator(MediaResolver.Located located) {
            this.located = located;
            this.url = located.mediaUrl;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public String refresh() throws IOException {
            MediaResolver r = resolver;
            if (r == null) throw new IOException("iPod parado");
            url = r.refresh(located);
            return url;
        }
    }
}
