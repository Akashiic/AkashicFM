package com.akashiic.fm.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;

import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.PortableState;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.ItemPortableRadio;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileTransmitter;
import com.mojang.authlib.GameProfile;

/**
 * {@code /fm}: ferramentas de admin (op). Listar e inspecionar rádios, transmissores e portáteis, parar um ou tudo,
 * recarregar o config, limpar o índice e bloquear jogadores. Toda ação que muda algo vai para o log de auditoria.
 * Nunca carrega chunk: o que está em chunk descarregado aparece pelo índice e só muda quando carregar.
 */
public final class FmCommand extends CommandBase {

    static final int PAGE_SIZE = 8;
    private static final List<String> SUBCOMMANDS = Arrays
        .asList("list", "info", "stop", "stopall", "reload", "purge", "block", "unblock", "blocked");

    @Override
    public String getCommandName() {
        return "fm";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "akashicfm.cmd.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    /** Thread principal do servidor. O RCON do 1.7.10 executa comandos na thread dele, não nesta. */
    private static volatile Thread serverThread;
    /** Comandos que chegaram por outra thread, para o próximo tick. */
    private static final Queue<Runnable> FROM_OTHER_THREADS = new ConcurrentLinkedQueue<>();
    static final long OTHER_THREAD_WAIT_MS = 10_000;
    private static final AtomicInteger QUEUED_RUNS = new AtomicInteger();

    /** Servidor iniciando (na thread dele). */
    public static void markServerThread() {
        serverThread = Thread.currentThread();
    }

    /** Começo do tick do servidor: roda os comandos que vieram de outra thread. */
    public static void runQueued() {
        serverThread = Thread.currentThread();
        for (Runnable r; (r = FROM_OTHER_THREADS.poll()) != null;) r.run();
    }

    /** Quantos comandos vindos de outra thread já rodaram na principal (diagnóstico e E2E). */
    public static int queuedRuns() {
        return QUEUED_RUNS.get();
    }

    /** Servidor parando. */
    public static void clear() {
        serverThread = null;
        FROM_OTHER_THREADS.clear();
    }

    /**
     * Tudo aqui mexe em TE, índice e log, que só a thread principal pode tocar. Vindo de outra (RCON, ponte de chat),
     * o comando vai para o próximo tick e esta thread espera o resultado (as respostas chegam ao RCON normalmente).
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        Thread main = serverThread;
        if (main == null || Thread.currentThread() == main) {
            execute(sender, args);
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        FROM_OTHER_THREADS.add(() -> {
            try {
                QUEUED_RUNS.incrementAndGet();
                execute(sender, args);
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(OTHER_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
                reply(sender, new ChatComponentTranslation("akashicfm.cmd.queued"));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return;
        }
        if (failure.get() != null) throw failure.get(); // CommandException: o CommandHandler mostra em vermelho
    }

    private void execute(ICommandSender sender, String[] args) {
        if (args.length == 0) throw new WrongUsageException("akashicfm.cmd.usage");
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "list":
                list(sender, rest);
                return;
            case "info":
                info(sender, rest);
                return;
            case "stop":
                stop(sender, rest);
                return;
            case "stopall":
                stopAll(sender);
                return;
            case "reload":
                reload(sender);
                return;
            case "purge":
                purge(sender, rest);
                return;
            case "block":
                block(sender, rest, true);
                return;
            case "unblock":
                block(sender, rest, false);
                return;
            case "blocked":
                blocked(sender);
                return;
            default:
                throw new WrongUsageException("akashicfm.cmd.usage");
        }
    }

    // ---- list ----

    private void list(ICommandSender sender, String[] args) {
        String what = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "radios";
        int page = args.length > 1 ? parseIntWithMin(sender, args[1], 1) : 1;
        List<IChatComponent> lines = new ArrayList<>();
        if (what.startsWith("radio")) {
            for (TileRadio r : ServerRadioRegistry.snapshot()) {
                if (r.isInvalid() || !r.state.playing) continue;
                lines.add(describe(r));
            }
        } else if (what.startsWith("trans")) {
            for (TransmitterIndex.Entry e : TransmitterIndex.get(overworld())
                .all()) lines.add(describe(e));
        } else if (what.startsWith("port")) {
            for (IChatComponent c : PortableSources.describeCarriers()) lines.add(c.createCopy());
        } else {
            throw new WrongUsageException("akashicfm.cmd.usage");
        }
        int pages = Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages);
        reply(sender, new ChatComponentTranslation("akashicfm.cmd.list." + listKey(what), lines.size(), page, pages));
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(lines.size(), page * PAGE_SIZE); i++) {
            reply(sender, new ChatComponentText(" ").appendSibling(lines.get(i)));
        }
    }

    private static String listKey(String what) {
        return what.startsWith("radio") ? "radios" : what.startsWith("trans") ? "transmitters" : "portables";
    }

    // As linhas vão traduzidas: o admin no jogo as vê no idioma dele, o console no do servidor.

    static IChatComponent describe(TileRadio r) {
        RadioState s = r.state;
        Object source = s.url;
        if (s.mode == TuneMode.FREQUENCY) {
            String f = Frequency.format(s.frequency);
            source = s.tunedUrl.isEmpty() ? new ChatComponentTranslation("akashicfm.cmd.fm_no_signal", f)
                : new ChatComponentTranslation("akashicfm.cmd.fm_tuned", f, s.tunedUrl);
        }
        return new ChatComponentTranslation(
            "akashicfm.cmd.radio",
            r.dimension(),
            r.pos()
                .toString(),
            source,
            s.transport.toString(),
            owner(s.ownerName));
    }

    static IChatComponent describe(TransmitterIndex.Entry e) {
        IChatComponent state = new ChatComponentTranslation(
            e.active ? "akashicfm.cmd.on_air" : "akashicfm.cmd.off_air");
        if (Moderation.isBlocked(e.owner)) state = new ChatComponentTranslation("akashicfm.cmd.owner_blocked", state);
        return new ChatComponentTranslation(
            "akashicfm.cmd.transmitter",
            e.dim,
            e.pos.toString(),
            Frequency.format(e.frequency),
            e.name.isEmpty() ? "-" : e.name,
            e.range,
            state,
            e.url);
    }

    private static Object owner(String name) {
        return name == null || name.isEmpty() ? new ChatComponentTranslation("akashicfm.cmd.no_owner") : name;
    }

    // ---- info / stop ----

    private void info(ICommandSender sender, String[] args) {
        Target t = target(sender, args);
        if (t.radio != null) {
            RadioState s = t.radio.state;
            reply(sender, gold(describe(t.radio)));
            reply(
                sender,
                new ChatComponentText(" ").appendSibling(
                    new ChatComponentTranslation(
                        "akashicfm.cmd.radio_info",
                        String.valueOf(s.playing),
                        s.volume,
                        s.range,
                        s.speakers.size(),
                        s.access.toString(),
                        s.session)));
            if (!s.nowPlaying.isEmpty()) reply(sender, new ChatComponentText(" ♪ " + s.nowPlaying));
            if (!s.status.isEmpty()) reply(sender, new ChatComponentText(" status: " + s.status));
        } else if (t.transmitter != null) {
            TransmitterState s = t.transmitter.state;
            reply(
                sender,
                gold(
                    new ChatComponentTranslation(
                        "akashicfm.cmd.transmitter_head",
                        t.transmitter.dimension(),
                        t.transmitter.pos()
                            .toString(),
                        Frequency.format(s.frequency),
                        s.name.isEmpty() ? "-" : s.name,
                        owner(s.ownerName))));
            IChatComponent line = new ChatComponentText(" ").appendSibling(
                new ChatComponentTranslation(
                    "akashicfm.cmd.transmitter_info",
                    s.url,
                    String.valueOf(s.broadcasting),
                    String.valueOf(s.active()),
                    s.antennas,
                    s.range));
            if (s.energyRequired) {
                line.appendText(" ")
                    .appendSibling(
                        new ChatComponentTranslation("akashicfm.cmd.transmitter_energy", s.energy, s.energyCapacity));
            }
            reply(sender, line);
        } else {
            throw new CommandException("akashicfm.cmd.no_target");
        }
    }

    private void stop(ICommandSender sender, String[] args) {
        Target t = target(sender, args);
        if (t.radio != null) {
            if (RadioActionHandler.applyStop(t.radio.state)) t.radio.markStateChanged();
            audit(sender, "admin.stop", "rádio dim " + t.radio.dimension() + " " + t.radio.pos());
        } else if (t.transmitter != null) {
            if (t.transmitter.state.broadcasting) {
                t.transmitter.state.broadcasting = false;
                t.transmitter.markStateChanged();
            }
            audit(sender, "admin.stop", "transmissor dim " + t.transmitter.dimension() + " " + t.transmitter.pos());
        } else {
            throw new CommandException("akashicfm.cmd.no_target");
        }
        reply(sender, new ChatComponentTranslation("akashicfm.cmd.stopped"));
    }

    /** Rádio ou transmissor nas coordenadas, ou no bloco que o jogador olha (até 8 blocos). Nunca carrega chunk. */
    private Target target(ICommandSender sender, String[] args) {
        World world;
        int x, y, z;
        if (args.length >= 3) {
            world = sender.getEntityWorld();
            EntityPlayerMP p = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
            // O parser do vanilla soma 0,5 a coordenada inteira (centro do bloco): floor, nunca (int), que erraria
            // o bloco em coordenada negativa.
            x = blockCoord(func_110666_a(sender, p == null ? 0 : p.posX, args[0]));
            y = blockCoord(func_110666_a(sender, p == null ? 0 : p.posY, args[1]));
            z = blockCoord(func_110666_a(sender, p == null ? 0 : p.posZ, args[2]));
        } else if (sender instanceof EntityPlayerMP) {
            EntityPlayerMP p = (EntityPlayerMP) sender;
            world = p.worldObj;
            Vec3 eye = Vec3.createVectorHelper(p.posX, p.posY + p.getEyeHeight(), p.posZ);
            Vec3 look = p.getLook(1f);
            Vec3 end = eye.addVector(look.xCoord * 8, look.yCoord * 8, look.zCoord * 8);
            MovingObjectPosition hit = world.rayTraceBlocks(eye, end);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                throw new CommandException("akashicfm.cmd.no_target");
            }
            x = hit.blockX;
            y = hit.blockY;
            z = hit.blockZ;
        } else {
            throw new WrongUsageException("akashicfm.cmd.usage");
        }
        if (world == null || y < 0 || y > 255 || !world.blockExists(x, y, z)) {
            throw new CommandException("akashicfm.cmd.not_loaded");
        }
        TileEntity te = world.getTileEntity(x, y, z);
        Target t = new Target();
        if (te instanceof TileRadio) t.radio = (TileRadio) te;
        else if (te instanceof TileTransmitter) t.transmitter = (TileTransmitter) te;
        return t;
    }

    /** Coordenada de bloco a partir da do parser (que já vem com +0,5 nos inteiros). */
    static int blockCoord(double parsed) {
        return MathHelper.floor_double(parsed);
    }

    private static final class Target {

        TileRadio radio;
        TileTransmitter transmitter;
    }

    // ---- stopall ----

    private void stopAll(ICommandSender sender) {
        int radios = 0, transmitters = 0, portables = 0;
        for (TileRadio r : ServerRadioRegistry.snapshot()) {
            if (r.isInvalid() || !RadioActionHandler.applyStop(r.state)) continue;
            r.markStateChanged();
            radios++;
        }
        for (TileTransmitter t : loadedTransmitters(null)) {
            if (!t.state.broadcasting) continue;
            t.state.broadcasting = false;
            t.markStateChanged();
            transmitters++;
        }
        for (EntityPlayerMP p : onlinePlayers()) portables += turnOffPortables(p);
        audit(
            sender,
            "admin.stopall",
            radios + " rádios, " + transmitters + " transmissores, " + portables + " portáteis");
        reply(sender, new ChatComponentTranslation("akashicfm.cmd.stopall", radios, transmitters, portables));
    }

    /** Transmissores com chunk carregado (de um dono, ou todos com {@code owner} null), pelo índice. */
    static List<TileTransmitter> loadedTransmitters(UUID owner) {
        List<TileTransmitter> out = new ArrayList<>();
        World overworld = overworld();
        if (overworld == null) return out;
        for (TransmitterIndex.Entry e : TransmitterIndex.get(overworld)
            .all()) {
            if (owner != null && !owner.equals(e.owner)) continue;
            WorldServer w = DimensionManager.getWorld(e.dim);
            if (w == null || !chunkLoaded(w, e.pos)) continue;
            TileEntity te = w.getTileEntity(e.pos.x, e.pos.y, e.pos.z);
            if (te instanceof TileTransmitter) out.add((TileTransmitter) te);
        }
        return out;
    }

    private static int turnOffPortables(EntityPlayerMP p) {
        int n = 0;
        for (int slot = 0; slot < PortableActionHandler.SLOTS; slot++) {
            ItemStack st = p.inventory.mainInventory[slot];
            if (st == null || !(st.getItem() instanceof ItemPortableRadio)) continue;
            PortableState s = ItemPortableRadio.state(st);
            if (!s.on) continue;
            s.on = false;
            ItemPortableRadio.save(st, s);
            n++;
        }
        return n;
    }

    // ---- reload ----

    private void reload(ICommandSender sender) {
        List<String> failed = ConfigReload.reloadServerConfig();
        audit(sender, "admin.reload", failed.isEmpty() ? "ok" : "falhou: " + failed);
        if (failed.isEmpty()) reply(sender, new ChatComponentTranslation("akashicfm.cmd.reloaded"));
        else reply(sender, new ChatComponentTranslation("akashicfm.cmd.reload_failed", String.join(", ", failed)));
    }

    // ---- purge ----

    private void purge(ICommandSender sender, String[] args) {
        if (args.length >= 2 && "player".equalsIgnoreCase(args[0])) {
            UUID id = resolvePlayer(sender, args[1]).getId();
            int t = removeOwned(id);
            audit(sender, "admin.purge", "entradas de " + args[1] + " (" + id + "): " + t);
            reply(sender, new ChatComponentTranslation("akashicfm.cmd.purged_player", t, args[1]));
            return;
        }
        int transmitters = 0, radios = 0;
        World overworld = overworld();
        TransmitterIndex ti = TransmitterIndex.get(overworld);
        for (TransmitterIndex.Entry e : ti.all()) {
            WorldServer w = DimensionManager.getWorld(e.dim);
            if (w == null || !chunkLoaded(w, e.pos)) continue; // descarregado: não dá para saber sem carregar
            if (!(w.getTileEntity(e.pos.x, e.pos.y, e.pos.z) instanceof TileTransmitter)) {
                ti.remove(e.dim, e.pos);
                transmitters++;
            }
        }
        RadioIndex ri = RadioIndex.get(overworld);
        for (RadioIndex.Entry e : ri.all()) {
            WorldServer w = DimensionManager.getWorld(e.dim);
            if (w == null || !chunkLoaded(w, e.pos)) continue;
            if (!(w.getTileEntity(e.pos.x, e.pos.y, e.pos.z) instanceof TileRadio)) {
                ri.remove(e.dim, e.pos);
                radios++;
            }
        }
        audit(sender, "admin.purge", transmitters + " transmissores, " + radios + " rádios sem bloco");
        reply(sender, new ChatComponentTranslation("akashicfm.cmd.purged", transmitters, radios));
    }

    /**
     * Esquece as entradas do jogador que não dá para confirmar: sem bloco, ou em chunk descarregado (o bloco que
     * existir volta ao índice quando o chunk carregar: a rádio no carregamento, o transmissor no primeiro tick). As de
     * bloco carregado e presente ficam: tirar um transmissor carregado do índice o deixaria fora do ar sem aviso.
     */
    private static int removeOwned(UUID id) {
        World overworld = overworld();
        int n = 0;
        TransmitterIndex ti = TransmitterIndex.get(overworld);
        for (TransmitterIndex.Entry e : ti.ownedBy(id)) {
            if (present(e.dim, e.pos, TileTransmitter.class)) continue;
            ti.remove(e.dim, e.pos);
            n++;
        }
        RadioIndex ri = RadioIndex.get(overworld);
        for (RadioIndex.Entry e : ri.ownedBy(id)) {
            if (present(e.dim, e.pos, TileRadio.class)) continue;
            ri.remove(e.dim, e.pos);
            n++;
        }
        return n;
    }

    /** Chunk carregado e o bloco do tipo no lugar. */
    private static boolean present(int dim, Pos pos, Class<? extends TileEntity> type) {
        WorldServer w = DimensionManager.getWorld(dim);
        return w != null && chunkLoaded(w, pos) && type.isInstance(w.getTileEntity(pos.x, pos.y, pos.z));
    }

    // ---- block / unblock / blocked ----

    private void block(ICommandSender sender, String[] args, boolean block) {
        if (args.length < 1) throw new WrongUsageException("akashicfm.cmd.usage");
        Moderation m = Moderation.get();
        if (m == null) return;
        UUID id;
        String name = args[0];
        if (block) {
            GameProfile profile = resolvePlayer(sender, name);
            id = profile.getId();
            name = profile.getName();
            if (!m.block(id, name)) {
                reply(sender, new ChatComponentTranslation("akashicfm.cmd.already_blocked", name));
                return;
            }
            silence(id);
        } else {
            id = m.byName(name);
            if (id == null) id = resolvePlayer(sender, name).getId();
            if (!m.unblock(id)) {
                reply(sender, new ChatComponentTranslation("akashicfm.cmd.not_blocked", name));
                return;
            }
        }
        audit(sender, block ? "admin.block" : "admin.unblock", name + " (" + id + ")");
        reply(sender, new ChatComponentTranslation(block ? "akashicfm.cmd.blocked" : "akashicfm.cmd.unblocked", name));
    }

    /** Bloqueado agora: para as rádios e transmissores carregados que são dele (o resto o serviço já ignora). */
    private static void silence(UUID id) {
        for (TileRadio r : ServerRadioRegistry.snapshot()) {
            if (r.isInvalid() || !id.equals(r.state.owner) || !RadioActionHandler.applyStop(r.state)) continue;
            r.markStateChanged();
        }
        for (TileTransmitter t : loadedTransmitters(id)) {
            if (!t.state.broadcasting) continue;
            t.state.broadcasting = false;
            t.markStateChanged();
        }
    }

    private void blocked(ICommandSender sender) {
        Moderation m = Moderation.get();
        Map<UUID, String> b = m == null ? java.util.Collections.emptyMap() : m.blocked();
        reply(sender, new ChatComponentTranslation("akashicfm.cmd.blocked_list", b.size()));
        for (Map.Entry<UUID, String> e : b.entrySet()) {
            reply(sender, new ChatComponentText(" " + e.getValue() + " (" + e.getKey() + ")"));
        }
    }

    /** Perfil do jogador pelo nome: conectado, ou do cache de perfis do servidor (quem já entrou um dia). */
    private static GameProfile resolvePlayer(ICommandSender sender, String name) {
        MinecraftServer server = MinecraftServer.getServer();
        EntityPlayerMP online = server.getConfigurationManager()
            .func_152612_a(name);
        if (online != null) return online.getGameProfile();
        GameProfile cached = server.func_152358_ax()
            .func_152655_a(name);
        if (cached == null || cached.getId() == null) {
            throw new CommandException("akashicfm.cmd.unknown_player", name);
        }
        return cached;
    }

    // ---- utilidades ----

    private static void audit(ICommandSender sender, String action, String details) {
        UUID id = sender instanceof EntityPlayer ? ((EntityPlayer) sender).getUniqueID() : null;
        AuditLog.log(sender.getCommandSenderName(), id, action, details);
    }

    private static void reply(ICommandSender sender, IChatComponent msg) {
        sender.addChatMessage(msg);
    }

    private static IChatComponent gold(IChatComponent c) {
        c.getChatStyle()
            .setColor(EnumChatFormatting.GOLD);
        return c;
    }

    static World overworld() {
        return MinecraftServer.getServer()
            .worldServerForDimension(0);
    }

    private static boolean chunkLoaded(WorldServer w, Pos p) {
        return w.getChunkProvider() instanceof ChunkProviderServer
            && ((ChunkProviderServer) w.getChunkProvider()).chunkExists(p.x >> 4, p.z >> 4);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityPlayerMP> onlinePlayers() {
        return new ArrayList<EntityPlayerMP>(
            MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsFromIterableMatchingLastWord(args, SUBCOMMANDS);
        if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "radios", "transmitters", "portables");
        }
        if (args.length == 2 && ("block".equalsIgnoreCase(args[0]) || "unblock".equalsIgnoreCase(args[0]))) {
            return getListOfStringsMatchingLastWord(
                args,
                MinecraftServer.getServer()
                    .getAllUsernames());
        }
        if (args.length == 2 && "purge".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "player");
        }
        return null;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 1 && args.length > 0 && ("block".equalsIgnoreCase(args[0]));
    }
}
