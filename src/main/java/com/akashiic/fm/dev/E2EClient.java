package com.akashiic.fm.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import com.akashiic.fm.audio.dsp.SpectrumAnalyzer;
import com.akashiic.fm.audio.spatial.OcclusionTracer;
import com.akashiic.fm.client.ClientIPod;
import com.akashiic.fm.client.ClientMutes;
import com.akashiic.fm.client.ClientPortables;
import com.akashiic.fm.client.ClientRadioRegistry;
import com.akashiic.fm.client.MuteKeys;
import com.akashiic.fm.client.NowPlaying;
import com.akashiic.fm.client.RadioInfo;
import com.akashiic.fm.client.audio.AudioEngine;
import com.akashiic.fm.client.audio.RadioAudioController;
import com.akashiic.fm.client.gui.FmConfigGui;
import com.akashiic.fm.client.gui.GuiIPod;
import com.akashiic.fm.client.gui.GuiPortableRadio;
import com.akashiic.fm.client.gui.GuiRadio;
import com.akashiic.fm.client.gui.GuiTransmitter;
import com.akashiic.fm.client.gui.NowPlayingMessage;
import com.akashiic.fm.client.relay.ClockSync;
import com.akashiic.fm.client.relay.RelayClient;
import com.akashiic.fm.client.relay.RelayFeed;
import com.akashiic.fm.client.spatial.OcclusionField;
import com.akashiic.fm.client.spatial.RoomProbe;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.common.PortableState;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.SpeakerChannel;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.FmContent;
import com.akashiic.fm.content.ItemHeadphones;
import com.akashiic.fm.content.ItemIPod;
import com.akashiic.fm.content.ItemPortableRadio;
import com.akashiic.fm.content.ItemTuner;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.network.C2SIPodAction;
import com.akashiic.fm.network.C2SPortableAction;
import com.akashiic.fm.network.C2SRadioAction;
import com.akashiic.fm.network.C2SRadioAction.Action;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CIPodStatus;
import com.akashiic.fm.network.S2CPortableSources;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Lado cliente do E2E: um roteiro de passos executado tick a tick, com prazo em cada passo. Usa só caminhos
 * reais (comandos de chat, pacotes do mod pela rede, o controlador e a engine de áudio de verdade) e escreve
 * "[E2E] PASS/FAIL" no log. Cenários: "main" (dono/op), "main+peer" (espera o segundo cliente) e "peer".
 */
public final class E2EClient {

    public static E2EClient INSTANCE;

    private static final int TPS = 20;
    private static final String DEFAULT_URL = "https://stream.radioparadise.com/mp3-128";
    /** Faixas curtas públicas (OGG Opus, arquivos que terminam) para a playlist: 6,0 s e 3,0 s. */
    private static final String TRACK1 = "https://actions.google.com/sounds/v1/alarms/alarm_clock.ogg";
    private static final String TRACK2 = "https://actions.google.com/sounds/v1/cartoon/clang_and_wobble.ogg";
    private static final double TRACK1_SECONDS = 6.0;
    /** Segunda estação (outro transmissor), para provar a troca de fonte na sintonia. */
    private static final String DEFAULT_URL2 = "https://stream.radioparadise.com/mellow-128";

    private final String scenario;
    private final String url;
    private final String url2;
    private final List<Step> steps = new ArrayList<>();
    private int cleanupIndex;
    private int index = -1;
    private int t;
    private int pass, fail;
    private boolean finished;
    private int shutdownCountdown = -1;

    // Contexto compartilhado entre passos.
    private int rx, ry, rz;
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private final List<String> chat = new CopyOnWriteArrayList<>();
    /** Chaves de tradução das mensagens de chat recebidas (respostas do sintonizador etc.). */
    private final List<String> chatKeys = new CopyOnWriteArrayList<>();
    private int sx, sy, sz;
    /** A segunda rádio dos passos de silenciar (no corredor ao sul da primeira). */
    private int r2x, r2z;
    private volatile S2CRadioPerms lastPerms;

    private E2EClient(String scenario) {
        this.scenario = scenario;
        String u = System.getenv("AKASHICFM_E2E_URL");
        this.url = u == null || u.isEmpty() ? DEFAULT_URL : u;
        String u2 = System.getenv("AKASHICFM_E2E_URL2");
        this.url2 = u2 == null || u2.isEmpty() ? DEFAULT_URL2 : u2;
        if (scenario.startsWith("peer")) buildPeer();
        else if (scenario.startsWith("listen")) buildListen();
        else if (scenario.startsWith("soak")) buildSoak();
        else if (scenario.startsWith("acoustic")) buildAcoustic();
        else buildMain(scenario.contains("peer"));
    }

    public static void register(String scenario) {
        // Sob o Xvfb a janela pode ficar sem foco: sem isto o jogo abre o menu de pausa sozinho no meio do roteiro.
        mc().gameSettings.pauseOnLostFocus = false;
        INSTANCE = new E2EClient(scenario);
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        DevE2E.log("cliente: cenário '{}', {} passos, url {}", scenario, INSTANCE.steps.size(), INSTANCE.url);
    }

    // ---- Ganchos ----

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) advance();
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.message == null) return;
        chat.add(event.message.getUnformattedText());
        if (event.message instanceof ChatComponentTranslation) {
            chatKeys.add(((ChatComponentTranslation) event.message).getKey());
        }
    }

    public void onNotice(S2CRadioNotice notice) {
        notices.add(notice.key);
        DevE2E.log("aviso do servidor: {} '{}' (erro={})", notice.key, notice.arg, notice.error);
    }

    public void onPerms(S2CRadioPerms perms) {
        lastPerms = perms;
    }

    // ---- Motor dos passos ----

    private abstract class Step {

        final String name;
        final int timeoutTicks;

        Step(String name, int timeoutSeconds) {
            this.name = name;
            this.timeoutTicks = timeoutSeconds * TPS;
        }

        void start() {}

        /** null: continua; "": passou; outro texto: falhou, com o motivo. */
        abstract String tick(int t);
    }

    private void advance() {
        if (shutdownCountdown >= 0) {
            if (shutdownCountdown-- == 0) Minecraft.getMinecraft()
                .shutdown();
            return;
        }
        if (finished) return;
        if (index < 0) begin(0);
        Step s = steps.get(index);
        String r;
        try {
            r = s.tick(t);
        } catch (Throwable e) {
            r = "exceção: " + e;
            com.akashiic.fm.AkashicFM.LOG.error("[E2E] exceção no passo", e);
        }
        t++;
        if (r == null && t > s.timeoutTicks) r = "prazo de " + s.timeoutTicks / TPS + " s esgotado";
        if (r == null) return;
        if (r.isEmpty()) {
            pass++;
            DevE2E.log("PASS {} ({} ticks)", s.name, t);
        } else {
            fail++;
            DevE2E.log("FAIL {}: {}", s.name, r);
            if (index < cleanupIndex) { // os passos seguintes dependem deste: vai direto à limpeza
                begin(cleanupIndex);
                return;
            }
        }
        if (index + 1 >= steps.size()) {
            finished = true;
            DevE2E.log("DONE cenario={} pass={} fail={}", scenario, pass, fail);
            shutdownCountdown = 40;
            return;
        }
        begin(index + 1);
    }

    private void begin(int i) {
        index = i;
        t = 0;
        DevE2E.log("STEP {}", steps.get(i).name);
        steps.get(i)
            .start();
    }

    // ---- Utilidades ----

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    private TileRadio radio() {
        return ClientRadioRegistry.get(new Pos(rx, ry, rz));
    }

    private AudioEngine.PlaybackInfo info() {
        TileRadio r = radio();
        if (r == null) return null;
        if (r.state.transport == Transport.RELAY) {
            RelayFeed feed = RelayClient.feedForUrl(r.state.effectiveUrl());
            return feed == null ? null : AudioEngine.INSTANCE.info(RadioAudioController.relayKey(feed));
        }
        return AudioEngine.INSTANCE.info(RadioAudioController.keyFor(r));
    }

    private static boolean relayMode() {
        return !"direct".equals(DevE2E.transport());
    }

    private static Transport expectedTransport() {
        return relayMode() ? Transport.RELAY : Transport.DIRECT;
    }

    /** Respostas "e2e-result relay bytes=N at=T" recebidas do servidor: pares {bytes, ms}. */
    private List<long[]> relayResults() {
        List<long[]> out = new ArrayList<>();
        for (String line : chat) {
            int i = line.indexOf("e2e-result relay bytes=");
            if (i < 0) continue;
            String[] parts = line.substring(i)
                .split(" ");
            out.add(new long[] { Long.parseLong(parts[2].substring(6)), Long.parseLong(parts[3].substring(3)) });
        }
        return out;
    }

    private void send(Action action, int intArg, String strArg) {
        FmNetwork.sendToServer(new C2SRadioAction(rx, ry, rz, action, intArg, strArg));
    }

    private static void say(String message) {
        if (mc().thePlayer != null) mc().thePlayer.sendChatMessage(message);
    }

    private int countChat(String needle) {
        int n = 0;
        for (String line : chat) if (line.contains(needle)) n++;
        return n;
    }

    private boolean chatSaw(String needle) {
        for (String line : chat) if (line.contains(needle)) return true;
        return false;
    }

    /** Threads de áudio do mod vivas (modo direto e decoders do relay). */
    private static int liveDirectThreads() {
        int n = 0;
        for (Thread th : Thread.getAllStackTraces()
            .keySet()) {
            String name = th.getName();
            if (th.isAlive() && (name.startsWith("AkashicFM-Direct-") || name.startsWith("AkashicFM-RelayFeed-"))) n++;
        }
        return n;
    }

    /** Clique direito de verdade: o cliente manda o pacote de uso e o servidor processa (sintonizador). */
    private static void rightClick(int x, int y, int z) {
        mc().playerController.onPlayerRightClick(
            mc().thePlayer,
            mc().theWorld,
            mc().thePlayer.getHeldItem(),
            x,
            y,
            z,
            1,
            Vec3.createVectorHelper(x + 0.5, y + 1.0, z + 0.5));
    }

    /**
     * O segundo jogador nasce com a dispersão de spawn do 1.7.10 (até ~10 blocos) e não é op: o principal o
     * traz para perto da rádio, dentro do alcance de uso (8 blocos). Antes de ele entrar, o comando só falha.
     */
    private void bringPeer() {
        say("/tp Player2 " + (rx + 0.5) + " " + ry + " " + (rz - 1.5));
    }

    private static void sneak(boolean on) {
        mc().getNetHandler()
            .addToSendQueue(new C0BPacketEntityAction(mc().thePlayer, on ? 1 : 2));
    }

    private TileSpeaker speaker() {
        TileEntity te = mc().theWorld.getTileEntity(sx, sy, sz);
        return te instanceof TileSpeaker ? (TileSpeaker) te : null;
    }

    private boolean voicesAre(int expected) {
        AudioEngine.PlaybackInfo i = info();
        return i != null && i.voices == expected && alInvariantsHold();
    }

    /**
     * Sem vazamento e sem fonte parada: toda voz tem 1 fonte AL tocando e exatamente {@code POOL_SIZE} buffers, e
     * não existe objeto AL do mod fora das vozes.
     */
    private static boolean alInvariantsHold() {
        int voices = AudioEngine.INSTANCE.totalVoices();
        return AudioEngine.INSTANCE.playingSources() == voices && AudioEngine.liveSources() == voices
            && AudioEngine.liveBuffers() == voices * AudioEngine.BUFFERS_PER_VOICE;
    }

    private static String alCounters() {
        return "vozes=" + AudioEngine.INSTANCE.totalVoices()
            + " tocando="
            + AudioEngine.INSTANCE.playingSources()
            + " fontesVivas="
            + AudioEngine.liveSources()
            + " buffersVivos="
            + AudioEngine.liveBuffers()
            + " reproducoes="
            + AudioEngine.INSTANCE.activeCount()
            + " threads="
            + liveDirectThreads()
            + " efx="
            + AudioEngine.liveEfxObjects();
    }

    /** Rodada com EFX (padrão) ou do caminho só com ganho (AKASHICFM_E2E_NO_EFX=1). */
    private static boolean efxExpected() {
        return !DevE2E.forceNoEfx();
    }

    /** Com EFX: filtro + efeito + slot (3 objetos) e o reverb disponível. Sem: nenhum objeto. */
    private static boolean efxObjectsAsExpected() {
        if (!efxExpected()) return AudioEngine.liveEfxObjects() == 0 && AudioEngine.INSTANCE.efxInfo() == null;
        AudioEngine.EfxInfo e = AudioEngine.INSTANCE.efxInfo();
        return AudioEngine.liveEfxObjects() == 3 && e != null && e.reverb;
    }

    private static String fmt(float[] v) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) b.append(i == 0 ? "" : ", ")
            .append(String.format("%.3f", v[i]));
        return b.append(']')
            .toString();
    }

    private static float avg(float[] v) {
        float sum = 0;
        for (float x : v) sum += x;
        return v.length == 0 ? Float.NaN : sum / v.length;
    }

    /** Todas as vozes com oclusão em [lo, hi]. */
    private static boolean allWithin(float[] v, double lo, double hi) {
        if (v.length == 0) return false;
        for (float x : v) if (x < lo || x > hi) return false;
        return true;
    }

    private int startsMark, underrunsMark, joinsMark;

    /** Guarda os contadores da reprodução antes de mudar as caixas. */
    private void markContinuity() {
        AudioEngine.PlaybackInfo i = info();
        startsMark = i == null ? 0 : i.starts;
        underrunsMark = i == null ? 0 : i.underruns;
        joinsMark = i == null ? 0 : i.joins;
    }

    /**
     * "" se as mudanças de caixa não reiniciaram a reprodução: nenhum início novo, nenhum underrun e pelo
     * menos uma voz entrou alinhada (quando havia o que entrar). Senão, o motivo.
     */
    private String continuityBroken() {
        AudioEngine.PlaybackInfo i = info();
        if (i == null) return "reprodução sumiu";
        DevE2E.log(
            "continuidade: inícios {} -> {}, underruns {} -> {}, vozes que entraram {} -> {}",
            startsMark,
            i.starts,
            underrunsMark,
            i.underruns,
            joinsMark,
            i.joins);
        if (i.starts != startsMark) return "a reprodução reiniciou (" + startsMark + " -> " + i.starts + ")";
        if (i.underruns != underrunsMark) return "underrun durante a troca";
        return "";
    }

    /** Mesmo caminho do F3+T para o som: descarrega e recria o sound system (e o contexto OpenAL). */
    private static void reloadSoundSystem() {
        mc().getSoundHandler()
            .onResourceManagerReload(mc().getResourceManager());
    }

    private static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Captura do último frame (dev): conferir visualmente a GUI e a tela da rádio. */
    private static void screenshot(String name) {
        try {
            ScreenShotHelper
                .saveScreenshot(mc().mcDataDir, name, mc().displayWidth, mc().displayHeight, mc().getFramebuffer());
            DevE2E.log("captura salva: screenshots/{}", name);
        } catch (Throwable e) {
            DevE2E.log("captura falhou: {}", e.toString());
        }
    }

    private static boolean inWorld() {
        return mc().theWorld != null && mc().thePlayer != null;
    }

    // ---- Passos comuns ----

    private Step join() {
        return new Step("entrar-no-servidor", 900) {

            int inWorldTicks;

            @Override
            String tick(int t) {
                inWorldTicks = inWorld() ? inWorldTicks + 1 : 0;
                return inWorldTicks >= 60 ? "" : null;
            }
        };
    }

    private Step audioPlaying(String name, int timeoutSeconds) {
        return new Step(name, timeoutSeconds) {

            @Override
            String tick(int t) {
                AudioEngine.PlaybackInfo i = info();
                if (i == null) return null;
                if (i.feedStatus == com.akashiic.fm.client.audio.AudioFeed.Status.ERROR && i.done) {
                    return "stream com erro: " + i.detail;
                }
                if (!i.playing) return null;
                DevE2E.log(
                    "tocando: vozes={} fontesAL={} frames={} underruns={}",
                    i.voices,
                    AudioEngine.INSTANCE.playingSources(),
                    i.framesQueued,
                    i.underruns);
                return "";
            }
        };
    }

    /**
     * Um engasgo do cliente (chunks carregando, GC, autosave do singleplayer) congela a thread que enche o OpenAL. A
     * fila de cada fonte tem que aguentar: o som segue sem underrun nem ressincronização.
     */
    private Step clientHitch(String name, long hitchMs) {
        return new Step(name, 15) {

            int underruns, resyncs, hitchAt = -1;

            @Override
            String tick(int t) {
                AudioEngine.PlaybackInfo i = info();
                if (hitchAt < 0) {
                    if (t < 40 || i == null || !i.playing) return null; // 2 s tocando: a fila está cheia
                    underruns = i.underruns;
                    resyncs = i.resyncs;
                    hitchAt = t;
                    long until = System.nanoTime() + hitchMs * 1_000_000L;
                    while (System.nanoTime() < until) {
                        try {
                            Thread.sleep(Math.max(1, (until - System.nanoTime()) / 1_000_000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread()
                                .interrupt();
                            break;
                        }
                    }
                    return null;
                }
                if (i == null) return "a reprodução sumiu depois do engasgo";
                if (i.underruns != underruns) {
                    return "underrun no engasgo de " + hitchMs + " ms (" + underruns + " -> " + i.underruns + ")";
                }
                if (i.resyncs != resyncs) return "ressincronizou depois do engasgo de " + hitchMs + " ms";
                if (t - hitchAt < 60) return null;
                DevE2E.log("engasgo de {} ms: sem underrun, sem ressincronizar, tocando={}", hitchMs, i.playing);
                return i.playing ? "" : "parou de tocar depois do engasgo";
            }
        };
    }

    private Step reconnect() {
        return new Step("reconectar", 120) {

            int attempts;

            @Override
            String tick(int t) {
                // Espera o servidor encerrar a sessão anterior. Sem o Hodgepodge (cliente Java 8 de dev), o FML
                // 1.7.10 tem uma corrida no login ao reconectar com o mesmo nome; por isso há uma 2ª tentativa.
                if (t == 100 || (t == 900 && !inWorld())) {
                    attempts++;
                    DevE2E.log("conectando (tentativa {})", attempts);
                    mc().displayGuiScreen(new GuiConnecting(new GuiMainMenu(), mc(), "127.0.0.1", 25565));
                }
                return inWorld() && t > 160 ? "" : null;
            }
        };
    }

    private Step disconnect(String name) {
        return new Step(name, 20) {

            @Override
            String tick(int t) {
                // Espera 1 s antes: mensagens de chat do passo anterior precisam sair antes de fechar a conexão.
                if (t == 20 && mc().theWorld != null) {
                    mc().theWorld.sendQuittingDisconnectingPacket();
                    mc().loadWorld(null);
                    mc().displayGuiScreen(new GuiMainMenu());
                }
                if (t < 60) return null;
                int active = AudioEngine.INSTANCE.activeCount();
                int threads = liveDirectThreads();
                if (active == 0 && threads == 0) return "";
                return t >= 200 ? "ainda ativo: reproduções=" + active + " threads=" + threads : null;
            }
        };
    }

    // ---- Cenário principal (op, coloca a rádio) ----

    private void buildMain(boolean expectPeer) {
        steps.add(join());
        steps.add(new Step("ir-para-o-spawn", 5) {

            @Override
            void start() {
                // O mundo de teste é persistente: sem isto, a posição salva deriva a cada rodada e a rádio pode
                // acabar fora do alcance de uso (8 blocos) do segundo jogador, que nasce no spawn.
                ChunkCoordinates spawn = mc().theWorld.getSpawnPoint();
                say("/tp " + (spawn.posX + 0.5) + " " + spawn.posY + " " + (spawn.posZ + 0.5));
            }

            @Override
            String tick(int t) {
                ChunkCoordinates spawn = mc().theWorld.getSpawnPoint();
                double dx = mc().thePlayer.posX - (spawn.posX + 0.5), dz = mc().thePlayer.posZ - (spawn.posZ + 0.5);
                return t > 10 && dx * dx + dz * dz < 1 ? "" : null;
            }
        });
        steps.add(new Step("colocar-radio", 15) {

            @Override
            void start() {
                rx = MathHelper.floor_double(mc().thePlayer.posX) + 2;
                ry = MathHelper.floor_double(mc().thePlayer.boundingBox.minY);
                rz = MathHelper.floor_double(mc().thePlayer.posZ);
                // Rádios de rodadas anteriores (o mundo é persistente) param, para o teste medir só a nova.
                for (TileRadio old : ClientRadioRegistry.snapshot()) {
                    if (old.state.playing) FmNetwork
                        .sendToServer(new C2SRadioAction(old.xCoord, old.yCoord, old.zCoord, Action.STOP, 0, ""));
                }
                // Limpa antes: o mundo pode guardar a rádio de uma rodada anterior no mesmo lugar.
                say("/setblock " + rx + " " + ry + " " + rz + " air");
            }

            @Override
            String tick(int t) {
                if (t == 5) say("/setblock " + rx + " " + ry + " " + rz + " akashicfm:radio 3");
                TileRadio r = radio();
                return t > 5 && r != null && r.state.url.isEmpty() && !r.state.playing ? "" : null;
            }
        });
        steps.add(new Step("cliente-malicioso", 20) {

            @Override
            String tick(int t) {
                if (t == 0) say("e2e:mark before-malicious");
                if (t == 5) {
                    // Coordenadas que o servidor nunca pode carregar nem aceitar.
                    FmNetwork.sendToServer(new C2SRadioAction(rx + 100_000, ry, rz, Action.PLAY, 0, url));
                    FmNetwork.sendToServer(new C2SRadioAction(rx, -5, rz, Action.PLAY, 0, url));
                    FmNetwork.sendToServer(new C2SRadioAction(rx, 300, rz, Action.PLAY, 0, url));
                    FmNetwork.sendToServer(new C2SRadioAction(29_999_000, ry, 29_999_000, Action.SET_URL, 0, url));
                    // URL interna gigante na rádio de verdade: cortada na leitura e recusada pela política.
                    StringBuilder big = new StringBuilder("http://127.0.0.1/");
                    while (big.length() < 15_000) big.append('x');
                    send(Action.SET_URL, 0, big.toString());
                }
                if (t == 10) {
                    for (int i = 0; i < 200; i++) send(Action.STOP, 0, "");
                }
                if (t == 60) {
                    say("e2e:mark after-malicious");
                    say("e2e:check-unloaded " + (rx + 100_000) + " " + rz);
                    say("e2e:check-unloaded 29999000 29999000");
                }
                if (t < 60) return null;
                TileRadio r = radio();
                if (r != null && !r.state.url.isEmpty()) return "URL maliciosa foi aceita: " + r.state.url;
                if (chatSaw("loaded=true")) return "um pacote malicioso carregou chunk no servidor";
                boolean bothChecked = chatSaw("e2e-result chunk " + (rx + 100_000) + " " + rz + " loaded=false")
                    && chatSaw("e2e-result chunk 29999000 29999000 loaded=false");
                return bothChecked && notices.contains("akashicfm.policy.internal") ? "" : null;
            }
        });
        steps.add(new Step("tocar-" + (relayMode() ? "relay" : "modo-direto"), 20) {

            @Override
            String tick(int t) {
                if (t == 50) send(Action.PLAY, 0, url); // espera o limitador de ações recarregar
                if (notices.contains("akashicfm.notice.no_transport")) return "servidor sem transporte";
                TileRadio r = radio();
                return r != null && r.state.playing && r.state.transport == expectedTransport() ? "" : null;
            }
        });
        steps.add(audioPlaying("audio-comeca", 45));
        steps.add(new Step("anunciar-radio-tocando", 2) {

            @Override
            String tick(int t) {
                bringPeer();
                say("e2e:main radio-playing");
                return "";
            }
        });
        steps.add(new Step("audio-continuo-5s", 10) {

            long frames0;
            int under0;

            @Override
            void start() {
                AudioEngine.PlaybackInfo i = info();
                frames0 = i == null ? 0 : i.framesQueued;
                under0 = i == null ? 0 : i.underruns;
            }

            @Override
            String tick(int t) {
                if (t < 100) return null;
                AudioEngine.PlaybackInfo i = info();
                if (i == null) return "reprodução sumiu";
                long gained = i.framesQueued - frames0;
                int sources = AudioEngine.INSTANCE.playingSources();
                int allVoices = AudioEngine.INSTANCE.totalVoices();
                DevE2E.log(
                    "5 s: frames={} ({} s) underruns={} vozes={} fontesAL={}",
                    gained,
                    String.format("%.2f", gained / 48000.0),
                    i.underruns - under0,
                    i.voices,
                    sources);
                if (gained < 4 * 48000) return "pouco áudio entregue: " + gained + " frames";
                // Toda voz criada está tocando, e nenhuma fonte AL do mod ficou fora da contagem.
                if (sources != allVoices || i.voices == 0) {
                    return "fontes AL tocando=" + sources + " vozes=" + allVoices;
                }
                return "";
            }
        });
        if (relayMode()) steps.add(clientHitch("engasgo-de-700ms-nao-corta-o-som", 700));
        if (relayMode()) {
            steps.add(syncCheck("sincronia-do-relay"));
            steps.add(new Step("banda-do-relay", 20) {

                int seen, phase;
                long b0, t0;

                @Override
                void start() {
                    seen = relayResults().size();
                    say("e2e:relay-stats");
                }

                @Override
                String tick(int t) {
                    List<long[]> res = relayResults();
                    if (res.size() <= seen) return null;
                    long[] last = res.get(res.size() - 1);
                    seen = res.size();
                    if (phase == 0) {
                        b0 = last[0];
                        t0 = last[1];
                        phase = 1;
                        say("e2e:relay-stats"); // a resposta chega depois de mais áudio enviado
                        return null;
                    }
                    if (last[1] - t0 < 3000) { // ainda cedo: pede de novo
                        say("e2e:relay-stats");
                        return null;
                    }
                    double rate = (last[0] - b0) * 1000.0 / (last[1] - t0);
                    DevE2E.log("relay: {} bytes/s para este jogador (Opus 64 kbps = 8000 B/s)", Math.round(rate));
                    return rate > 6000 && rate < 14000 ? "" : "taxa fora do esperado: " + Math.round(rate) + " B/s";
                }
            });
        }
        addNowPlayingSteps();
        steps.add(new Step("tela-da-radio-com-texto", 15) {

            @Override
            void start() {
                send(Action.SET_SCREEN_TEXT, 0, "Radio Paradise 128k");
                say("/time set 6000");
                say("/tp " + (rx + 0.5) + " " + ry + " " + (rz + 2.3));
            }

            @Override
            String tick(int t) {
                if (t == 40) {
                    // De frente para a rádio (face sul), olhando para o centro da tela dela.
                    mc().thePlayer.rotationYaw = 180f;
                    mc().thePlayer.rotationPitch = 37f;
                    mc().gameSettings.hideGUI = true;
                }
                if (t == 80) {
                    screenshot("e2e-radio-tesr.png");
                    mc().gameSettings.hideGUI = false;
                }
                TileRadio r = radio();
                return t >= 80 && r != null && "Radio Paradise 128k".equals(r.state.screenText) ? "" : null;
            }
        });
        steps.add(new Step("gui-abre-com-permissoes", 10) {

            @Override
            void start() {
                mc().displayGuiScreen(new GuiRadio(rx, ry, rz));
            }

            @Override
            String tick(int t) {
                if (t == 28) screenshot("e2e-gui.png");
                if (t < 30) return null;
                if (!(mc().currentScreen instanceof GuiRadio)) return "a GUI fechou sozinha";
                GuiRadio gui = (GuiRadio) mc().currentScreen;
                boolean ok = gui.permsKnown() && gui.canAdmin();
                mc().displayGuiScreen(null);
                return ok ? "" : "permissões não chegaram ou op sem admin";
            }
        });
        steps.add(new Step("tela-de-config-do-mod", 10) {

            @Override
            void start() {
                try {
                    mc().displayGuiScreen(new FmConfigGui(null));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            String tick(int t) {
                if (t == 20) screenshot("e2e-config.png");
                if (t < 22) return null;
                boolean ok = mc().currentScreen instanceof FmConfigGui;
                mc().displayGuiScreen(null);
                return ok ? "" : "a tela de config não abriu";
            }
        });
        steps.add(new Step("pegar-sintonizador", 10) {

            @Override
            void start() {
                say("/give " + mc().thePlayer.getCommandSenderName() + " akashicfm:tuner");
            }

            @Override
            String tick(int t) {
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack st = mc().thePlayer.inventory.getStackInSlot(slot);
                    if (st != null && st.getItem() instanceof ItemTuner) {
                        mc().thePlayer.inventory.currentItem = slot;
                        return "";
                    }
                }
                return null;
            }
        });
        steps.add(new Step("colocar-caixa", 10) {

            @Override
            void start() {
                sx = rx + 3;
                sy = ry;
                sz = rz;
                say("/setblock " + sx + " " + sy + " " + sz + " air");
            }

            @Override
            String tick(int t) {
                if (t == 5) say("/setblock " + sx + " " + sy + " " + sz + " akashicfm:speaker 3");
                return t > 5 && speaker() != null ? "" : null;
            }
        });
        steps.add(new Step("ligar-caixa-com-sintonizador", 10) {

            @Override
            void start() {
                markContinuity();
            }

            @Override
            String tick(int t) {
                if (t == 2) rightClick(sx, sy, sz); // seleciona a caixa
                if (t == 12) rightClick(rx, ry, rz); // liga na rádio
                TileRadio r = radio();
                boolean linked = r != null && r.state.speakers.contains(new Pos(sx, sy, sz));
                return linked && chatKeys.contains("akashicfm.tuner.linked") ? "" : null;
            }
        });
        steps.add(new Step("caixa-toca-junto", 10) {

            @Override
            String tick(int t) {
                // Rádio em estéreo (2 fontes) + caixa em MIX (1 fonte), que entrou sem reiniciar as outras.
                if (!voicesAre(3)) return null;
                return continuityBroken();
            }
        });
        steps.add(new Step("waila-e-cone-da-caixa", 15) {

            float bassMax;

            @Override
            void start() {
                // De frente para a caixa (face sul), perto, para a captura do cone pulsando.
                say("/tp " + (sx + 0.5) + " " + sy + " " + (sz + 2.6));
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return null;
                float[] bands = new float[SpectrumAnalyzer.BANDS];
                if (AudioEngine.INSTANCE.visuals(RadioAudioController.playbackKey(r), bands) >= 0) {
                    bassMax = Math.max(bassMax, Math.max(bands[0], Math.max(bands[1], bands[2])));
                }
                if (t == 30) {
                    mc().thePlayer.rotationYaw = 180f;
                    mc().thePlayer.rotationPitch = 30f;
                    mc().gameSettings.hideGUI = true;
                }
                if (t == 70) {
                    screenshot("e2e-speaker.png");
                    mc().gameSettings.hideGUI = false;
                }
                if (t < 70) return null;
                TileSpeaker sp = speaker();
                if (sp == null) return "caixa sumiu";
                List<String> lines = RadioInfo.lines(sp);
                DevE2E.log("waila da caixa: {} | graves máx={}", lines, String.format("%.2f", bassMax));
                String linked = rx + ", " + ry + ", " + rz;
                boolean ok = false;
                for (String l : lines) if (l.contains(linked)) ok = true;
                if (!ok) return "linhas da caixa sem a rádio ligada: " + lines;
                // O cone só se mexe com grave acima de 0,45 (≈ -27 dBFS na banda).
                return bassMax > 0.45f ? "" : "graves fracos demais para o cone: " + bassMax;
            }
        });
        steps.add(new Step("trocar-canal-agachado-ate-estereo", 15) {

            @Override
            void start() {
                markContinuity();
            }

            @Override
            String tick(int t) {
                // MIX -> LEFT -> RIGHT -> STEREO: três cliques agachado.
                if (t == 1) sneak(true);
                if (t == 4 || t == 14 || t == 24) rightClick(sx, sy, sz);
                if (t == 30) sneak(false);
                TileSpeaker sp = speaker();
                if (t < 30 || sp == null || sp.channel != SpeakerChannel.STEREO) return null;
                if (!voicesAre(4)) return null; // caixa em estéreo vira duas fontes
                return continuityBroken();
            }
        });
        steps.add(new Step("desvincular-caixas", 10) {

            @Override
            void start() {
                markContinuity();
                send(Action.UNLINK_ALL_SPEAKERS, 0, "");
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                TileSpeaker sp = speaker();
                boolean clean = r != null && r.state.speakers.isEmpty() && sp != null && sp.linkedRadio == null;
                if (!clean || !voicesAre(2)) return null;
                return continuityBroken();
            }
        });
        addAcousticSteps();
        steps.add(soundReload("recarregar-som-durante-reproducao"));
        if (expectPeer) {
            steps.add(new Step("esperar-segundo-jogador", 600) {

                @Override
                String tick(int t) {
                    // Repete o anúncio: o segundo cliente pode ter entrado depois da primeira mensagem.
                    if (t % 100 == 0) {
                        bringPeer();
                        say("e2e:main radio-playing");
                    }
                    return chatSaw("e2e:peer ready") ? "" : null;
                }
            });
        }
        addFrequencySteps(expectPeer);
        addPortableSteps(expectPeer);
        addIPodSteps(expectPeer);
        addAdminSteps(expectPeer);
        if (relayMode()) addPlaylistSteps(expectPeer);
        addMuteSteps();
        if (Loader.isModLoaded("OpenComputers")) addOcSteps();
        steps.add(new Step("teleporte-1000-blocos-silencia", 15) {

            double startX;
            int movedAt = -1;

            @Override
            void start() {
                startX = mc().thePlayer.posX;
                say("/tp ~1000 ~ ~");
            }

            @Override
            String tick(int t) {
                if (!inWorld()) return null;
                if (movedAt < 0 && Math.abs(mc().thePlayer.posX - startX) > 500) movedAt = t;
                if (movedAt < 0) return null;
                if (AudioEngine.INSTANCE.activeCount() == 0) {
                    int delay = t - movedAt;
                    DevE2E.log("silêncio {} ticks depois do teleporte", delay);
                    return delay <= TPS ? "" : "demorou " + delay + " ticks";
                }
                return null;
            }
        });
        if (relayMode()) {
            steps.add(new Step("relay-para-de-enviar-fora-do-alcance", 15) {

                int seen;
                long first = -1;

                @Override
                String tick(int t) {
                    // Espera a audiência do servidor (a cada 10 ticks) perceber que saímos do alcance.
                    if (t == 40 || t == 120) {
                        seen = relayResults().size();
                        say("e2e:relay-stats");
                    }
                    List<long[]> res = relayResults();
                    if (t > 40 && first < 0 && res.size() > seen) first = res.get(res.size() - 1)[0];
                    if (t <= 120 || res.size() <= seen || first < 0) return null;
                    long second = res.get(res.size() - 1)[0];
                    DevE2E.log(
                        "relay fora do alcance: bytes {} -> {}, estações neste cliente={}",
                        first,
                        second,
                        RelayClient.activeStations());
                    if (second != first) return "o servidor continuou mandando " + (second - first) + " bytes";
                    return RelayClient.activeStations() == 0 ? "" : "o cliente ainda tem estação aberta";
                }
            });
        }
        steps.add(new Step("voltar-e-ouvir-de-novo", 5) {

            @Override
            void start() {
                say("/tp ~-1000 ~ ~");
            }

            @Override
            String tick(int t) {
                return t >= 20 ? "" : null;
            }
        });
        steps.add(audioPlaying("audio-volta", 45));
        steps.add(new Step("parar", 5) {

            @Override
            void start() {
                send(Action.STOP, 0, "");
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                return r != null && !r.state.playing && AudioEngine.INSTANCE.activeCount() == 0 ? "" : null;
            }
        });
        steps.add(new Step("efx-solto-depois-de-parar", 12) {

            @Override
            String tick(int t) {
                if (AudioEngine.liveEfxObjects() != 0) return null;
                DevE2E.log("objetos EFX soltos {} ticks depois de parar", t);
                // Com EFX, espera a cauda do reverb (4 s) antes de soltar; sem EFX, nunca houve objeto.
                if (efxExpected() && t < 3 * TPS) return "EFX solto cedo demais (" + t + " ticks): corta a cauda";
                return "";
            }
        });
        if (expectPeer) {
            steps.add(new Step("segundo-jogador-parou", 60) {

                @Override
                String tick(int t) {
                    return chatSaw("e2e:peer stopped-ok") ? "" : null;
                }
            });
        }
        steps.add(new Step("tocar-de-novo", 10) {

            @Override
            void start() {
                send(Action.PLAY, 0, "");
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                return r != null && r.state.playing ? "" : null;
            }
        });
        steps.add(audioPlaying("audio-de-novo", 45));
        steps.add(disconnect("desconectar-sem-som-orfao"));
        steps.add(reconnect());
        steps.add(audioPlaying("ouvir-depois-de-reconectar", 45));
        steps.add(purgeKeepsLoaded());
        steps.add(new Step("fm-stopall-para-tudo", 15) {

            int seen;

            @Override
            void start() {
                seen = countChat("Stopped ");
                say("/fm stopall");
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (countChat("Stopped ") <= seen || r == null || r.state.playing) return null;
                return AudioEngine.INSTANCE.activeCount() == 0 ? "" : null;
            }
        });
        cleanupIndex = steps.size();
        steps.add(disconnect("desconectar-final"));
    }

    // ---- Fase 6a: transmissores, antenas e rádio sintonizada ----

    /** Transmissores do roteiro: T3 (torre longe, chunk descarregado), T (24 blocos, 2 antenas), T2 (perto). */
    private int t3x, tx, t2x;

    private TileTransmitter transmitterAt(int x, int y, int z) {
        TileEntity te = mc().theWorld == null ? null : mc().theWorld.getTileEntity(x, y, z);
        return te instanceof TileTransmitter ? (TileTransmitter) te : null;
    }

    private static void sendTo(int x, int y, int z, Action action, int intArg, String strArg) {
        FmNetwork.sendToServer(new C2SRadioAction(x, y, z, action, intArg, strArg));
    }

    /** Leva o jogador a (x, z) e espera chegar (mundo plano: a altura da rádio é o chão). */
    private Step teleport(String name, java.util.function.IntSupplier xs, double dz) {
        return new Step(name, 15) {

            double x, z;

            @Override
            void start() {
                x = xs.getAsInt() + 0.5;
                z = rz + dz;
                say("/tp " + x + " " + ry + " " + z);
            }

            @Override
            String tick(int t) {
                if (!inWorld()) return null;
                double ddx = mc().thePlayer.posX - x, ddz = mc().thePlayer.posZ - z;
                return t > 20 && ddx * ddx + ddz * ddz < 1 ? "" : null;
            }
        };
    }

    /**
     * Monta um transmissor em (x, ry, rz) com {@code antennas} antenas em cima e o põe no ar. O jogador precisa estar
     * a até 8 blocos (as ações do transmissor têm o mesmo limite de uso da rádio).
     */
    private Step buildTransmitter(String name, java.util.function.IntSupplier xs, int antennas, String u, int freq,
        String station) {
        return new Step(name, 20) {

            int x;

            @Override
            void start() {
                x = xs.getAsInt();
                say("/setblock " + x + " " + ry + " " + rz + " air");
            }

            @Override
            String tick(int t) {
                if (t == 5) say("/setblock " + x + " " + ry + " " + rz + " akashicfm:transmitter 3");
                if (t == 10 && antennas > 0) fill(x, ry + 1, rz, x, ry + antennas, rz, "akashicfm:antenna");
                if (t == 20) {
                    sendTo(x, ry, rz, Action.SET_URL, 0, u);
                    sendTo(x, ry, rz, Action.SET_FREQUENCY, freq, "");
                    sendTo(x, ry, rz, Action.SET_SCREEN_TEXT, 0, station);
                    sendTo(x, ry, rz, Action.PLAY, 0, "");
                }
                TileTransmitter tr = transmitterAt(x, ry, rz);
                if (t < 20 || tr == null) return null;
                TransmitterState s = tr.state;
                // Alcances do servidor de teste (o config do cliente é outro).
                int range = DevE2E.TRANSMITTER_BASE_RANGE + antennas * DevE2E.TRANSMITTER_RANGE_PER_ANTENNA;
                boolean ok = s.active() && u.equals(s.url)
                    && s.frequency == freq
                    && station.equals(s.name)
                    && s.antennas == antennas
                    && s.range == range;
                if (!ok) return null;
                DevE2E.log(
                    "transmissor {} no ar: {} em {} MHz, {} antenas, alcance {}, energia exigida={}",
                    station,
                    s.url,
                    Frequency.format(s.frequency),
                    s.antennas,
                    s.range,
                    s.energyRequired);
                // Sem IC2 nem RF no ambiente de dev: a exigência de energia não se aplica.
                return s.energyRequired ? "energia exigida sem nenhuma API de energia instalada" : "";
            }
        };
    }

    /** A rádio sintonizada chegou ao estado esperado (URL do transmissor, nome e faixa de sinal). */
    private Step tunedTo(String name, String u, String station, int minSignal, int maxSignal) {
        return new Step(name, 20) {

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return null;
                RadioState s = r.state;
                if (s.mode != TuneMode.FREQUENCY || !s.playing || !u.equals(s.tunedUrl)) return null;
                if (!station.equals(s.tunedName) || s.transport != expectedTransport()) return null;
                DevE2E.log(
                    "sintonizada em {} MHz: {} ({}), sinal {}%, sessão {}",
                    Frequency.format(s.frequency),
                    s.tunedName,
                    s.tunedUrl,
                    s.signal,
                    s.session);
                return s.signal >= minSignal && s.signal <= maxSignal ? ""
                    : "sinal " + s.signal + "% fora de [" + minSignal + ", " + maxSignal + "]";
            }
        };
    }

    /** Pergunta ao servidor se o chunk do bloco está carregado e espera a resposta esperada. */
    private Step chunkLoaded(String name, java.util.function.IntSupplier xs, boolean expected) {
        return new Step(name, 30) {

            @Override
            String tick(int t) {
                int x = xs.getAsInt();
                if (t % 40 == 0) say("e2e:check-unloaded " + x + " " + rz);
                return chatSaw("e2e-result chunk " + x + " " + rz + " loaded=" + expected) ? "" : null;
            }
        };
    }

    private void addFrequencySteps(boolean expectPeer) {
        // Torre longe (150 blocos, 9 antenas, alcance 16 + 9×16 = 160) em outra frequência: depois que o jogador
        // volta, o chunk dela descarrega (view-distance 4) e a rádio ainda a sintoniza pelo índice.
        steps.add(teleport("fm-ir-ate-a-torre", () -> t3x = rx - 150, 2.5));
        steps.add(buildTransmitter("fm-torre-no-ar", () -> t3x, 9, url2, 1000, "Torre"));
        // T: 24 blocos da rádio, 2 antenas (alcance 48). Sem as antenas (16) não alcança mais a rádio.
        steps.add(teleport("fm-ir-ate-o-transmissor", () -> tx = rx - 24, 2.5));
        steps.add(buildTransmitter("fm-transmissor-no-ar", () -> tx, 2, url, 987, "Akashic FM"));
        steps.add(new Step("gui-do-transmissor", 10) {

            @Override
            void start() {
                mc().displayGuiScreen(new GuiTransmitter(tx, ry, rz));
            }

            @Override
            String tick(int t) {
                if (t == 28) screenshot("e2e-gui-transmissor.png");
                if (t < 30) return null;
                if (!(mc().currentScreen instanceof GuiTransmitter)) return "a GUI do transmissor fechou sozinha";
                boolean ok = ((GuiTransmitter) mc().currentScreen).permsKnown();
                mc().displayGuiScreen(null);
                return ok ? "" : "permissões não chegaram";
            }
        });
        steps.add(new Step("tela-do-transmissor-e-antenas", 10) {

            @Override
            void start() {
                say("/tp " + (tx + 0.5) + " " + ry + " " + (rz + 4.5)); // longe o bastante para ver as antenas
            }

            @Override
            String tick(int t) {
                if (t == 20) {
                    mc().thePlayer.rotationYaw = 180f;
                    mc().thePlayer.rotationPitch = 2f;
                    mc().gameSettings.hideGUI = true;
                }
                if (t == 45) {
                    screenshot("e2e-transmissor.png");
                    mc().gameSettings.hideGUI = false;
                }
                if (t < 45) return null;
                TileTransmitter tr = transmitterAt(tx, ry, rz);
                if (tr == null) return "transmissor sumiu";
                List<String> lines = RadioInfo.lines(tr);
                DevE2E.log("waila do transmissor: {}", lines);
                return lines.toString()
                    .contains("98.7") ? "" : "waila sem a frequência: " + lines;
            }
        });
        steps.add(teleport("fm-voltar-para-a-radio", () -> rx, 2.3));
        steps.add(chunkLoaded("fm-chunk-da-torre-descarregou", () -> t3x, false));
        steps.add(new Step("fm-sintonizar-a-radio", 5) {

            @Override
            void start() {
                send(Action.SET_SCREEN_TEXT, 0, ""); // a tela passa a mostrar a frequência e o título
                send(Action.SET_FREQUENCY, 987, "");
                send(Action.SET_MODE, TuneMode.FREQUENCY.ordinal(), "");
            }

            @Override
            String tick(int t) {
                return "";
            }
        });
        // Sinal = 1 − d/alcance: T a ~24,5 blocos com alcance 48 → ~49%.
        steps.add(tunedTo("fm-radio-ouve-o-transmissor", url, "Akashic FM", 40, 60));
        steps.add(audioPlaying("fm-audio-sintonizado", 45));
        // T2 a 3 blocos, sem antenas (alcance 16 → ~79%): mais de 0,1 acima do atual, assume.
        steps.add(buildTransmitter("fm-transmissor-perto-no-ar", () -> t2x = rx - 3, 0, url2, 987, "Perto FM"));
        steps.add(tunedTo("fm-o-mais-forte-assume", url2, "Perto FM", 70, 90));
        steps.add(audioPlaying("fm-audio-do-mais-forte", 45));
        steps.add(new Step("fm-tela-e-waila-da-radio", 15) {

            @Override
            String tick(int t) {
                if (t == 5) {
                    mc().thePlayer.rotationYaw = 180f;
                    mc().thePlayer.rotationPitch = 37f;
                    mc().gameSettings.hideGUI = true;
                }
                if (t == 60) {
                    screenshot("e2e-radio-fm.png");
                    mc().gameSettings.hideGUI = false;
                }
                if (t < 60) return null;
                TileRadio r = radio();
                if (r == null) return "rádio sumiu";
                List<String> lines = RadioInfo.lines(r);
                DevE2E.log("waila da rádio sintonizada: {}", lines);
                return lines.toString()
                    .contains("98.7")
                    && lines.toString()
                        .contains("Perto FM") ? "" : "waila incompleto: " + lines;
            }
        });
        steps.add(new Step("gui-da-radio-no-fm", 10) {

            @Override
            void start() {
                mc().displayGuiScreen(new GuiRadio(rx, ry, rz));
            }

            @Override
            String tick(int t) {
                if (t == 28) screenshot("e2e-gui-fm.png");
                if (t < 30) return null;
                boolean ok = mc().currentScreen instanceof GuiRadio && ((GuiRadio) mc().currentScreen).permsKnown();
                mc().displayGuiScreen(null);
                return ok ? "" : "a GUI da rádio no modo FM não abriu com permissões";
            }
        });
        if (expectPeer) {
            steps.add(new Step("fm-segundo-jogador-acompanha", 120) {

                @Override
                String tick(int t) {
                    return chatSaw("e2e:peer fm-ok") ? "" : null;
                }
            });
        }
        steps.add(new Step("fm-desligar-o-perto", 5) {

            @Override
            void start() {
                sendTo(t2x, ry, rz, Action.STOP, 0, "");
            }

            @Override
            String tick(int t) {
                TileTransmitter tr = transmitterAt(t2x, ry, rz);
                return tr != null && !tr.state.broadcasting ? "" : null;
            }
        });
        steps.add(new Step("antena-colocada-com-a-mao", 15) {

            int selectedAt = -1, clickedAt = -1;

            @Override
            void start() {
                // O /give do 1.7.10 joga o item no chão e o jogador o pega alguns ticks depois.
                say("/give " + mc().thePlayer.getCommandSenderName() + " akashicfm:antenna 4");
            }

            @Override
            String tick(int t) {
                if (selectedAt < 0) {
                    for (int slot = 0; slot < 9; slot++) {
                        ItemStack st = mc().thePlayer.inventory.getStackInSlot(slot);
                        if (st != null && Block.getBlockFromItem(st.getItem()) == FmContent.antenna) {
                            mc().thePlayer.inventory.currentItem = slot;
                            selectedAt = t;
                        }
                    }
                    return null;
                }
                // Espera a troca de item chegar ao servidor antes do clique (vai no próximo tick do controlador).
                if (clickedAt < 0 && t >= selectedAt + 5) {
                    rightClick(t2x, ry, rz); // na face de cima do transmissor: coloca, não abre a tela
                    clickedAt = t;
                }
                // Só a tela do transmissor é falha (o menu de pausa pode abrir sozinho sem foco na janela).
                if (mc().currentScreen instanceof GuiTransmitter) return "a tela abriu em vez de colocar a antena";
                TileTransmitter tr = transmitterAt(t2x, ry, rz);
                boolean placed = mc().theWorld.getBlock(t2x, ry + 1, rz) == FmContent.antenna;
                return clickedAt >= 0 && placed && tr != null && tr.state.antennas == 1 ? "" : null;
            }
        });
        steps.add(tunedTo("fm-volta-para-o-outro-transmissor", url, "Akashic FM", 40, 60));
        steps.add(audioPlaying("fm-audio-do-outro-transmissor", 45));
        steps.add(new Step("fm-sem-antenas-sem-sinal", 15) {

            int silentTicks;

            @Override
            void start() {
                // Sem as antenas o alcance de T cai para 16 e não chega mais à rádio, a ~24 blocos.
                fill(tx, ry + 1, rz, tx, ry + 2, rz, "minecraft:air");
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                TileTransmitter tr = transmitterAt(tx, ry, rz);
                if (r == null || tr == null || tr.state.antennas != 0) return null;
                RadioState s = r.state;
                if (!s.playing || s.mode != TuneMode.FREQUENCY || !s.tunedUrl.isEmpty()) return null;
                if (!s.status.startsWith("akashicfm.status.no_signal")) return null;
                // Ligada e em silêncio: nenhuma reprodução neste cliente.
                silentTicks = AudioEngine.INSTANCE.activeCount() == 0 ? silentTicks + 1 : 0;
                if (silentTicks < 20) return null;
                DevE2E.log("sem sinal: status '{}', tela '{}'", s.status, RadioInfo.lines(r));
                return "";
            }
        });
        steps.add(new Step("fm-torre-em-chunk-descarregado", 5) {

            @Override
            void start() {
                send(Action.SET_FREQUENCY, 1000, "");
            }

            @Override
            String tick(int t) {
                return "";
            }
        });
        // Torre a ~150 blocos com alcance 160: sinal baixo, mas pega (o índice responde sem carregar o chunk).
        steps.add(tunedTo("fm-radio-ouve-a-torre-longe", url2, "Torre", 1, 15));
        steps.add(chunkLoaded("fm-torre-continua-descarregada", () -> t3x, false));
        steps.add(audioPlaying("fm-audio-da-torre", 45));
        steps.add(new Step("fm-voltar-para-url", 20) {

            @Override
            void start() {
                send(Action.SET_MODE, TuneMode.URL.ordinal(), "");
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return null;
                RadioState s = r.state;
                boolean ok = s.mode == TuneMode.URL && s.playing
                    && url.equals(s.effectiveUrl())
                    && s.transport == expectedTransport()
                    && s.tunedUrl.isEmpty();
                return ok ? "" : null;
            }
        });
        steps.add(audioPlaying("fm-audio-de-volta-na-url", 45));
    }

    private void addPeerFrequencySteps() {
        steps.add(new Step("peer-segue-o-fm", 900) {

            final Step inner = audioPlaying("peer-audio-fm", 45);
            int since = -1;

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (since < 0) {
                    if (r == null || r.state.mode != TuneMode.FREQUENCY
                        || !url2.equals(r.state.effectiveUrl())
                        || !"Perto FM".equals(r.state.tunedName)) return null;
                    since = t;
                    DevE2E.log("peer: rádio sintonizada em {} ({})", r.state.tunedName, r.state.tunedUrl);
                }
                String res = inner.tick(t - since);
                if (res == null && t - since > inner.timeoutTicks) return "peer não ouviu o transmissor";
                return res;
            }
        });
        if (relayMode()) steps.add(syncCheck("peer-sincronia-no-fm"));
        steps.add(new Step("peer-avisa-fm-ok", 2) {

            @Override
            String tick(int t) {
                say("e2e:peer fm-ok");
                return "";
            }
        });
    }

    // ---- Fase 6b: rádio portátil e fone ----

    private int portableSlot = -1;

    private ItemStack portableStack() {
        if (portableSlot < 0) return null;
        ItemStack st = mc().thePlayer.inventory.mainInventory[portableSlot];
        return st != null && st.getItem() instanceof ItemPortableRadio ? st : null;
    }

    private void sendPortable(C2SPortableAction.Action action, int intArg, String strArg) {
        ItemStack st = portableStack();
        long id = st == null ? 0 : ItemPortableRadio.state(st).id;
        FmNetwork.sendToServer(new C2SPortableAction(portableSlot, id, action, intArg, strArg));
    }

    /** A fonte de portátil que este cliente recebe do servidor para a URL (de quem for), ou null. */
    private S2CPortableSources.Entry portableEntry(String u, boolean mine) {
        int me = mc().thePlayer.getEntityId();
        for (S2CPortableSources.Entry e : ClientPortables.current(System.currentTimeMillis())) {
            if (u.equals(e.url) && (e.entityId == me) == mine) return e;
        }
        return null;
    }

    /** Reprodução do portátil neste cliente (no relay é a da estação), ou null. */
    private static AudioEngine.PlaybackInfo portableInfo(S2CPortableSources.Entry e) {
        if (e == null) return null;
        if (e.transport == Transport.RELAY) {
            RelayFeed feed = RelayClient.feedForUrl(e.url);
            return feed == null ? null : AudioEngine.INSTANCE.info(RadioAudioController.relayKey(feed));
        }
        return AudioEngine.INSTANCE.info(RadioAudioController.portableKey(e));
    }

    /** O portátil do outro jogador soa aqui, no mundo (fonte com posição, não presa a quem ouve). */
    private boolean hearsOtherPortable() {
        S2CPortableSources.Entry e = portableEntry(url2, false);
        AudioEngine.PlaybackInfo i = portableInfo(e);
        return i != null && i.playing && i.voices >= 1 && i.relativeVoices == 0;
    }

    /** Nada do portátil do outro jogador chega aqui: nem a fonte, nem a estação do relay, nem reprodução. */
    private boolean otherPortableSilent() {
        if (portableEntry(url2, false) != null) return false;
        if (relayMode()) return RelayClient.feedForUrl(url2) == null;
        for (S2CPortableSources.Entry e : ClientPortables.current(System.currentTimeMillis())) {
            if (AudioEngine.INSTANCE.info(RadioAudioController.portableKey(e)) != null) return false;
        }
        return true;
    }

    /** Diz {@code mark} no chat a cada 5 s até ver {@code ack} (o outro cliente pode estar ocupado num passo). */
    private Step handshake(String name, String mark, String ack) {
        return new Step(name, 120) {

            @Override
            String tick(int t) {
                if (t % 100 == 0) say("e2e:main " + mark);
                return chatSaw("e2e:peer " + ack) ? "" : null;
            }
        };
    }

    /** O próprio portátil toca preso a quem ouve: {@code voices} vozes relativas, {@code dry} delas sem filtro. */
    private Step ownPortable(String name, boolean headphones, int voices, int dry) {
        return new Step(name, 45) {

            @Override
            String tick(int t) {
                S2CPortableSources.Entry e = portableEntry(url2, true);
                if (e == null || e.headphones != headphones || e.transport != expectedTransport()) return null;
                AudioEngine.PlaybackInfo i = portableInfo(e);
                if (i == null || !i.playing || i.relativeVoices != voices || i.dryVoices != dry) return null;
                DevE2E.log(
                    "portátil próprio: vozes={} relativas={} sem filtro={} fone={} sessão={} {}",
                    i.voices,
                    i.relativeVoices,
                    i.dryVoices,
                    e.headphones,
                    e.session,
                    alCounters());
                return alInvariantsHold() ? "" : "objetos AL fora do esperado: " + alCounters();
            }
        };
    }

    private void addPortableSteps(boolean expectPeer) {
        steps.add(new Step("portatil-pegar", 15) {

            @Override
            void start() {
                say("/give " + mc().thePlayer.getCommandSenderName() + " akashicfm:portable_radio");
            }

            @Override
            String tick(int t) {
                // O /give joga o item no chão; o servidor dá a identidade assim que ele entra no inventário.
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack st = mc().thePlayer.inventory.getStackInSlot(slot);
                    if (st != null && st.getItem() instanceof ItemPortableRadio
                        && ItemPortableRadio.state(st).id != 0) {
                        portableSlot = slot;
                        return "";
                    }
                }
                return null;
            }
        });
        steps.add(new Step("portatil-ligar", 10) {

            @Override
            void start() {
                sendPortable(C2SPortableAction.Action.SET_URL, 0, url2);
                sendPortable(C2SPortableAction.Action.TURN_ON, 0, "");
            }

            @Override
            String tick(int t) {
                ItemStack st = portableStack();
                if (st == null) return null;
                PortableState s = ItemPortableRadio.state(st);
                return s.on && url2.equals(s.url) ? "" : null;
            }
        });
        steps.add(ownPortable("portatil-toca-para-quem-carrega", false, 1, 0));
        steps.add(new Step("gui-do-portatil", 10) {

            @Override
            void start() {
                mc().displayGuiScreen(new GuiPortableRadio(portableSlot));
            }

            @Override
            String tick(int t) {
                if (t == 28) screenshot("e2e-gui-portatil.png");
                if (t < 30) return null;
                boolean ok = mc().currentScreen instanceof GuiPortableRadio;
                mc().displayGuiScreen(null);
                return ok ? "" : "a tela do portátil não abriu";
            }
        });
        if (expectPeer) steps.add(handshake("portatil-segundo-jogador-ouve", "portable-on", "portable-heard"));
        // Longe do segundo jogador (alcance 16): ele para de receber; quem carrega continua ouvindo.
        steps.add(teleport("portatil-ir-longe", () -> rx + 60, 2.3));
        steps.add(ownPortable("portatil-longe-continua-tocando", false, 1, 0));
        if (expectPeer) steps.add(handshake("portatil-longe-ninguem-mais-ouve", "portable-far", "portable-far-ok"));
        steps.add(teleport("portatil-voltar", () -> rx, 2.3));
        if (expectPeer) steps.add(handshake("portatil-perto-de-novo", "portable-back", "portable-back-ok"));
        steps.add(new Step("fone-colocar", 5) {

            @Override
            void start() {
                say("e2e:headphones on");
            }

            @Override
            String tick(int t) {
                ItemStack helmet = mc().thePlayer.inventory.armorItemInSlot(3);
                return helmet != null && helmet.getItem() instanceof ItemHeadphones ? "" : null;
            }
        });
        // De fone: par estéreo preso a quem ouve, sem filtro nem reverb; o segundo jogador perde a estação.
        steps.add(ownPortable("fone-estereo-so-para-quem-usa", true, 2, 2));
        if (expectPeer) steps.add(handshake("fone-segundo-jogador-nao-ouve", "headphones-on", "hp-on-ok"));
        steps.add(new Step("fone-tirar", 5) {

            @Override
            void start() {
                say("e2e:headphones off");
            }

            @Override
            String tick(int t) {
                return mc().thePlayer.inventory.armorItemInSlot(3) == null ? "" : null;
            }
        });
        steps.add(ownPortable("sem-fone-volta-o-alto-falante", false, 1, 0));
        if (expectPeer) steps.add(handshake("sem-fone-segundo-jogador-ouve", "headphones-off", "hp-off-ok"));
        // O mesmo com o fone num slot de bauble (Baubles Expanded no ambiente de dev): prova a interface opcional.
        steps.add(new Step("fone-no-slot-do-baubles", 10) {

            @Override
            void start() {
                say("e2e:headphones bauble");
            }

            @Override
            String tick(int t) {
                if (chatSaw("e2e-result headphones bauble fail")) return "o slot de bauble recusou o fone";
                return chatSaw("e2e-result headphones bauble ok") ? "" : null;
            }
        });
        steps.add(ownPortable("fone-do-baubles-estereo-so-para-quem-usa", true, 2, 2));
        if (expectPeer) steps.add(handshake("fone-do-baubles-segundo-jogador-nao-ouve", "baubles-on", "bauble-ok"));
        steps.add(new Step("fone-do-baubles-tirar", 10) {

            int seen;

            @Override
            void start() {
                seen = countChat("e2e-result headphones off ok");
                say("e2e:headphones off");
            }

            @Override
            String tick(int t) {
                return countChat("e2e-result headphones off ok") > seen ? "" : null;
            }
        });
        steps.add(ownPortable("sem-fone-do-baubles-volta-o-alto-falante", false, 1, 0));
        if (expectPeer)
            steps.add(handshake("sem-fone-do-baubles-segundo-jogador-ouve", "baubles-off", "bauble-off-ok"));
        steps.add(new Step("largar-o-portatil-para", 15) {

            int silent;

            @Override
            void start() {
                say("/clear " + mc().thePlayer.getCommandSenderName() + " akashicfm:portable_radio");
            }

            @Override
            String tick(int t) {
                boolean gone = portableEntry(url2, true) == null
                    && (relayMode() ? RelayClient.feedForUrl(url2) == null : true);
                silent = gone ? silent + 1 : 0;
                return silent >= 20 ? "" : null;
            }
        });
        if (expectPeer)
            steps.add(handshake("sem-portatil-segundo-jogador-silencio", "portable-gone", "portable-gone-ok"));
    }

    /** Espera o anúncio do principal, depois a condição; responde com {@code ack}. */
    private Step peerPortable(String name, String mark, java.util.function.BooleanSupplier condition, String ack) {
        return new Step(name, 900) {

            int okTicks;

            @Override
            String tick(int t) {
                if (!chatSaw("e2e:main " + mark)) return null;
                okTicks = condition.getAsBoolean() ? okTicks + 1 : 0;
                if (t % 100 == 0) DevE2E.log("peer: {}", portableDiagnostics());
                if (okTicks < 10) return null;
                say("e2e:peer " + ack);
                return "";
            }
        };
    }

    /** O que este cliente sabe dos portáteis agora (diagnóstico dos passos que esperam ouvir ou não). */
    private String portableDiagnostics() {
        StringBuilder b = new StringBuilder("fontes=[");
        for (S2CPortableSources.Entry e : ClientPortables.current(System.currentTimeMillis())) {
            AudioEngine.PlaybackInfo i = portableInfo(e);
            b.append("{entidade=")
                .append(e.entityId)
                .append(mc().theWorld.getEntityByID(e.entityId) != null ? " (vista)" : " (não vista)")
                .append(" url=")
                .append(e.url)
                .append(" ")
                .append(e.transport)
                .append(" fone=")
                .append(e.headphones)
                .append(" reprodução=")
                .append(i == null ? "nenhuma" : (i.playing ? "tocando" : "parada") + " vozes=" + i.voices)
                .append("} ");
        }
        return b.append("] estação url2=")
            .append(RelayClient.feedForUrl(url2) != null)
            .append(" ")
            .append(alCounters())
            .toString();
    }

    private void addPeerPortableSteps() {
        steps.add(peerPortable("peer-ouve-o-portatil", "portable-on", this::hearsOtherPortable, "portable-heard"));
        steps.add(
            peerPortable("peer-portatil-longe-silencia", "portable-far", this::otherPortableSilent, "portable-far-ok"));
        steps.add(peerPortable("peer-portatil-volta", "portable-back", this::hearsOtherPortable, "portable-back-ok"));
        steps.add(peerPortable("peer-fone-isola", "headphones-on", this::otherPortableSilent, "hp-on-ok"));
        steps.add(peerPortable("peer-sem-fone-ouve", "headphones-off", this::hearsOtherPortable, "hp-off-ok"));
        steps.add(peerPortable("peer-fone-do-baubles-isola", "baubles-on", this::otherPortableSilent, "bauble-ok"));
        steps.add(
            peerPortable("peer-sem-fone-do-baubles-ouve", "baubles-off", this::hearsOtherPortable, "bauble-off-ok"));
        steps.add(peerPortable("peer-portatil-sumiu", "portable-gone", this::otherPortableSilent, "portable-gone-ok"));
    }

    // ---- Fase 8c: iPod (com o yt-dlp falso: tools/e2e/fake-yt-dlp.sh) ----

    private static final String IPOD_SET = "https://soundcloud.com/e2e/sets/lista";
    private static final String IPOD_VIDEO = "https://www.youtube.com/watch?v=e2eVideo001";
    private static final String IPOD_LONG = "https://soundcloud.com/e2e/faixa-longa";
    private int ipodSlot = -1;

    private ItemStack ipodStack() {
        return ipodSlot < 0 ? null : ItemIPod.at(mc().thePlayer, ipodSlot);
    }

    private IPodState ipodState() {
        ItemStack st = ipodStack();
        return st == null ? null : ItemIPod.state(st);
    }

    private void sendIPod(C2SIPodAction.Action action, int intArg, String strArg) {
        IPodState s = ipodState();
        FmNetwork.sendToServer(new C2SIPodAction(ipodSlot, s == null ? 0 : s.id, action, intArg, strArg));
    }

    /** A fonte de iPod que este cliente recebe (a própria ou a de outro jogador), ou null. */
    private S2CPortableSources.Entry ipodEntry(boolean mine) {
        int me = mc().thePlayer.getEntityId();
        for (S2CPortableSources.Entry e : ClientPortables.current(System.currentTimeMillis())) {
            if (e.url.startsWith("ipod:") && (e.entityId == me) == mine) return e;
        }
        return null;
    }

    private S2CIPodStatus ipodStatus() {
        IPodState s = ipodState();
        return s == null ? null : ClientIPod.status(s.id, System.currentTimeMillis());
    }

    /** O próprio iPod soa aqui (estação do relay com a chave dele, reprodução tocando). */
    private boolean ownIPodPlaying() {
        S2CPortableSources.Entry e = ipodEntry(true);
        AudioEngine.PlaybackInfo i = portableInfo(e);
        return e != null && e.transport == Transport.RELAY && i != null && i.playing;
    }

    /** O iPod do outro jogador soa aqui, no mundo. */
    private boolean hearsOtherIPod() {
        AudioEngine.PlaybackInfo i = portableInfo(ipodEntry(false));
        return i != null && i.playing && i.voices >= 1 && i.relativeVoices == 0;
    }

    /** Nada do iPod do outro jogador chega aqui. */
    private boolean otherIPodSilent() {
        S2CPortableSources.Entry e = ipodEntry(false);
        return e == null || e.url.isEmpty() || RelayClient.feedForUrl(e.url) == null;
    }

    /** Pede ao servidor as contagens de chamadas do yt-dlp falso e espera a resposta nova. */
    private Step ipodCalls(String name, java.util.function.Predicate<String> ok) {
        return new Step(name, 10) {

            int seen;

            @Override
            void start() {
                seen = countChat("e2e-result ipod-calls");
                say("e2e:ipod-calls");
            }

            @Override
            String tick(int t) {
                if (countChat("e2e-result ipod-calls") <= seen) return null;
                String last = "";
                for (String line : chat) if (line.contains("e2e-result ipod-calls")) last = line;
                DevE2E.log("ipod: {}", last);
                return ok.test(last) ? "" : "chamadas do yt-dlp fora do esperado: " + last;
            }
        };
    }

    private static int callCount(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) return -1;
        int j = i + key.length() + 1, k = j;
        while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
        return Integer.parseInt(line.substring(j, k));
    }

    private void addIPodSteps(boolean expectPeer) {
        steps.add(new Step("ipod-pegar", 15) {

            @Override
            void start() {
                say("/give " + mc().thePlayer.getCommandSenderName() + " akashicfm:ipod");
            }

            @Override
            String tick(int t) {
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack st = mc().thePlayer.inventory.getStackInSlot(slot);
                    if (st != null && st.getItem() instanceof ItemIPod && ItemIPod.state(st).id != 0) {
                        ipodSlot = slot;
                        return "";
                    }
                }
                return null;
            }
        });
        steps.add(new Step("ipod-link-de-outro-site-recusado", 10) {

            int before;

            @Override
            void start() {
                before = notices.size();
                sendIPod(C2SIPodAction.Action.ADD, 0, "https://evil.example.com/musica.mp3");
            }

            @Override
            String tick(int t) {
                return notices.subList(before, notices.size())
                    .contains("akashicfm.ipod.err.invalid") ? "" : null;
            }
        });
        steps.add(new Step("ipod-adicionar-set-do-soundcloud", 30) {

            @Override
            void start() {
                sendIPod(C2SIPodAction.Action.ADD, 0, IPOD_SET);
            }

            @Override
            String tick(int t) {
                IPodState s = ipodState();
                if (s == null || s.queue.size() != 3) return null;
                return s.on && s.index >= 0 ? "" : null; // a fila parada começa sozinha pela primeira
            }
        });
        if (!relayMode()) {
            // Sem relay o iPod não toca (não há modo direto para ele): o dono vê o motivo.
            steps.add(new Step("ipod-sem-relay-avisa", 20) {

                @Override
                String tick(int t) {
                    S2CIPodStatus st = ipodStatus();
                    return st != null && st.status.startsWith("akashicfm.ipod.err.no_relay") ? "" : null;
                }
            });
            steps.add(new Step("ipod-limpar-sem-relay", 10) {

                @Override
                void start() {
                    sendIPod(C2SIPodAction.Action.CLEAR, 0, "");
                }

                @Override
                String tick(int t) {
                    IPodState s = ipodState();
                    return s != null && s.queue.isEmpty() && !s.on ? "" : null;
                }
            });
            return;
        }
        // A primeira URL da "faixa-a" dá 404 (como uma assinada que expirou): toca depois de renovar.
        steps.add(new Step("ipod-toca-depois-de-renovar-a-url", 45) {

            @Override
            String tick(int t) {
                S2CIPodStatus st = ipodStatus();
                return ownIPodPlaying() && st != null && st.phase == S2CIPodStatus.Phase.PLAYING ? "" : null;
            }
        });
        steps.add(ipodCalls("ipod-a-url-expirada-foi-renovada", l -> callCount(l, "faixa-a") >= 2));
        steps.add(new Step("ipod-pula-a-protegida-e-segue", 45) {

            boolean sawDrm;

            @Override
            String tick(int t) {
                S2CIPodStatus st = ipodStatus();
                if (st != null && st.status.startsWith("akashicfm.ipod.err.drm")) sawDrm = true;
                IPodState s = ipodState();
                return sawDrm && s != null && s.index == 2 && ownIPodPlaying() ? "" : null;
            }
        });
        steps.add(new Step("ipod-fim-da-fila-para", 30) {

            @Override
            String tick(int t) {
                IPodState s = ipodState();
                S2CPortableSources.Entry e = ipodEntry(true);
                return s != null && !s.on && (e == null || e.url.isEmpty()) ? "" : null;
            }
        });
        steps.add(new Step("ipod-youtube-pelo-espelho", 45) {

            @Override
            void start() {
                sendIPod(C2SIPodAction.Action.ADD, 0, IPOD_VIDEO);
            }

            @Override
            String tick(int t) {
                IPodState s = ipodState();
                if (s == null || s.queue.size() != 4 || s.index != 3) return null;
                return s.current().source == IPodTrack.Source.YOUTUBE && ownIPodPlaying() ? "" : null;
            }
        });
        // O espelho certo: buscou, tocou o "E2E Band - Song One" e nunca tentou a prévia nem o remix.
        steps.add(
            ipodCalls(
                "ipod-espelho-escolhe-a-versao-certa",
                l -> callCount(l, "search") >= 1 && callCount(l, "song-one") >= 1
                    && callCount(l, "remix") == 0
                    && callCount(l, "preview") == 0));
        steps.add(new Step("ipod-faixa-longa", 60) {

            @Override
            void start() {
                sendIPod(C2SIPodAction.Action.ADD, 0, IPOD_LONG);
            }

            @Override
            String tick(int t) {
                IPodState s = ipodState();
                return s != null && s.queue.size() == 5 && s.index == 4 && ownIPodPlaying() ? "" : null;
            }
        });
        steps.add(new Step("gui-do-ipod", 10) {

            @Override
            void start() {
                mc().displayGuiScreen(new GuiIPod(ipodSlot));
            }

            @Override
            String tick(int t) {
                if (t == 38) screenshot("e2e-gui-ipod.png");
                if (t < 40) return null;
                boolean ok = mc().currentScreen instanceof GuiIPod;
                mc().displayGuiScreen(null);
                return ok ? "" : "a tela do iPod não abriu";
            }
        });
        if (expectPeer) steps.add(handshake("ipod-segundo-jogador-ouve", "ipod-on", "ipod-heard"));
        // Pausa: o servidor para de mandar (o feed do relay fica sem frames) e a posição congela.
        steps.add(new Step("ipod-pausa", 20) {

            long frozenAt = -1;
            int stalled;

            @Override
            void start() {
                sendIPod(C2SIPodAction.Action.TOGGLE, 0, "");
            }

            @Override
            String tick(int t) {
                S2CIPodStatus st = ipodStatus();
                S2CPortableSources.Entry e = ipodEntry(true);
                RelayFeed feed = e == null || e.url.isEmpty() ? null : RelayClient.feedForUrl(e.url);
                if (st == null || st.phase != S2CIPodStatus.Phase.PAUSED || feed == null) return null;
                if (feed.status() != com.akashiic.fm.client.audio.AudioFeed.Status.RECONNECTING) return null;
                if (frozenAt < 0) frozenAt = st.positionMs;
                stalled = st.positionMs == frozenAt ? stalled + 1 : 0;
                if (st.positionMs != frozenAt) frozenAt = st.positionMs;
                return stalled >= 40 ? "" : null; // 2 s com a posição parada
            }
        });
        steps.add(new Step("ipod-retoma", 20) {

            @Override
            void start() {
                sendIPod(C2SIPodAction.Action.TOGGLE, 0, "");
            }

            @Override
            String tick(int t) {
                S2CIPodStatus st = ipodStatus();
                S2CPortableSources.Entry e = ipodEntry(true);
                RelayFeed feed = e == null || e.url.isEmpty() ? null : RelayClient.feedForUrl(e.url);
                return st != null && st.phase == S2CIPodStatus.Phase.PLAYING
                    && feed != null
                    && feed.status() == com.akashiic.fm.client.audio.AudioFeed.Status.PLAYING
                    && ownIPodPlaying() ? "" : null;
            }
        });
        if (expectPeer)
            steps.add(handshake("ipod-depois-da-pausa-segundo-jogador-ouve", "ipod-resumed", "ipod-resumed-ok"));
        steps.add(new Step("ipod-fone-colocar", 5) {

            @Override
            void start() {
                say("e2e:headphones on");
            }

            @Override
            String tick(int t) {
                ItemStack helmet = mc().thePlayer.inventory.armorItemInSlot(3);
                return helmet != null && helmet.getItem() instanceof ItemHeadphones ? "" : null;
            }
        });
        if (expectPeer) steps.add(handshake("ipod-fone-segundo-jogador-nao-ouve", "ipod-hp-on", "ipod-hp-ok"));
        steps.add(new Step("ipod-fone-tirar", 5) {

            int seen;

            @Override
            void start() {
                seen = countChat("e2e-result headphones off ok");
                say("e2e:headphones off");
            }

            @Override
            String tick(int t) {
                return countChat("e2e-result headphones off ok") > seen ? "" : null;
            }
        });
        steps.add(new Step("ipod-repetir-e-anterior", 15) {

            int session = -1;

            @Override
            void start() {
                IPodState s = ipodState();
                session = s == null ? -1 : s.session;
                sendIPod(C2SIPodAction.Action.REPEAT, 0, "");
                sendIPod(C2SIPodAction.Action.PREVIOUS, 0, ""); // já tocou mais de 5 s: recomeça a mesma faixa
            }

            @Override
            String tick(int t) {
                IPodState s = ipodState();
                return s != null && s.repeat == IPodState.Repeat.ALL && s.index == 4 && s.session > session ? "" : null;
            }
        });
        // Relay desligado com o iPod tocando (o que o /fm reload faz): para com o motivo; religado, volta sozinho.
        steps.add(new Step("ipod-relay-desligado-para", 20) {

            @Override
            void start() {
                say("e2e:relay off");
            }

            @Override
            String tick(int t) {
                S2CIPodStatus st = ipodStatus();
                S2CPortableSources.Entry e = ipodEntry(true);
                return st != null && st.status.startsWith("akashicfm.ipod.err.no_relay")
                    && (e == null || e.url.isEmpty()) ? "" : null;
            }
        });
        steps.add(new Step("ipod-relay-religado-volta-a-tocar", 45) {

            @Override
            void start() {
                say("e2e:relay on");
            }

            @Override
            String tick(int t) {
                S2CIPodStatus st = ipodStatus();
                IPodState s = ipodState();
                return st != null && st.phase == S2CIPodStatus.Phase.PLAYING
                    && s != null
                    && s.on
                    && s.index == 4
                    && ownIPodPlaying() ? "" : null;
            }
        });
        steps.add(new Step("ipod-parar", 20) {

            String key = "";

            @Override
            void start() {
                S2CPortableSources.Entry e = ipodEntry(true);
                key = e == null ? "" : e.url;
                sendIPod(C2SIPodAction.Action.STOP, 0, "");
            }

            @Override
            String tick(int t) {
                IPodState s = ipodState();
                return s != null && !s.on
                    && ipodEntry(true) == null
                    && (key.isEmpty() || RelayClient.feedForUrl(key) == null) ? "" : null;
            }
        });
        if (expectPeer) steps.add(handshake("ipod-parado-segundo-jogador-silencio", "ipod-gone", "ipod-gone-ok"));
        steps.add(new Step("ipod-auditoria", 10) {

            int seen;

            @Override
            void start() {
                seen = countChat("e2e-result audit");
                say("e2e:audit-tail");
            }

            @Override
            String tick(int t) {
                if (countChat("e2e-result audit") <= seen) return null;
                return chatSaw("ipod=true") ? "" : "a auditoria não registrou o link do iPod";
            }
        });
        steps.add(new Step("ipod-guardar", 10) {

            @Override
            void start() {
                say("/clear " + mc().thePlayer.getCommandSenderName() + " akashicfm:ipod");
            }

            @Override
            String tick(int t) {
                return ipodStack() == null ? "" : null;
            }
        });
    }

    private void addPeerIPodSteps() {
        if (!relayMode()) return;
        steps.add(peerPortable("peer-ouve-o-ipod", "ipod-on", this::hearsOtherIPod, "ipod-heard"));
        steps.add(
            peerPortable("peer-ouve-o-ipod-depois-da-pausa", "ipod-resumed", this::hearsOtherIPod, "ipod-resumed-ok"));
        steps.add(peerPortable("peer-fone-isola-o-ipod", "ipod-hp-on", this::otherIPodSilent, "ipod-hp-ok"));
        steps.add(peerPortable("peer-ipod-parado", "ipod-gone", this::otherIPodSilent, "ipod-gone-ok"));
    }

    // ---- Fase 7a: /fm, bloqueio e auditoria ----

    /** Manda um comando e espera uma linha do chat que contenha {@code expect} (nova, depois do comando). */
    private Step command(String name, String cmd, String expect) {
        return command(name, () -> cmd, () -> expect);
    }

    /** Idem, com comando e texto esperado calculados no início do passo (coordenadas do roteiro). */
    private Step command(String name, java.util.function.Supplier<String> cmd,
        java.util.function.Supplier<String> expect) {
        return new Step(name, 10) {

            int seen;
            String want;

            @Override
            void start() {
                want = expect.get();
                seen = countChat(want);
                say(cmd.get());
            }

            @Override
            String tick(int t) {
                return countChat(want) > seen ? "" : null;
            }
        };
    }

    private void addAdminSteps(boolean expectPeer) {
        steps.add(command("fm-list-transmissores", "/fm list transmitters", "98.7 MHz · Akashic FM"));
        steps.add(offThreadCommand("fm-pelo-rcon", "e2e:rcon", false));
        steps.add(offThreadCommand("fm-pela-ponte-de-chat-roda-na-thread-principal", "e2e:bridge", true));
        steps.add(command("fm-list-radios", () -> "/fm list radios", () -> new Pos(rx, ry, rz).toString()));
        steps.add(new Step("fm-info-do-bloco-olhado", 10) {

            int seen;

            @Override
            void start() {
                // De frente para a rádio, olhando para ela (o /fm info sem coordenadas usa o bloco olhado).
                mc().thePlayer.rotationYaw = 180f;
                mc().thePlayer.rotationPitch = 37f;
                seen = countChat("playing=true");
            }

            @Override
            String tick(int t) {
                if (t == 5) say("/fm info");
                return countChat("playing=true") > seen ? "" : null;
            }
        });
        steps.add(reloadReadsFile());
        steps.add(command("fm-stop-no-transmissor", () -> "/fm stop " + tx + " " + ry + " " + rz, () -> "Stopped."));
        steps.add(new Step("transmissor-parado-pelo-admin", 5) {

            @Override
            String tick(int t) {
                TileTransmitter tr = transmitterAt(tx, ry, rz);
                return tr != null && !tr.state.broadcasting ? "" : null;
            }
        });
        if (expectPeer) {
            steps.add(command("fm-block-segundo-jogador", "/fm block Player2", "Player2 is now blocked"));
            steps.add(handshake("bloqueado-nao-controla", "blocked-peer", "blocked-ok"));
            steps.add(command("fm-unblock-segundo-jogador", "/fm unblock Player2", "Player2 is no longer blocked"));
            steps.add(handshake("desbloqueado-controla-de-novo", "unblocked-peer", "unblocked-ok"));
        }
        steps.add(new Step("auditoria-em-arquivo", 10) {

            @Override
            void start() {
                say("e2e:audit-tail");
            }

            @Override
            String tick(int t) {
                for (String line : chat) {
                    if (!line.contains("e2e-result audit ")) continue;
                    DevE2E.log("auditoria: {}", line);
                    boolean ok = line.contains("url=true") && line.contains("stop=true")
                        && line.contains("reload=true")
                        && (!expectPeer || (line.contains("block=true") && line.contains("unblock=true")));
                    return ok ? "" : "linhas faltando no arquivo de auditoria: " + line;
                }
                return null;
            }
        });
    }

    /**
     * {@code /fm} vindo de outra thread: a resposta tem que voltar a quem mandou. Pela ponte de chat (executeCommand
     * da thread dela), o /fm tem que passar pela fila da thread principal (o contador sobe 1). Pelo RCON, o 1.7.10
     * puro também usaria a fila; com o Hodgepodge (fixRconThreading, no ambiente de dev e no pack GTNH) o comando já
     * chega na principal: as duas coisas valem.
     */
    private Step offThreadCommand(String name, String trigger, boolean mustQueue) {
        return new Step(name, 20) {

            static final String RESULT = "e2e-result rcon ";
            int before = -1, seen, nextAsk = -1;

            @Override
            void start() {
                ask();
            }

            private void ask() {
                seen = countChat(RESULT);
                say("e2e:rcon-result");
            }

            @Override
            String tick(int t) {
                if (nextAsk >= 0) {
                    if (t < nextAsk) return null;
                    nextAsk = -1;
                    ask();
                    return null;
                }
                String line = chatAfter(RESULT, seen);
                if (line == null) return null;
                int runs = intField(line, "queuedRuns=");
                if (before < 0) {
                    before = runs;
                    say(trigger + " fm list transmitters");
                    nextAsk = t + 10;
                    return null;
                }
                if (!line.contains(" done ")) {
                    nextAsk = t + 10; // ainda rodando: pergunta de novo daqui a meio segundo
                    return null;
                }
                DevE2E.log("{}: fila +{}: {}", trigger, runs - before, line);
                if (mustQueue ? runs != before + 1 : runs < before || runs > before + 1) {
                    return "o /fm de outra thread não passou pela thread principal: " + line;
                }
                return line.contains("98.7 MHz · Akashic FM") ? "" : "resposta sem a lista: " + line;
            }
        };
    }

    /**
     * {@code /fm reload} relê o arquivo de verdade: muda {@code maxPerPlayer} no config/akashicfm.cfg, recarrega,
     * confere o valor novo (e que os valores do roteiro continuam), depois volta o arquivo e confere de novo.
     */
    private Step reloadReadsFile() {
        return new Step("fm-reload-rele-o-arquivo", 40) {

            static final String CONFIG = "e2e-result config ", FILE = "e2e-result config-file maxPerPlayer",
                RELOADED = "Server config reloaded";
            int stage, seen, original = -1;
            String waitFor;

            @Override
            void start() {
                ask("e2e:config-get", CONFIG);
            }

            private void ask(String msg, String needle) {
                waitFor = needle;
                seen = countChat(needle);
                say(msg);
            }

            @Override
            String tick(int t) {
                if (chatSaw("Config reload failed")) return "reload falhou: " + chatAfter("Config reload failed", 0);
                String line = chatAfter(waitFor, seen);
                if (line == null) return null;
                String err;
                switch (stage++) {
                    case 0:
                        original = intField(line, "maxPerPlayer=");
                        if (original < 1) return "config ilegível: " + line;
                        ask("e2e:config-file maxPerPlayer " + (original + 1), FILE);
                        return null;
                    case 1:
                    case 4:
                        if (!line.endsWith(" ok")) return "não editou o arquivo: " + line;
                        ask("/fm reload", RELOADED);
                        return null;
                    case 2:
                    case 5:
                        ask("e2e:config-get", CONFIG);
                        return null;
                    case 3:
                        err = check(line, original + 1);
                        if (err != null) return err;
                        ask("e2e:config-file maxPerPlayer " + original, FILE); // devolve o arquivo
                        return null;
                    default:
                        err = check(line, original);
                        return err == null ? "" : err;
                }
            }

            /** O valor do arquivo entrou e os valores do roteiro (transporte, alcance curto) continuam. */
            private String check(String line, int want) {
                DevE2E.log("reload: {}", line);
                if (!line.contains("maxPerPlayer=" + want + " ")) {
                    return "reload não aplicou maxPerPlayer=" + want + ": " + line;
                }
                boolean relay = !"direct".equals(DevE2E.transport());
                if (!line.contains("baseRange=" + DevE2E.TRANSMITTER_BASE_RANGE + " ")
                    || !line.contains("relay=" + relay + " ")) {
                    return "reload desfez os valores do roteiro: " + line;
                }
                return null;
            }
        };
    }

    /**
     * {@code /fm purge player}: as entradas de bloco carregado e presente ficam (tirar um transmissor carregado do
     * índice o deixaria fora do ar); só saem as que não dá para confirmar (em chunk descarregado). Os blocos do
     * roteiro não têm dono (/setblock): o servidor dá ao jogador um transmissor real e duas entradas fantasmas.
     */
    private Step purgeKeepsLoaded() {
        return new Step("fm-purge-player-mantem-carregados", 20) {

            int phase = -1, seen, total = -1, loaded = -1;
            String purgeMsg;

            @Override
            void start() {
                seen = countChat("e2e-result index-seed ");
                say("e2e:index-seed");
            }

            @Override
            String tick(int t) {
                String me = mc().thePlayer.getCommandSenderName();
                if (phase < 0) {
                    String line = chatAfter("e2e-result index-seed ", seen);
                    if (line == null) return null;
                    if (!line.endsWith(" ok")) return "não preparou o índice: " + line;
                    seen = countChat("e2e-result index ");
                    say("e2e:index-owned");
                    phase = 0;
                    return null;
                }
                if (phase == 0) {
                    String line = chatAfter("e2e-result index ", seen);
                    if (line == null) return null;
                    total = intField(line, "total=");
                    loaded = intField(line, "loaded=");
                    // O transmissor real (carregado) e as duas entradas fantasmas (chunk descarregado).
                    if (loaded < 1 || total - loaded < 2) return "índice antes do purge inesperado: " + line;
                    purgeMsg = "Removed " + (total - loaded) + " index entries owned by " + me;
                    seen = countChat("index entries owned by " + me);
                    say("/fm purge player " + me);
                    phase = 1;
                    return null;
                }
                if (phase == 1) {
                    String line = chatAfter("index entries owned by " + me, seen);
                    if (line == null) return null;
                    if (!line.contains(purgeMsg))
                        return "purge removeu o que não devia: " + line + " (esperado: " + purgeMsg + ")";
                    seen = countChat("e2e-result index ");
                    say("e2e:index-owned");
                    phase = 2;
                    return null;
                }
                String line = chatAfter("e2e-result index ", seen);
                if (line == null) return null;
                DevE2E.log("purge player: antes total={} carregadas={}; depois {}", total, loaded, line);
                return intField(line, "loaded=") == loaded && intField(line, "total=") == loaded ? ""
                    : "índice depois do purge: " + line;
            }
        };
    }

    /** A linha do chat com {@code needle} logo depois das {@code skip} primeiras ocorrências, ou null. */
    private String chatAfter(String needle, int skip) {
        int n = 0;
        for (String line : chat) {
            if (!line.contains(needle)) continue;
            if (n++ == skip) return line;
        }
        return null;
    }

    private static int intField(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return -1;
        int j = i + key.length(), k = j;
        while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
        return k > j ? Integer.parseInt(line.substring(j, k)) : -1;
    }

    // ---- Fase 7b: playlist e mute ----

    /** A reprodução que toca esta rádio neste cliente (no relay, a da estação), ou null. */
    private static AudioEngine.PlaybackInfo infoOf(TileRadio r) {
        String key = r == null ? null : RadioAudioController.playbackKey(r);
        return key == null ? null : AudioEngine.INSTANCE.info(key);
    }

    /** A reprodução da estação do relay desta URL neste cliente, ou null. */
    private static AudioEngine.PlaybackInfo stationInfo(String u) {
        RelayFeed feed = RelayClient.feedForUrl(u);
        return feed == null ? null : AudioEngine.INSTANCE.info(RadioAudioController.relayKey(feed));
    }

    /** Vira o jogador para o centro do bloco (a mira de MuteKeys sai de posY, como a do vanilla). */
    private static void lookAt(double x, double y, double z) {
        double dx = x - mc().thePlayer.posX, dy = y - mc().thePlayer.posY, dz = z - mc().thePlayer.posZ;
        mc().thePlayer.rotationYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc().thePlayer.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /**
     * Playlist com duas faixas curtas: a rádio toca a primeira até o fim (não corta os últimos segundos: o fim só
     * soa nos clientes 1,5 s depois de o servidor terminar o download), passa para a segunda, também para o segundo
     * jogador, e volta para a primeira. No fim, a rádio volta à estação do roteiro.
     */
    private void addPlaylistSteps(boolean expectPeer) {
        steps.add(new Step("playlist-prepara-as-favoritas", 30) {

            @Override
            void start() {
                bringPeer();
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null || t % 4 != 0) return null; // devagar: o limite de ações por segundo vale aqui
                RadioState s = r.state;
                boolean exact = s.stations.size() == 2 && TRACK1.equals(s.stations.get(0))
                    && TRACK2.equals(s.stations.get(1));
                if (!exact && !s.stations.isEmpty() && !TRACK1.equals(s.stations.get(0))) {
                    int last = s.stations.size() - 1;
                    send(Action.REMOVE_STATION, last, s.stations.get(last));
                } else if (s.stations.isEmpty()) {
                    send(Action.ADD_STATION, 0, TRACK1);
                } else if (s.stations.size() == 1) {
                    send(Action.ADD_STATION, 0, TRACK2);
                } else if (!exact) {
                    send(Action.REMOVE_STATION, s.stations.size() - 1, s.stations.get(s.stations.size() - 1));
                } else if (!s.playlist) {
                    send(Action.SET_PLAYLIST, 1, "");
                } else {
                    return "";
                }
                return null;
            }
        });
        final long[] firstPlayingNanos = { 0 };
        final int[] firstSession = { 0 };
        steps.add(new Step("playlist-toca-a-primeira", 30) {

            @Override
            void start() {
                send(Action.PLAY_STATION, 0, TRACK1);
            }

            @Override
            String tick(int t) {
                TileRadio r = radio();
                AudioEngine.PlaybackInfo i = stationInfo(TRACK1);
                if (r == null || !TRACK1.equals(r.state.url) || i == null || !i.playing) return null;
                firstPlayingNanos[0] = System.nanoTime();
                firstSession[0] = r.state.session;
                return "";
            }
        });
        steps.add(new Step("playlist-passa-para-a-segunda-no-fim", 40) {

            long framesOfFirst;

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return "rádio sumiu";
                AudioEngine.PlaybackInfo i = stationInfo(TRACK1);
                if (i != null) framesOfFirst = Math.max(framesOfFirst, i.framesQueued);
                if (!TRACK2.equals(r.state.url)) return null;
                double heard = (System.nanoTime() - firstPlayingNanos[0]) / 1e9;
                double delivered = framesOfFirst / 48000.0;
                DevE2E.log(
                    "playlist: trocou {} s depois de a primeira começar a soar; {} s dela entregues ao OpenAL; sessão {} -> {}",
                    String.format("%.2f", heard),
                    String.format("%.2f", delivered),
                    firstSession[0],
                    r.state.session);
                // Trocando no "terminou" do servidor, a troca viria ~3,5 s antes do fim soar (2,5 s de faixa).
                if (heard < TRACK1_SECONDS - 1.0) return "trocou cedo demais: " + heard + " s";
                if (delivered < TRACK1_SECONDS * 0.9) return "a primeira não tocou inteira: " + delivered + " s";
                return r.state.session > firstSession[0] ? "" : "sessão não mudou";
            }
        });
        steps.add(new Step("playlist-segunda-toca", 30) {

            @Override
            String tick(int t) {
                AudioEngine.PlaybackInfo i = stationInfo(TRACK2);
                return i != null && i.playing ? "" : null;
            }
        });
        if (expectPeer) steps.add(handshake("playlist-segundo-jogador-ouve", "playlist-2", "playlist-ok"));
        steps.add(new Step("gui-da-radio-com-playlist", 10) {

            @Override
            void start() {
                mc().displayGuiScreen(new GuiRadio(rx, ry, rz));
            }

            @Override
            String tick(int t) {
                if (t == 28) screenshot("e2e-gui-playlist.png");
                if (t < 30) return null;
                boolean ok = mc().currentScreen instanceof GuiRadio;
                mc().displayGuiScreen(null);
                TileRadio r = radio();
                String waila = r == null ? ""
                    : RadioInfo.lines(r)
                        .toString();
                DevE2E.log("waila com playlist: {}", waila);
                if (!waila.contains("Playlist: 2")) return "waila sem a playlist: " + waila;
                return ok ? "" : "a GUI da rádio não abriu";
            }
        });
        steps.add(new Step("playlist-volta-para-a-primeira", 40) {

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null || !TRACK1.equals(r.state.url) || r.state.session <= firstSession[0] + 1) return null;
                AudioEngine.PlaybackInfo i = stationInfo(TRACK1);
                return i != null && i.playing ? "" : null;
            }
        });
        steps.add(new Step("playlist-desliga-e-volta-a-estacao", 45) {

            @Override
            String tick(int t) {
                if (t == 0) send(Action.SET_PLAYLIST, 0, "");
                if (t == 6) send(Action.SET_URL, 0, url);
                TileRadio r = radio();
                if (t < 6 || r == null || r.state.playlist || !url.equals(r.state.url) || !r.state.playing) return null;
                AudioEngine.PlaybackInfo i = infoOf(r);
                return i != null && i.playing ? "" : null;
            }
        });
    }

    private void addPeerPlaylistSteps() {
        steps.add(new Step("peer-ouve-a-playlist", 900) {

            boolean heardSecond;

            @Override
            String tick(int t) {
                // A segunda faixa dura 3 s: vale ter ouvido a qualquer momento (o anúncio pode chegar depois).
                AudioEngine.PlaybackInfo i = stationInfo(TRACK2);
                if (i != null && i.playing) heardSecond = true;
                if (!heardSecond || !chatSaw("e2e:main playlist-2")) return null;
                say("e2e:peer playlist-ok");
                return "";
            }
        });
    }

    /**
     * Teclas de silenciar: tudo (o servidor para de mandar e o config é salvo) e só o que se olha (uma rádio, ou um
     * transmissor e as rádios sintonizadas na estação dele), com uma segunda rádio tocando para provar que só a
     * escolhida some.
     */
    private void addMuteSteps() {
        final java.io.File config = new java.io.File(mc().mcDataDir, "config/akashicfm.cfg");
        steps.add(new Step("silenciar-tudo", 20) {

            int quietAt = -1, seen;

            @Override
            void start() {
                if (!MuteKeys.toggleAll(mc())) DevE2E.log("silenciar-tudo: já estava silenciado?");
            }

            @Override
            String tick(int t) {
                if (FmConfig.Client.enableAudio) return "a tecla não desligou o áudio";
                boolean quiet = AudioEngine.INSTANCE.activeCount() == 0 && liveDirectThreads() == 0
                    && (!relayMode() || RelayClient.feedForUrl(url) == null);
                if (!quiet) return null;
                if (quietAt < 0) {
                    // O servidor tirou o jogador da audiência: os bytes mandados a ele param de crescer.
                    quietAt = t;
                    seen = relayResults().size();
                    say("e2e:relay-stats");
                    return null;
                }
                if (t == quietAt + 40) say("e2e:relay-stats");
                List<long[]> res = relayResults();
                if (res.size() < seen + 2) return null;
                long grew = res.get(seen + 1)[0] - res.get(seen)[0];
                String text = readText(config);
                DevE2E.log("silenciado: {} bytes em 2 s; config salvo={}", grew, text.contains("B:enableAudio=false"));
                if (relayMode() && grew > 0) return "o servidor continuou mandando: " + grew + " bytes";
                return text.contains("B:enableAudio=false") ? "" : "o config não foi salvo";
            }
        });
        steps.add(new Step("silenciar-tudo-desfaz", 45) {

            @Override
            void start() {
                MuteKeys.toggleAll(mc());
            }

            @Override
            String tick(int t) {
                if (!FmConfig.Client.enableAudio) return "a tecla não religou o áudio";
                AudioEngine.PlaybackInfo i = infoOf(radio());
                if (i == null || !i.playing) return null;
                return readText(config).contains("B:enableAudio=true") ? "" : "o config não foi salvo";
            }
        });
        steps.add(new Step("segunda-radio-tocando", 45) {

            @Override
            void start() {
                // Calculada aqui: na montagem do roteiro a rádio ainda não tem posição.
                r2x = rx + 2;
                r2z = rz + 2;
            }

            @Override
            String tick(int t) {
                if (t == 0) say("/setblock " + r2x + " " + ry + " " + r2z + " air");
                if (t == 5) say("/setblock " + r2x + " " + ry + " " + r2z + " akashicfm:radio 3");
                if (t == 15) sendTo(r2x, ry, r2z, Action.PLAY, 0, url2);
                if (t == 20) say("/tp " + (rx + 0.5) + " " + ry + " " + (rz + 5.5));
                TileEntity te = mc().theWorld.getTileEntity(r2x, ry, r2z);
                if (t < 30 || !(te instanceof TileRadio)) return null;
                AudioEngine.PlaybackInfo second = infoOf((TileRadio) te), first = infoOf(radio());
                return second != null && second.playing && first != null && first.playing ? "" : null;
            }
        });
        steps.add(new Step("silenciar-a-radio-olhada", 20) {

            String key;

            @Override
            String tick(int t) {
                if (t == 5) lookAt(rx + 0.5, ry + 0.5, rz + 0.5);
                if (t == 7) key = MuteKeys.toggleTarget(mc());
                if (t < 7) return null;
                if (!"akashicfm.mute.radio_on".equals(key)) return "a mira não pegou a rádio: " + key;
                TileEntity te = mc().theWorld.getTileEntity(r2x, ry, r2z);
                AudioEngine.PlaybackInfo first = infoOf(radio()),
                    second = te instanceof TileRadio ? infoOf((TileRadio) te) : null;
                if (first != null || second == null || !second.playing) return null; // só a olhada some
                String waila = RadioInfo.lines(radio())
                    .toString();
                return waila.contains("Muted for you") ? "" : "waila sem o silenciada: " + waila;
            }
        });
        steps.add(new Step("gui-da-radio-silenciada", 10) {

            @Override
            void start() {
                mc().displayGuiScreen(new GuiRadio(rx, ry, rz));
            }

            @Override
            String tick(int t) {
                if (t == 28) screenshot("e2e-gui-silenciada.png");
                if (t < 30) return null;
                boolean ok = mc().currentScreen instanceof GuiRadio;
                mc().displayGuiScreen(null);
                return ok ? "" : "a GUI da rádio não abriu";
            }
        });
        steps.add(new Step("silenciar-a-radio-olhada-desfaz", 45) {

            String key;

            @Override
            String tick(int t) {
                if (t == 2) lookAt(rx + 0.5, ry + 0.5, rz + 0.5);
                if (t == 4) key = MuteKeys.toggleTarget(mc());
                if (t < 4) return null;
                if (!"akashicfm.mute.radio_off".equals(key)) return "não desfez: " + key;
                AudioEngine.PlaybackInfo first = infoOf(radio());
                return first != null && first.playing ? "" : null;
            }
        });
        steps.add(new Step("silenciar-o-transmissor-olhado", 20) {

            String key;

            @Override
            String tick(int t) {
                // O "Perto FM" (desligado, mas com a URL url2): a segunda rádio, que toca url2, some.
                if (t == 2) lookAt(rx - 2.5, ry + 0.5, rz + 0.5);
                if (t == 4) key = MuteKeys.toggleTarget(mc());
                if (t < 4) return null;
                if (!"akashicfm.mute.station_on".equals(key)) return "a mira não pegou o transmissor: " + key;
                TileEntity te = mc().theWorld.getTileEntity(r2x, ry, r2z);
                AudioEngine.PlaybackInfo first = infoOf(radio()),
                    second = te instanceof TileRadio ? infoOf((TileRadio) te) : null;
                if (second != null || first == null || !first.playing) return null;
                TileTransmitter tr = transmitterAt(rx - 3, ry, rz);
                String waila = tr == null ? ""
                    : RadioInfo.lines(tr)
                        .toString();
                return waila.contains("Station muted for you") ? "" : "waila do transmissor: " + waila;
            }
        });
        steps.add(new Step("silenciar-o-transmissor-desfaz-e-limpa", 45) {

            String key;

            @Override
            String tick(int t) {
                if (t == 2) lookAt(rx - 2.5, ry + 0.5, rz + 0.5);
                if (t == 4) key = MuteKeys.toggleTarget(mc());
                if (t < 4) return null;
                if (!"akashicfm.mute.station_off".equals(key)) return "não desfez: " + key;
                TileEntity te = mc().theWorld.getTileEntity(r2x, ry, r2z);
                AudioEngine.PlaybackInfo second = te instanceof TileRadio ? infoOf((TileRadio) te) : null;
                if (second == null || !second.playing) return null;
                if (ClientMutes.any()) return "sobrou algo silenciado";
                say("/setblock " + r2x + " " + ry + " " + r2z + " air");
                return "";
            }
        });
    }

    /** Pede ao servidor uma chamada do OC e espera a resposta que contém {@code expect}. */
    private Step ocCall(String name, java.util.function.Supplier<String> call, String expect,
        java.util.function.Predicate<TileRadio> after) {
        return new Step(name, 15) {

            int seen;

            @Override
            void start() {
                seen = countChat("e2e-result oc ");
                say(call.get());
            }

            @Override
            String tick(int t) {
                String line = chatAfter("e2e-result oc ", seen);
                if (line == null) return null;
                if (!line.contains(expect)) return "resposta do OC: " + line + " (esperado: " + expect + ")";
                TileRadio r = radio();
                if (after != null && (r == null || !after.test(r))) return t > 40 ? "a rádio não mudou: " + line : null;
                DevE2E.log("oc: {}", line);
                return "";
            }
        };
    }

    /**
     * OpenComputers no ambiente de dev: o driver acha a rádio como um Adaptador acharia, e as chamadas pelo próprio OC
     * mudam a rádio de verdade, com a escala do OpenFM, a política de URL e a recusa de bloco privado.
     */
    private void addOcSteps() {
        final int[] volumeBefore = { -1 };
        steps.add(ocCall("oc-componente-openfm-radio", () -> {
            volumeBefore[0] = radio() == null ? -1 : radio().state.volume;
            return "e2e:oc " + rx + " " + ry + " " + rz + " greet";
        }, "openfm_radio [Lasciate ogne speranza", null));
        steps.add(
            ocCall(
                "oc-volume-na-escala-do-openfm",
                () -> "e2e:oc " + rx + " " + ry + " " + rz + " setVol 5",
                "openfm_radio [0.5]",
                r -> r.state.volume == 50));
        steps.add(
            ocCall(
                "oc-texto-da-tela",
                () -> "e2e:oc " + rx + " " + ry + " " + rz + " setScreenText OC",
                "openfm_radio [true]",
                r -> "OC".equals(r.state.screenText)));
        steps.add(
            ocCall(
                "oc-tocando",
                () -> "e2e:oc " + rx + " " + ry + " " + rz + " isPlaying",
                "openfm_radio [true]",
                r -> r.state.playing));
        steps.add(
            ocCall(
                "oc-url-interna-recusada",
                () -> "e2e:oc " + rx + " " + ry + " " + rz + " setURL http://10.0.0.1/live",
                "openfm_radio [false, URL refused",
                r -> url.equals(r.state.url)));
        steps.add(
            ocCall(
                "oc-radio-privada-recusada",
                () -> "e2e:oc-private " + rx + " " + ry + " " + rz + " setVol 3",
                "openfm_radio [false, private block",
                r -> r.state.volume == 50));
        steps.add(
            ocCall(
                "oc-transmissor",
                () -> "e2e:oc " + (rx - 3) + " " + ry + " " + rz + " getFrequency",
                "akashicfm_transmitter [98.7]",
                null));
        steps.add(new Step("oc-devolve-a-radio", 10) {

            @Override
            String tick(int t) {
                if (t == 0) {
                    send(Action.SET_SCREEN_TEXT, 0, "");
                    if (volumeBefore[0] >= 0) send(Action.SET_VOLUME, volumeBefore[0], "");
                }
                TileRadio r = radio();
                return r != null && r.state.screenText.isEmpty()
                    && (volumeBefore[0] < 0 || r.state.volume == volumeBefore[0]) ? "" : null;
            }
        });
    }

    private static String readText(java.io.File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private void addPeerAdminSteps() {
        steps.add(new Step("peer-bloqueado-e-recusado", 900) {

            boolean sent;

            @Override
            String tick(int t) {
                if (!chatSaw("e2e:main blocked-peer")) return null;
                if (!sent) {
                    notices.clear();
                    send(Action.SET_VOLUME, 33, "");
                    sent = true;
                }
                if (!notices.contains("akashicfm.notice.blocked")) return null;
                TileRadio r = radio();
                if (r != null && r.state.volume == 33) return "bloqueado mudou o volume";
                say("e2e:peer blocked-ok");
                return "";
            }
        });
        steps.add(new Step("peer-desbloqueado-controla", 900) {

            int sentAt = -1;

            @Override
            String tick(int t) {
                if (!chatSaw("e2e:main unblocked-peer")) return null;
                if (sentAt < 0) {
                    send(Action.SET_VOLUME, 40, "");
                    sentAt = t;
                }
                TileRadio r = radio();
                if (r == null || r.state.volume != 40) return null;
                say("e2e:peer unblocked-ok");
                return "";
            }
        });
    }

    // ---- Fase 4: oclusão e reverb ----

    /** Ganho médio aplicado com o corredor livre (referência para comparar com as paredes). */
    private float openGain = Float.NaN;

    private void fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        say("e2e:fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " " + block);
    }

    /** Parede de 1 bloco entre o ouvinte e a rádio, atravessando o corredor inteiro. */
    private void wall(String block) {
        fill(rx - 2, ry - 1, rz + 4, rx + 2, ry + 4, rz + 4, block);
    }

    /**
     * Corredor limpo ao sul da rádio (ar, chão de pedra), ouvinte a 7 blocos dela; paredes de lã e de vidro no
     * meio; depois uma sala de pedra fechada em volta do ouvinte e o corredor aberto de novo. Mede a oclusão de
     * cada voz, o ganho aplicado (sem EFX a oclusão vira ganho; com EFX vira low-pass e o ganho fica) e o reverb
     * lido de volta do OpenAL.
     */
    private void addAcousticSteps() {
        steps.add(new Step("acustica-corredor-livre", 30) {

            int stable;

            @Override
            String tick(int t) {
                if (t == 0) {
                    fill(rx - 2, ry, rz + 1, rx + 2, ry + 4, rz + 10, "minecraft:air");
                    fill(rx - 2, ry - 1, rz + 1, rx + 2, ry - 1, rz + 10, "minecraft:stone");
                }
                if (t == 10) say("/tp " + (rx + 0.5) + " " + ry + " " + (rz + 7.5));
                if (t < 60) return null;
                double dz = mc().thePlayer.posZ - (rz + 7.5);
                AudioEngine.PlaybackInfo i = info();
                if (i == null || !i.playing || Math.abs(dz) > 0.2) return null;
                stable = allWithin(i.occlusions, 0, 0.02) ? stable + 1 : 0;
                if (stable < 20) return null;
                openGain = avg(i.gains);
                DevE2E.log(
                    "corredor livre: oclusão={} ganho={} efx={} ({})",
                    fmt(i.occlusions),
                    fmt(i.gains),
                    AudioEngine.liveEfxObjects(),
                    efxExpected() ? "com EFX" : "sem EFX");
                return efxObjectsAsExpected() ? "" : "objetos EFX inesperados: " + AudioEngine.liveEfxObjects();
            }
        });
        steps.add(occlusionStep("oclusao-parede-de-la", "minecraft:wool", 0.85, 0.95));
        steps.add(occlusionStep("oclusao-parede-de-vidro", "minecraft:glass", 0.07, 0.15));
        steps.add(occlusionStep("oclusao-sem-parede", "minecraft:air", 0, 0.02));
        steps.add(new Step("reverb-sala-de-pedra", 30) {

            int stable;

            @Override
            String tick(int t) {
                if (t == 0) fill(rx - 2, ry - 1, rz + 4, rx + 2, ry + 3, rz + 10, "minecraft:stone");
                if (t == 2) fill(rx - 1, ry, rz + 5, rx + 1, ry + 2, rz + 9, "minecraft:air");
                if (t < 60) return null;
                AudioEngine.PlaybackInfo i = info();
                if (i == null || !i.playing) return null;
                // A rádio ficou do lado de fora: 1 bloco de pedra (0,6) entre ela e o ouvinte.
                boolean occluded = allWithin(i.occlusions, 0.5, 0.75);
                AudioEngine.EfxInfo e = AudioEngine.INSTANCE.efxInfo();
                boolean ok;
                if (efxExpected()) {
                    ok = occluded && e != null
                        && e.effectLoaded
                        && e.slotGain > 0.6f
                        && e.slotGain < 0.8f
                        && e.decay > 0.3f
                        && e.decay < 1.5f;
                } else {
                    ok = occluded && e == null && AudioEngine.liveEfxObjects() == 0;
                }
                stable = ok ? stable + 1 : 0;
                if (stable < 20) return null;
                DevE2E.log(
                    "sala de pedra: sondagem={} oclusão da rádio={} {}",
                    RoomProbe.INSTANCE.target(),
                    fmt(i.occlusions),
                    e == null ? "sem EFX (reverb indisponível, como esperado)"
                        : "slot: ganho=" + String.format("%.3f", e.slotGain)
                            + " decaimento="
                            + String.format("%.2f", e.decay)
                            + "s saída="
                            + e.sendIndex
                            + "/"
                            + e.maxSends
                            + " enviado="
                            + e.pushed);
                return "";
            }
        });
        steps.add(new Step("reverb-corredor-aberto", 30) {

            int stable;

            @Override
            String tick(int t) {
                if (t == 0) fill(rx - 2, ry, rz + 4, rx + 2, ry + 3, rz + 10, "minecraft:air");
                if (t < 60) return null;
                AudioEngine.PlaybackInfo i = info();
                if (i == null || !i.playing) return null;
                AudioEngine.EfxInfo e = AudioEngine.INSTANCE.efxInfo();
                boolean ok = allWithin(i.occlusions, 0, 0.02)
                    && (efxExpected() ? e != null && (!e.effectLoaded || e.slotGain < 0.25f) : e == null);
                stable = ok ? stable + 1 : 0;
                if (stable < 20) return null;
                DevE2E.log(
                    "corredor aberto: sondagem={} {} oclusão: {} raios/tick={} traçados={}",
                    RoomProbe.INSTANCE.target(),
                    e == null ? "sem EFX"
                        : "slot: ganho=" + String.format("%.3f", e.slotGain) + " carregado=" + e.effectLoaded,
                    fmt(i.occlusions),
                    OcclusionField.INSTANCE.lastSteps(),
                    OcclusionField.INSTANCE.lastTraced());
                return "";
            }
        });
    }

    /**
     * Parede de {@code block} no corredor: toda voz com oclusão em [lo, hi] por 1 s. Com EFX o ganho aplicado não
     * muda (o abafado é do low-pass); sem EFX ele cai para {@link OcclusionTracer#gainOnly}.
     */
    private Step occlusionStep(String name, String block, double lo, double hi) {
        return new Step(name, 20) {

            int stable;

            @Override
            String tick(int t) {
                if (t == 0) wall(block);
                if (t < 20) return null;
                AudioEngine.PlaybackInfo i = info();
                if (i == null || !i.playing) return null;
                float occ = avg(i.occlusions), gain = avg(i.gains);
                double expectedRatio = efxExpected() ? 1.0 : OcclusionTracer.gainOnly(occ);
                double ratio = gain / openGain;
                boolean ok = allWithin(i.occlusions, lo, hi) && Math.abs(ratio - expectedRatio) < 0.05;
                stable = ok ? stable + 1 : 0;
                if (stable < 20) return null;
                DevE2E.log(
                    "{}: oclusão={} ganho={} (razão {} , esperada {}) efx={}",
                    block,
                    fmt(i.occlusions),
                    fmt(i.gains),
                    String.format("%.3f", ratio),
                    String.format("%.3f", expectedRatio),
                    AudioEngine.liveEfxObjects());
                return "";
            }
        };
    }

    // ---- Fase 5: tocando agora, aviso, espectro e WAILA ----

    private void addNowPlayingSteps() {
        steps.add(new Step("titulo-chega", 40) {

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return null;
                String title = NowPlaying.title(r);
                if (title.isEmpty()) return null;
                // No relay o título vem do servidor (estado da rádio); no direto, do próprio stream neste cliente.
                DevE2E.log(
                    "título: '{}' (estado da rádio: '{}', transporte {})",
                    title,
                    r.state.nowPlaying,
                    r.state.transport);
                if (relayMode() && r.state.nowPlaying.isEmpty()) return "relay sem título no estado da rádio";
                return "";
            }
        });
        steps.add(new Step("aviso-tocando-agora", 30) {

            int deliveredAt = -1, visibleTicks;

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return null;
                String title = NowPlaying.title(r);
                // 1) O aviso de verdade já entregou o título (no início veio o host; o título vem até 3 s depois).
                if (deliveredAt < 0) {
                    if (title.isEmpty() || !title.equals(NowPlayingMessage.INSTANCE.lastText())) return null;
                    deliveredAt = t;
                    DevE2E.log(
                        "aviso entregue: '{}' ({} avisos até agora)",
                        NowPlayingMessage.INSTANCE.lastText(),
                        NowPlayingMessage.INSTANCE.shownCount());
                    // 2) Esquece o que foi anunciado (como quem para de ouvir e volta): o mesmo caminho mostra de novo.
                    RadioAudioController.resetNowPlaying();
                    return null;
                }
                boolean ok = title.equals(NowPlayingMessage.INSTANCE.lastText())
                    && NowPlayingMessage.INSTANCE.visible(System.currentTimeMillis());
                visibleTicks = ok ? visibleTicks + 1 : 0;
                if (visibleTicks == 10) screenshot("e2e-tocando-agora.png");
                if (visibleTicks < 10) return null;
                DevE2E.log("aviso reapareceu {} ticks depois de esquecer", t - deliveredAt - 10);
                return "";
            }
        });
        steps.add(new Step("espectro-tocando", 10) {

            float levelMax, sumMax;

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return null;
                float[] bands = new float[SpectrumAnalyzer.BANDS];
                float level = AudioEngine.INSTANCE.visuals(RadioAudioController.playbackKey(r), bands);
                if (level < 0) return null;
                float sum = 0;
                for (float b : bands) sum += b;
                levelMax = Math.max(levelMax, level);
                sumMax = Math.max(sumMax, sum);
                if (t < 5 * TPS) return null;
                DevE2E.log(
                    "espectro: nível máx={} soma das bandas máx={} agora={}",
                    String.format("%.2f", levelMax),
                    String.format("%.2f", sumMax),
                    fmt(bands));
                // Música real: nível bem acima de -42 dBFS (0,3) e energia em várias bandas.
                return levelMax > 0.3f && sumMax > 2f ? "" : "espectro vazio tocando música";
            }
        });
        steps.add(new Step("waila-da-radio", 5) {

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (r == null) return null;
                List<String> lines = RadioInfo.lines(r);
                DevE2E.log("waila da rádio: {}", lines);
                String host = NowPlaying.hostOf(r.state.url), title = NowPlaying.title(r);
                boolean hasHost = false, hasTitle = title.isEmpty();
                for (String l : lines) {
                    if (l.contains(host)) hasHost = true;
                    if (!title.isEmpty() && l.contains(title)) hasTitle = true;
                }
                return hasHost && hasTitle ? "" : "linhas incompletas: " + lines;
            }
        });
    }

    /** O relay toca em sincronia: erro suavizado abaixo de 15 ms por 2 s seguidos (dois clientes: < 30 ms entre si). */
    private Step syncCheck(String name) {
        return new Step(name, 30) {

            int okTicks;

            @Override
            String tick(int t) {
                AudioEngine.PlaybackInfo i = info();
                if (i == null || !i.playing || Double.isNaN(i.syncErrorMs)) return null;
                okTicks = Math.abs(i.syncErrorMs) < 15 ? okTicks + 1 : 0;
                if (okTicks < 40) return null;
                // No E2E servidor e clientes rodam na mesma máquina: o nanoTime é o mesmo relógio, então o offset
                // verdadeiro é 0 e o estimado é o próprio erro da estimativa. Erro real = medido + offset estimado.
                DevE2E.log(
                    "sincronia: erro={} ms offsetEstimado={} ms erroReal={} ms pitch={} ressincronizações={} menor RTT={} ms",
                    String.format("%.2f", i.syncErrorMs),
                    String.format("%.2f", ClockSync.offsetMs()),
                    String.format("%.2f", i.syncErrorMs + ClockSync.offsetMs()),
                    String.format("%.5f", i.pitch),
                    i.resyncs,
                    String.format("%.2f", ClockSync.bestRttMs()));
                say("e2e:sync " + String.format("%.2f", i.syncErrorMs));
                // Mesma máquina: o erro real (com offset verdadeiro 0) também precisa ficar pequeno; um relógio mal
                // estimado (ex.: carimbo no tick em vez da chegada na rede) erra até ±25 ms.
                double real = i.syncErrorMs + ClockSync.offsetMs();
                return Math.abs(real) < 20 ? "" : "erro real de sincronia " + String.format("%.1f", real) + " ms";
            }
        };
    }

    private Step soundReload(String name) {
        return new Step(name, 40) {

            int gen0;

            @Override
            void start() {
                gen0 = AudioEngine.INSTANCE.generation();
                DevE2E.log("antes do recarregamento: geração={} {}", gen0, alCounters());
                reloadSoundSystem();
            }

            @Override
            String tick(int t) {
                // Destruição + criação do contexto: a geração anda pelo menos 2.
                if (AudioEngine.INSTANCE.generation() < gen0 + 2) return null;
                AudioEngine.PlaybackInfo i = info();
                if (i == null || !i.playing || i.voices == 0 || !alInvariantsHold()) return null;
                // O EFX do contexto antigo foi solto com ele e o novo foi criado (ou continua sem, no modo sem EFX).
                if (!efxObjectsAsExpected()) return null;
                DevE2E.log("depois do recarregamento: geração={} {}", AudioEngine.INSTANCE.generation(), alCounters());
                return "";
            }
        };
    }

    // ---- Cenário "soak": 10 min tocando, com caixa ligando/desligando e recarregamentos do som ----

    private void buildSoak() {
        steps.add(join());
        steps.add(new Step("preparar", 30) {

            @Override
            String tick(int t) {
                ChunkCoordinates spawn = mc().theWorld.getSpawnPoint();
                rx = spawn.posX + 2;
                ry = spawn.posY;
                rz = spawn.posZ;
                sx = rx + 3;
                sy = ry;
                sz = rz;
                if (t == 0) say("/tp " + (spawn.posX + 0.5) + " " + spawn.posY + " " + (spawn.posZ + 0.5));
                if (t == 10) say("/setblock " + rx + " " + ry + " " + rz + " akashicfm:radio 3");
                if (t == 12) say("/setblock " + sx + " " + sy + " " + sz + " akashicfm:speaker 3");
                if (t == 14) say("/give " + mc().thePlayer.getCommandSenderName() + " akashicfm:tuner");
                if (t == 40) send(Action.PLAY, 0, url);
                if (t < 40) return null;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack st = mc().thePlayer.inventory.getStackInSlot(slot);
                    if (st != null && st.getItem() instanceof ItemTuner) mc().thePlayer.inventory.currentItem = slot;
                }
                AudioEngine.PlaybackInfo i = info();
                return i != null && i.playing && speaker() != null ? "" : null;
            }
        });
        steps.add(new Step("soak-10-minutos", 11 * 60) {

            static final int DURATION = 10 * 60 * TPS;
            long heapStart;
            int under0, samples, violations, toggles, reloads, lastChange, linkAt = -1;
            String firstViolation = "";

            @Override
            void start() {
                heapStart = usedHeapAfterGc();
                AudioEngine.PlaybackInfo i = info();
                under0 = i == null ? 0 : i.underruns;
                DevE2E.log("soak: início heap={} MB {}", heapStart >> 20, alCounters());
            }

            @Override
            String tick(int t) {
                if (t > 0 && t % (30 * TPS) == 0 && t < DURATION) { // caixa: liga e desliga em alternância
                    TileRadio r = radio();
                    if (r != null && r.state.speakers.isEmpty()) {
                        rightClick(sx, sy, sz);
                        linkAt = t + 10;
                    } else {
                        send(Action.UNLINK_ALL_SPEAKERS, 0, "");
                    }
                    toggles++;
                    lastChange = t;
                }
                if (t == linkAt) rightClick(rx, ry, rz);
                if (t % (150 * TPS) == 75 * TPS && t < DURATION) { // recarrega o som a cada 2,5 min
                    reloadSoundSystem();
                    reloads++;
                    lastChange = t;
                }
                if (t % (10 * TPS) == 5 * TPS && t - lastChange > 6 * TPS) { // amostra com tudo estável
                    samples++;
                    boolean ok = alInvariantsHold() && liveDirectThreads() == AudioEngine.INSTANCE.activeCount()
                        && AudioEngine.INSTANCE.activeCount() == 1
                        && efxObjectsAsExpected();
                    if (!ok) {
                        violations++;
                        if (firstViolation.isEmpty()) firstViolation = "t=" + t / TPS + "s " + alCounters();
                    }
                    if (samples % 6 == 0) DevE2E.log("soak t={}s {}", t / TPS, alCounters());
                }
                if (t < DURATION) return null;
                long heapEnd = usedHeapAfterGc();
                AudioEngine.PlaybackInfo i = info();
                int underruns = i == null ? -1 : i.underruns - under0;
                DevE2E.log(
                    "soak: fim amostras={} violações={} trocas={} recarregamentos={} underruns={} heap {} -> {} MB",
                    samples,
                    violations,
                    toggles,
                    reloads,
                    underruns,
                    heapStart >> 20,
                    heapEnd >> 20);
                if (violations > 0) return "invariante quebrada: " + firstViolation;
                // 60 amostras possíveis (a cada 10 s); ~24 caem a menos de 6 s de uma troca e são puladas.
                if (samples < 30) return "poucas amostras: " + samples;
                if (heapEnd - heapStart > 64L << 20) return "heap cresceu " + ((heapEnd - heapStart) >> 20) + " MB";
                return "";
            }
        });
        cleanupIndex = steps.size();
        steps.add(disconnect("desconectar-final"));
    }

    // ---- Cenário "acoustic": segmentos separados por silêncio, para medir o áudio de saída gravado ----

    /**
     * Com o OpenAL Soft gravando a mixagem num WAV (backend "wave", ver tools/e2e/run-acoustic.sh): corredor
     * aberto, parede de lã, de vidro, aberto de novo, sala de pedra (parando a rádio dentro dela para a cauda do
     * reverb) e parada no corredor aberto (referência sem cauda). Entre os segmentos a rádio fica muda por 2 s,
     * então o analisador acha cada segmento pelo silêncio. Marcas "acoustic-mark" no log dão a ordem e os tempos.
     */
    private void buildAcoustic() {
        steps.add(join());
        steps.add(new Step("preparar-acustica", 60) {

            @Override
            String tick(int t) {
                if (t == 0) {
                    // Só o som das rádios na gravação.
                    for (SoundCategory c : SoundCategory.values()) {
                        if (c != SoundCategory.MASTER && c != SoundCategory.RECORDS) {
                            mc().gameSettings.setSoundLevel(c, 0f);
                        }
                    }
                    mc().gameSettings.setSoundLevel(SoundCategory.MASTER, 1f);
                    mc().gameSettings.setSoundLevel(SoundCategory.RECORDS, 1f);
                    ChunkCoordinates spawn = mc().theWorld.getSpawnPoint();
                    rx = spawn.posX + 2;
                    ry = spawn.posY;
                    rz = spawn.posZ;
                    say("/tp " + (spawn.posX + 0.5) + " " + spawn.posY + " " + (spawn.posZ + 0.5));
                }
                if (t == 10) say("/setblock " + rx + " " + ry + " " + rz + " akashicfm:radio 3");
                if (t == 60) send(Action.PLAY, 0, url);
                if (t < 60) return null;
                TileRadio r = radio();
                return r != null && r.state.playing ? "" : null;
            }
        });
        steps.add(audioPlaying("acustica-audio-comeca", 45));
        steps.add(new Step("acustica-corredor", 30) {

            int stable;

            @Override
            String tick(int t) {
                if (t == 0) {
                    fill(rx - 2, ry, rz + 1, rx + 2, ry + 4, rz + 10, "minecraft:air");
                    fill(rx - 2, ry - 1, rz + 1, rx + 2, ry - 1, rz + 10, "minecraft:stone");
                }
                if (t == 10) say("/tp " + (rx + 0.5) + " " + ry + " " + (rz + 7.5));
                if (t < 60) return null;
                AudioEngine.PlaybackInfo i = info();
                if (i == null || !i.playing) return null;
                stable = allWithin(i.occlusions, 0, 0.02) ? stable + 1 : 0;
                return stable >= 40 ? "" : null;
            }
        });
        steps.add(acousticSegment("A-aberto", null, false));
        steps.add(acousticSegment("B-la", "minecraft:wool", false));
        steps.add(acousticSegment("C-vidro", "minecraft:glass", false));
        steps.add(acousticSegment("D-aberto", "minecraft:air", false));
        steps.add(acousticSegment("E-sala-de-pedra", "box", true));
        steps.add(new Step("F-aberto-parada", 60) {

            int playingAt = -1;

            @Override
            String tick(int t) {
                if (t == 0) {
                    fill(rx - 2, ry, rz + 4, rx + 2, ry + 3, rz + 10, "minecraft:air");
                    send(Action.SET_VOLUME, VOLUME_ON, "");
                }
                if (t == 20) send(Action.PLAY, 0, "");
                AudioEngine.PlaybackInfo i = info();
                if (playingAt < 0 && t > 20 && i != null && i.playing) {
                    playingAt = t;
                    mark("F-aberto-parada", "playing");
                }
                if (playingAt >= 0 && t == playingAt + 5 * TPS) {
                    logAcousticState("F-aberto-parada", i); // a sala saiu: o reverb do aberto já tem que ser zero
                    mark("F-aberto-parada", "stop");
                    send(Action.STOP, 0, "");
                }
                return playingAt >= 0 && t >= playingAt + 8 * TPS ? "" : null;
            }
        });
        cleanupIndex = steps.size();
        steps.add(disconnect("desconectar-final"));
    }

    private static final int VOLUME_ON = 60;

    private static void mark(String segment, String event) {
        DevE2E.log("acoustic-mark {} {} ms={}", segment, event, System.currentTimeMillis());
    }

    /** Oclusão, ganho e o reverb aplicado agora (para comparar com o que o analisador mede no WAV). */
    private static void logAcousticState(String segment, AudioEngine.PlaybackInfo i) {
        AudioEngine.EfxInfo e = AudioEngine.INSTANCE.efxInfo();
        DevE2E.log(
            "acoustic-state {} oclusão={} ganho={} reverb={}",
            segment,
            i == null ? "-" : fmt(i.occlusions),
            i == null ? "-" : fmt(i.gains),
            e == null ? "sem EFX" : "ganho " + String.format("%.3f", e.slotGain) + " " + e.pushed);
    }

    /**
     * Um segmento: muda a rádio, monta o cenário ({@code null} = nada, "box" = sala de pedra, senão parede do
     * bloco), espera 2 s de silêncio, toca 6 s e registra a oclusão. {@code stopInside}: no fim para a rádio ali
     * dentro (cauda do reverb) em vez de mudar.
     */
    private Step acousticSegment(String name, String scene, boolean stopInside) {
        return new Step(name, 30) {

            @Override
            String tick(int t) {
                if (t == 0) send(Action.SET_VOLUME, 0, "");
                if (t == 5 && scene != null) {
                    if ("box".equals(scene)) {
                        fill(rx - 2, ry - 1, rz + 4, rx + 2, ry + 3, rz + 10, "minecraft:stone");
                        fill(rx - 1, ry, rz + 5, rx + 1, ry + 2, rz + 9, "minecraft:air");
                    } else {
                        wall(scene);
                    }
                }
                if (t == 2 * TPS) {
                    mark(name, "unmute");
                    send(Action.SET_VOLUME, VOLUME_ON, "");
                }
                if (t == 6 * TPS) logAcousticState(name, info());
                if (t == 8 * TPS) {
                    mark(name, stopInside ? "stop" : "mute");
                    send(stopInside ? Action.STOP : Action.SET_VOLUME, 0, "");
                }
                return t >= (stopInside ? 13 : 8) * TPS ? "" : null;
            }
        };
    }

    // ---- Cenário "listen": só entra e confere que ouve uma rádio que já estava tocando (persistência) ----

    private void buildListen() {
        steps.add(join());
        steps.add(new Step("achar-radio-que-ja-tocava", 30) {

            @Override
            String tick(int t) {
                for (TileRadio r : ClientRadioRegistry.snapshot()) {
                    if (r.getWorldObj() == mc().theWorld && r.state.playing) {
                        rx = r.xCoord;
                        ry = r.yCoord;
                        rz = r.zCoord;
                        DevE2E.log(
                            "rádio carregada do disco: {} sessão={} url={}",
                            r.pos(),
                            r.state.session,
                            r.state.url);
                        return "";
                    }
                }
                return null;
            }
        });
        steps.add(audioPlaying("ouvir-radio-persistida", 45));
        cleanupIndex = steps.size();
        steps.add(disconnect("desconectar-final"));
    }

    // ---- Cenário do segundo jogador (não-op) ----

    private void buildPeer() {
        steps.add(join());
        steps.add(new Step("achar-radio-tocando", 900) {

            @Override
            String tick(int t) {
                if (!chatSaw("e2e:main radio-playing")) return null;
                for (TileRadio r : ClientRadioRegistry.snapshot()) {
                    if (r.getWorldObj() == mc().theWorld && r.state.playing) {
                        rx = r.xCoord;
                        ry = r.yCoord;
                        rz = r.zCoord;
                        return "";
                    }
                }
                return null;
            }
        });
        steps.add(new Step("peer-ouve-o-mesmo-audio", 45) {

            final Step inner = audioPlaying("peer-audio", 45);

            @Override
            String tick(int t) {
                String r = inner.tick(t);
                if ("".equals(r)) say("e2e:peer hears");
                return r;
            }
        });
        if (relayMode()) steps.add(syncCheck("peer-sincronia-do-relay"));
        steps.add(new Step("peer-aviso-tocando-agora", 30) {

            @Override
            String tick(int t) {
                if (NowPlayingMessage.INSTANCE.shownCount() == 0) return null;
                DevE2E.log("peer: aviso '{}'", NowPlayingMessage.INSTANCE.lastText());
                return "";
            }
        });
        steps.add(new Step("peer-permissoes", 10) {

            @Override
            void start() {
                lastPerms = null;
                send(Action.REQUEST_PERMS, 0, "");
            }

            @Override
            String tick(int t) {
                S2CRadioPerms p = lastPerms;
                if (p == null) return null;
                // Rádio sem dono (colocada por comando): qualquer jogador controla, só op administra.
                return p.canControl && !p.canAdmin ? "" : "control=" + p.canControl + " admin=" + p.canAdmin;
            }
        });
        steps.add(new Step("peer-sem-admin-e-recusado", 10) {

            @Override
            void start() {
                notices.clear();
                send(Action.SET_ACCESS, RadioAccess.PUBLIC.ordinal(), "");
            }

            @Override
            String tick(int t) {
                if (!notices.contains("akashicfm.notice.no_permission")) return null;
                say("e2e:peer ready");
                return "";
            }
        });
        addPeerFrequencySteps();
        addPeerPortableSteps();
        addPeerIPodSteps();
        addPeerAdminSteps();
        if (relayMode()) addPeerPlaylistSteps();
        steps.add(new Step("peer-ve-a-parada", 900) {

            int stoppedAt = -1;

            @Override
            String tick(int t) {
                TileRadio r = radio();
                if (stoppedAt < 0 && r != null && !r.state.playing) stoppedAt = t;
                if (stoppedAt < 0) return null;
                if (AudioEngine.INSTANCE.activeCount() == 0) {
                    say("e2e:peer stopped-ok");
                    return "";
                }
                return t - stoppedAt > 2 * TPS ? "continuou tocando depois da parada" : null;
            }
        });
        cleanupIndex = steps.size();
        steps.add(disconnect("desconectar-sem-som-orfao"));
    }
}
