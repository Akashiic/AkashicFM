package com.akashiic.fm.dev;

import net.minecraft.block.Block;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.compat.baubles.BaublesCompat;
import com.akashiic.fm.content.FmContent;
import com.akashiic.fm.content.ItemHeadphones;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.network.ServerActionQueue;
import com.akashiic.fm.server.FmCommand;
import com.akashiic.fm.server.RadioIndex;
import com.akashiic.fm.server.ServerPolicy;
import com.akashiic.fm.server.ServerRadioRegistry;
import com.akashiic.fm.server.TransmitterIndex;
import com.akashiic.fm.server.relay.RelayService;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Lado servidor do E2E: escolhe o transporte e, a cada mensagem "e2e:..." no chat, registra no log os chunks
 * carregados e os contadores da fila de ações (é assim que o teste prova que pacotes maliciosos não carregaram
 * chunk nenhum). Também responde aos pedidos do roteiro: estatísticas do relay, chunk carregado e
 * {@code e2e:fill} (cuboide de um bloco, para as paredes e salas do teste de acústica; o 1.7.10 não tem /fill).
 */
public final class E2EServer {

    private static volatile String rconResult = "";

    public static void register() {
        applyOverrides();
        MinecraftForge.EVENT_BUS.register(new E2EServer());
        DevE2E.log("servidor pronto (transporte {})", FmConfig.Direct.enabled ? "direto" : "relay");
    }

    /** Valores do roteiro por cima do config (de novo a cada {@code /fm reload}, que relê o arquivo). */
    public static void applyOverrides() {
        boolean direct = "direct".equals(DevE2E.transport());
        FmConfig.Relay.enabled = !direct;
        FmConfig.Direct.enabled = direct;
        // Alcances curtos: o teste de antenas cabe perto da rádio, onde o chunk está carregado (view-distance 4).
        FmConfig.Transmitter.baseRange = DevE2E.TRANSMITTER_BASE_RANGE;
        FmConfig.Transmitter.rangePerAntenna = DevE2E.TRANSMITTER_RANGE_PER_ANTENNA;
        // iPod com o yt-dlp falso (respostas fixas, áudio público curto): o fluxo inteiro sem SoundCloud/YouTube.
        String fake = System.getenv("AKASHICFM_E2E_YTDLP");
        if (fake != null && !fake.isEmpty()) {
            FmConfig.IPod.enabled = true;
            FmConfig.IPod.autoInstallTools = false;
            FmConfig.IPod.ytDlpPath = fake;
            FmConfig.IPod.maxQueue = 50;
            FmConfig.IPod.maxResolves = 2;
            FmConfig.IPod.maxTrackMinutes = 20;
            FmConfig.IPod.pauseTimeoutMinutes = 10;
            // Estado do falso de uma rodada anterior (contador da URL expirada, registro): começa do zero.
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                java.io.File tmp = server.getFile("akashicfm/tools/tmp");
                new java.io.File(tmp, "fake-yt-dlp.log").delete();
                java.io.File[] state = new java.io.File(tmp, "fake-yt-dlp").listFiles();
                if (state != null) for (java.io.File f : state) f.delete();
            }
        }
    }

    /** O registro de chamadas do yt-dlp falso (em {@code }, a pasta temporária das ferramentas do iPod). */
    private static java.io.File fakeYtDlpLog() {
        return MinecraftServer.getServer()
            .getFile("akashicfm/tools/tmp/fake-yt-dlp.log");
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (event.message == null || !event.message.startsWith("e2e:")) return;
        if (event.message.startsWith("e2e:relay-stats") && event.player != null) {
            long bytes = RelayService.bytesSentTo(event.player.getUniqueID());
            DevE2E.log(
                "relay: {} recebeu {} bytes; estações={} ouvintes={}",
                event.username,
                bytes,
                RelayService.stationCount(),
                RelayService.listenerCount());
            event.player.addChatMessage(
                new ChatComponentText(
                    "e2e-result relay bytes=" + bytes
                        + " at="
                        + System.nanoTime() / 1_000_000L
                        + " stations="
                        + RelayService.stationCount()));
        }
        if (event.message.startsWith("e2e:fill ") && event.player != null) {
            fill(event.player.worldObj, event.message.split(" "));
            event.player.addChatMessage(new ChatComponentText("e2e-result fill " + event.message.substring(9)));
        }
        if (event.message.startsWith("e2e:headphones ") && event.player != null) {
            // Põe o fone no capacete ("on") ou num slot de bauble ("bauble"), ou tira dos dois ("off").
            String mode = event.message.substring("e2e:headphones ".length());
            boolean baubles = Loader.isModLoaded("Baubles|Expanded");
            boolean ok = true;
            if ("on".equals(mode)) {
                event.player.inventory.armorInventory[3] = new ItemStack(FmContent.headphones);
            } else if ("bauble".equals(mode)) {
                ok = baubles && BaublesCompat.equip(event.player, new ItemStack(FmContent.headphones));
            } else {
                event.player.inventory.armorInventory[3] = null;
                if (baubles) BaublesCompat.removeAll(event.player, ItemHeadphones.class);
            }
            event.player.inventoryContainer.detectAndSendChanges();
            DevE2E.log("fone de {}: {} ({})", event.username, mode, ok ? "ok" : "falhou");
            event.player
                .addChatMessage(new ChatComponentText("e2e-result headphones " + mode + (ok ? " ok" : " fail")));
        }
        if (event.message.startsWith("e2e:audit-tail") && event.player != null) {
            // Confere o arquivo de auditoria de verdade (o que um admin abriria).
            String text = "";
            try {
                text = new String(
                    java.nio.file.Files.readAllBytes(
                        MinecraftServer.getServer()
                            .getFile("logs/akashicfm-audit.log")
                            .toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                DevE2E.log("auditoria ilegível: {}", e.toString());
            }
            event.player.addChatMessage(
                new ChatComponentText(
                    "e2e-result audit lines=" + text.split("\n").length
                        + " url="
                        + text.contains("radio.url")
                        + " stop="
                        + text.contains("admin.stop")
                        + " block="
                        + text.contains("admin.block")
                        + " unblock="
                        + text.contains("admin.unblock")
                        + " reload="
                        + text.contains("admin.reload: ok")
                        + " purge="
                        + text.contains("admin.purge")
                        + " ipod="
                        + text.contains("ipod.add")
                        + " ipodsearch="
                        + text.contains("ipod.search")));
        }
        if (event.message.startsWith("e2e:ipod-calls") && event.player != null) {
            // Quantas vezes o iPod chamou o yt-dlp falso para cada alvo (registro do próprio script).
            java.util.List<String> lines = java.util.Collections.emptyList();
            try {
                lines = java.nio.file.Files
                    .readAllLines(fakeYtDlpLog().toPath(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                DevE2E.log("registro do yt-dlp falso ilegível: {}", e.toString());
            }
            int faixaA = 0, search = 0, songOne = 0, remix = 0, preview = 0, ytsearch = 0, songOneInfo = 0;
            for (String l : lines) {
                if (l.endsWith("media https://soundcloud.com/e2e/faixa-a")) faixaA++;
                if (l.contains(" info scsearch")) search++;
                if (l.contains(" info ytsearch")) ytsearch++;
                if (l.endsWith(" info https://soundcloud.com/e2e/song-one")) songOneInfo++;
                if (l.endsWith("media https://soundcloud.com/e2e/song-one")) songOne++;
                if (l.endsWith("media https://soundcloud.com/e2e/song-one-remix")) remix++;
                if (l.endsWith("media https://soundcloud.com/e2e/song-one-preview")) preview++;
            }
            String r = "faixa-a=" + faixaA
                + " search="
                + search
                + " song-one="
                + songOne
                + " remix="
                + remix
                + " preview="
                + preview
                + " ytsearch="
                + ytsearch
                + " song-one-info="
                + songOneInfo
                + " .";
            DevE2E.log("ipod: chamadas do yt-dlp falso: {}", r);
            event.player.addChatMessage(new ChatComponentText("e2e-result ipod-calls " + r));
        }
        if (event.message.startsWith("e2e:relay ") && event.player != null) {
            // O que o /fm reload faz ao ler relay.enabled (no E2E o arquivo não decide o transporte).
            boolean on = event.message.endsWith(" on");
            FmConfig.Relay.enabled = on;
            ServerPolicy.setRelayAvailable(on);
            DevE2E.log("relay {}", on ? "religado" : "desligado");
            event.player.addChatMessage(new ChatComponentText("e2e-result relay-toggle " + (on ? "on" : "off")));
        }
        if (event.message.startsWith("e2e:oc") && event.player != null) {
            // Componentes do OpenComputers chamados pelo próprio OC (só com ele instalado).
            boolean priv = event.message.startsWith("e2e:oc-private ");
            String[] a = event.message.substring(event.message.indexOf(' ') + 1)
                .split(" ");
            String r = !Loader.isModLoaded("OpenComputers") ? "semoc"
                : priv ? E2EOc.callPrivate(event.player.worldObj, a, event.player.getUniqueID())
                    : E2EOc.call(event.player.worldObj, a);
            DevE2E.log("oc: {} -> {}", event.message, r);
            event.player.addChatMessage(new ChatComponentText("e2e-result oc " + r));
        }
        if (event.message.startsWith("e2e:rcon ") && event.player != null) {
            // O RCON de verdade (handleRConCommand). No 1.7.10 puro ele roda o comando na thread do RCON; com o
            // Hodgepodge (fixRconThreading) já chega na principal. Esta thread (a principal) não espera.
            String cmd = event.message.substring("e2e:rcon ".length());
            offThread(
                "AkashicFM-E2E-RCON",
                () -> MinecraftServer.getServer()
                    .handleRConCommand(cmd));
        }
        if (event.message.startsWith("e2e:bridge ") && event.player != null) {
            // Uma ponte de chat (Discord etc.) chamando executeCommand da thread dela: ninguém leva para a principal.
            String cmd = event.message.substring("e2e:bridge ".length());
            offThread("AkashicFM-E2E-Bridge", () -> {
                StringBuffer out = new StringBuffer();
                MinecraftServer.getServer()
                    .getCommandManager()
                    .executeCommand(new BridgeSender(out), cmd);
                return out.toString();
            });
        }
        if (event.message.startsWith("e2e:rcon-result") && event.player != null) {
            event.player.addChatMessage(
                new ChatComponentText(
                    "e2e-result rcon queuedRuns=" + FmCommand.queuedRuns()
                        + " "
                        + (rconResult.isEmpty() ? "pending" : rconResult)));
        }
        if (event.message.startsWith("e2e:config-file ") && event.player != null) {
            // Edita o arquivo de config como um admin faria (só o valor da chave), para o /fm reload reler.
            String[] a = event.message.split(" ");
            boolean ok = a.length == 3 && editConfigFile(a[1], a[2]);
            event.player.addChatMessage(
                new ChatComponentText(
                    "e2e-result config-file " + (a.length > 1 ? a[1] : "?") + (ok ? " ok" : " fail")));
        }
        if (event.message.startsWith("e2e:config-get") && event.player != null) {
            event.player.addChatMessage(
                new ChatComponentText(
                    "e2e-result config maxPerPlayer=" + FmConfig.Transmitter.maxPerPlayer
                        + " euPerTick="
                        + FmConfig.Transmitter.euPerTick
                        + " baseRange="
                        + FmConfig.Transmitter.baseRange
                        + " relay="
                        + FmConfig.Relay.enabled
                        + " ."));
        }
        if (event.message.startsWith("e2e:index-seed") && event.player != null) {
            // Os blocos do roteiro vêm de /setblock (sem dono). Para o /fm purge player: um transmissor carregado e
            // real passa a ser deste jogador, e duas entradas dele ficam em chunk descarregado (sem bloco).
            java.util.UUID id = event.player.getUniqueID();
            TileTransmitter real = null;
            for (TransmitterIndex.Entry e : TransmitterIndex.get(event.player.worldObj)
                .all()) {
                if (!present(e.dim, e.pos.x, e.pos.y, e.pos.z, TileTransmitter.class)) continue;
                real = (TileTransmitter) DimensionManager.getWorld(e.dim)
                    .getTileEntity(e.pos.x, e.pos.y, e.pos.z);
                break;
            }
            int fx = (int) event.player.posX + 4096, fz = (int) event.player.posZ + 4096;
            boolean ok = real != null
                && !((ChunkProviderServer) event.player.worldObj.getChunkProvider()).chunkExists(fx >> 4, fz >> 4);
            if (ok) {
                real.state.owner = id;
                real.state.ownerName = event.player.getCommandSenderName();
                real.markStateChanged();
                TransmitterIndex.get(event.player.worldObj)
                    .put(
                        new TransmitterIndex.Entry(
                            0,
                            new Pos(fx, 4, fz),
                            987,
                            64,
                            "http://fantasma.invalid/",
                            "Fantasma",
                            id,
                            false,
                            false));
                RadioIndex.get(event.player.worldObj)
                    .put(0, new Pos(fx + 1, 4, fz), id);
            }
            event.player.addChatMessage(new ChatComponentText("e2e-result index-seed " + (ok ? "ok" : "fail")));
        }
        if (event.message.startsWith("e2e:index-owned") && event.player != null) {
            // Entradas do índice do jogador: todas, e as de bloco carregado e presente (que o /fm purge player mantém).
            java.util.UUID id = event.player.getUniqueID();
            int total = 0, loaded = 0;
            for (TransmitterIndex.Entry e : TransmitterIndex.get(event.player.worldObj)
                .ownedBy(id)) {
                total++;
                if (present(e.dim, e.pos.x, e.pos.y, e.pos.z, TileTransmitter.class)) loaded++;
            }
            for (RadioIndex.Entry e : RadioIndex.get(event.player.worldObj)
                .ownedBy(id)) {
                total++;
                if (present(e.dim, e.pos.x, e.pos.y, e.pos.z, TileRadio.class)) loaded++;
            }
            event.player
                .addChatMessage(new ChatComponentText("e2e-result index total=" + total + " loaded=" + loaded + " ."));
        }
        if (event.message.startsWith("e2e:check-unloaded ") && event.player != null) {
            // Responde ao cliente se o chunk da coordenada está carregado (chunkExists nunca carrega).
            String[] a = event.message.split(" ");
            int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
            boolean loaded = ((ChunkProviderServer) event.player.worldObj.getChunkProvider())
                .chunkExists(x >> 4, z >> 4);
            DevE2E.log("chunk do bloco ({}, {}) carregado={}", x, z, loaded);
            event.player.addChatMessage(new ChatComponentText("e2e-result chunk " + x + " " + z + " loaded=" + loaded));
        }
        DevE2E.log(
            "server mark from={} msg='{}' chunks={} radios={} {}",
            event.username,
            event.message,
            loadedChunks(),
            ServerRadioRegistry.snapshot()
                .size(),
            ServerActionQueue.stats());
    }

    /** e2e:fill x0 y0 z0 x1 y1 z1 modid:bloco — até 4096 blocos, notificando os clientes. */
    private static void fill(World world, String[] a) {
        int x0 = Integer.parseInt(a[1]), y0 = Integer.parseInt(a[2]), z0 = Integer.parseInt(a[3]);
        int x1 = Integer.parseInt(a[4]), y1 = Integer.parseInt(a[5]), z1 = Integer.parseInt(a[6]);
        Block block = Block.getBlockFromName(a[7]);
        if (block == null) throw new IllegalArgumentException("bloco desconhecido: " + a[7]);
        int lx = Math.min(x0, x1), hx = Math.max(x0, x1), ly = Math.max(0, Math.min(y0, y1)),
            hy = Math.min(255, Math.max(y0, y1)), lz = Math.min(z0, z1), hz = Math.max(z0, z1);
        long volume = (long) (hx - lx + 1) * (hy - ly + 1) * (hz - lz + 1);
        if (volume > 4096) throw new IllegalArgumentException("volume grande demais: " + volume);
        for (int x = lx; x <= hx; x++) for (int y = ly; y <= hy; y++) for (int z = lz; z <= hz; z++) {
            world.setBlock(x, y, z, block, 0, 3);
        }
        DevE2E.log("fill {} blocos de {} em ({},{},{})..({},{},{})", volume, a[7], lx, ly, lz, hx, hy, hz);
    }

    static int loadedChunks() {
        int total = 0;
        for (WorldServer w : DimensionManager.getWorlds()) {
            if (w != null && w.getChunkProvider() instanceof ChunkProviderServer) {
                total += ((ChunkProviderServer) w.getChunkProvider()).getLoadedChunkCount();
            }
        }
        return total;
    }

    /** Chunk carregado (sem carregar) e o bloco do tipo no lugar. */
    private static boolean present(int dim, int x, int y, int z, Class<?> type) {
        WorldServer w = DimensionManager.getWorld(dim);
        return w != null && ((ChunkProviderServer) w.getChunkProvider()).chunkExists(x >> 4, z >> 4)
            && type.isInstance(w.getTileEntity(x, y, z));
    }

    /** Troca o valor de {@code key} (primeira ocorrência, qualquer tipo: I:, B:, S:) no config/akashicfm.cfg. */
    private static boolean editConfigFile(String key, String value) {
        java.io.File f = new java.io.File(
            Loader.instance()
                .getConfigDir(),
            AkashicFM.MODID + ".cfg");
        try {
            String text = new String(
                java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^(\\s*[IBSD]:" + java.util.regex.Pattern.quote(key) + "=).*$")
                .matcher(text);
            if (!m.find()) return false;
            String edited = text.substring(0, m.start()) + m.group(1) + value + text.substring(m.end());
            java.nio.file.Files.write(f.toPath(), edited.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (java.io.IOException e) {
            DevE2E.log("config ilegível: {}", e.toString());
            return false;
        }
    }

    /** Roda {@code task} numa thread nova; o resultado fica para o {@code e2e:rcon-result}. */
    private static void offThread(String name, java.util.concurrent.Callable<String> task) {
        rconResult = "";
        Thread th = new Thread(() -> {
            try {
                rconResult = "done " + task.call()
                    .replace('\n', '|');
            } catch (Exception e) {
                rconResult = "done erro " + e;
            }
        }, name);
        th.setDaemon(true);
        th.start();
    }

    /** Quem manda o comando pela ponte: junta as respostas, com permissão de console. */
    private static final class BridgeSender implements ICommandSender {

        private final StringBuffer out;

        BridgeSender(StringBuffer out) {
            this.out = out;
        }

        @Override
        public String getCommandSenderName() {
            return "Ponte";
        }

        @Override
        public IChatComponent func_145748_c_() {
            return new ChatComponentText(getCommandSenderName());
        }

        @Override
        public void addChatMessage(IChatComponent msg) {
            out.append(msg.getUnformattedText())
                .append('\n');
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return true;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }

        @Override
        public World getEntityWorld() {
            return MinecraftServer.getServer()
                .getEntityWorld();
        }
    }
}
