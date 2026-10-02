package com.akashiic.fm.server;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.Permissions;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.network.C2SRadioAction;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;
import com.akashiic.fm.server.relay.RelayService;

/**
 * Aplica as ações dos jogadores nas rádios (e despacha as do transmissor). Roda na thread principal do servidor.
 * Ordem das checagens: jogador ainda conectado, chunk já carregado (nunca carrega chunk por causa de um pacote),
 * TE certo, distância de uso, permissão da ação e, por último, validação do conteúdo.
 */
public final class RadioActionHandler {

    /** Mesmo alcance de uso de GUI do vanilla (8 blocos). */
    private static final double MAX_USE_DISTANCE_SQ = 64.0;

    private RadioActionHandler() {}

    public static void handle(EntityPlayerMP player, C2SRadioAction msg) {
        if (player == null || player.isDead || player.playerNetServerHandler == null) return;
        World world = player.worldObj;
        if (world == null || msg.y < 0 || msg.y > 255 || !world.blockExists(msg.x, msg.y, msg.z)) return;
        TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
        if (te instanceof TileTransmitter) {
            TransmitterActionHandler.handle(player, msg, (TileTransmitter) te);
            return;
        }
        if (!(te instanceof TileRadio)) return;
        TileRadio radio = (TileRadio) te;
        if (player.getDistanceSq(msg.x + 0.5, msg.y + 0.5, msg.z + 0.5) > MAX_USE_DISTANCE_SQ) {
            notice(player, radio, true, "akashicfm.notice.too_far", "");
            return;
        }
        RadioState s = radio.state;
        // Bloqueado por um admin (/fm block): só pode olhar.
        boolean blocked = Moderation.isBlocked(player);
        boolean control = !blocked && Permissions.canControl(s, player);
        boolean admin = !blocked && Permissions.canAdmin(s, player);

        switch (msg.action) {
            case REQUEST_PERMS:
                FmNetwork.sendTo(new S2CRadioPerms(msg.x, msg.y, msg.z, control, admin, maxRange()), player);
                return;
            case PLAY:
                if (!control) break;
                // Com URL junto: troca e toca numa ação só (se a URL for recusada, não toca a antiga).
                if (!msg.strArg.isEmpty()) {
                    String url = TextSanitizer.cleanUrl(msg.strArg, RadioLimits.MAX_URL_LENGTH);
                    if (url.isEmpty()) {
                        notice(player, radio, true, "akashicfm.notice.no_url", "");
                        return;
                    }
                    if (!setUrl(player, radio, url)) return;
                }
                startPlaying(player, radio);
                return;
            case STOP:
                if (!control) break;
                if (applyStop(s)) radio.markStateChanged();
                return;
            case SET_URL:
                if (!control) break;
                setUrl(player, radio, msg.strArg);
                return;
            case SET_VOLUME:
                if (!control) break;
                int v = RadioLimits.clamp(msg.intArg, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
                if (v != s.volume) {
                    s.volume = v;
                    radio.markStateChanged();
                }
                return;
            case SET_RANGE:
                if (!control) break;
                int r = RadioLimits.clamp(msg.intArg, RadioLimits.RANGE_MIN, maxRange());
                if (r != s.range) {
                    s.range = r;
                    radio.markStateChanged();
                }
                return;
            case PLAY_STATION:
                if (!control) break;
                if (!isStationAt(s, msg.intArg, msg.strArg)) return;
                if (setUrl(player, radio, s.stations.get(msg.intArg))) {
                    // Favorita é uma URL: sai do modo de frequência (sem tocar a do transmissor no caminho).
                    if (s.mode != TuneMode.URL && setMode(radio, TuneMode.URL)) radio.markStateChanged();
                    startPlaying(player, radio);
                }
                return;
            case ADD_STATION:
                if (!admin) break;
                addStation(player, radio, msg.strArg);
                return;
            case REMOVE_STATION:
                if (!admin) break;
                if (isStationAt(s, msg.intArg, msg.strArg)) {
                    s.stations.remove(msg.intArg);
                    radio.markStateChanged();
                }
                return;
            case SET_SCREEN_TEXT:
                if (!admin) break;
                String text = TextSanitizer.clean(msg.strArg, RadioLimits.MAX_SCREEN_TEXT);
                if (!text.equals(s.screenText)) {
                    s.screenText = text;
                    radio.markStateChanged();
                }
                return;
            case SET_SCREEN_COLOR:
                if (!admin) break;
                int color = msg.intArg & 0xFFFFFF;
                if (color != s.screenColor) {
                    s.screenColor = color;
                    radio.markStateChanged();
                }
                return;
            case SET_ACCESS:
                if (!admin) break;
                RadioAccess access = RadioAccess.byOrdinal(msg.intArg);
                if (access != s.access) {
                    s.access = access;
                    radio.markStateChanged();
                }
                return;
            case SET_REDSTONE_MODE:
                if (!admin) break;
                RedstoneMode mode = RedstoneMode.byOrdinal(msg.intArg);
                if (mode != s.redstoneMode) {
                    s.redstoneMode = mode;
                    s.lastPowered = world.isBlockIndirectlyGettingPowered(msg.x, msg.y, msg.z);
                    radio.markStateChanged();
                }
                return;
            case UNLINK_SPEAKER:
                if (!admin) break;
                if (msg.intArg >= 0 && msg.intArg < s.speakers.size()) {
                    unlinkSpeaker(world, radio, s.speakers.get(msg.intArg));
                    radio.markStateChanged();
                }
                return;
            case UNLINK_ALL_SPEAKERS:
                if (!admin) break;
                if (!s.speakers.isEmpty()) {
                    for (Pos p : s.speakers.toArray(new Pos[0])) unlinkSpeaker(world, radio, p);
                    radio.markStateChanged();
                }
                return;
            case SET_MODE:
                if (!control) break;
                if (setMode(radio, TuneMode.byOrdinal(msg.intArg))) radio.markStateChanged();
                return;
            case SET_FREQUENCY:
                if (!control) break;
                int f = Frequency.clamp(msg.intArg);
                if (f != s.frequency) {
                    s.frequency = f;
                    if (s.mode == TuneMode.FREQUENCY && s.playing) FrequencyService.retune(radio);
                    radio.markStateChanged();
                }
                return;
            default:
                return;
        }
        notice(player, radio, true, blocked ? "akashicfm.notice.blocked" : "akashicfm.notice.no_permission", "");
    }

    /**
     * O índice existe e, se o cliente mandou a URL que via na tela, ainda é ela (a lista pode ter mudado por
     * outro jogador entre o clique e o processamento; sem isto o clique tocaria ou apagaria outra estação).
     */
    static boolean isStationAt(RadioState s, int index, String expected) {
        if (index < 0 || index >= s.stations.size()) return false;
        return expected == null || expected.isEmpty() || expected.equals(s.stations.get(index));
    }

    enum PlayResult {
        CHANGED,
        UNCHANGED,
        NO_URL,
        REJECTED,
        NO_TRANSPORT
    }

    /**
     * Muta o estado para tocar, sem notificar. Quem chama decide quando marcar a mudança. A URL passa de novo
     * pela política: pode ter vindo do item (NBT) ou ter sido salva antes de o admin mudar a allowlist. No modo
     * de frequência a rádio só liga "sem sinal": quem chama sintoniza em seguida ({@link #afterPlay}).
     */
    static PlayResult applyPlay(RadioState s) {
        if (s.mode == TuneMode.FREQUENCY) {
            if (s.playing) return PlayResult.UNCHANGED;
            s.playing = true;
            s.session++;
            s.status = "";
            s.transport = Transport.NONE;
            clearTuning(s);
            return PlayResult.CHANGED;
        }
        if (s.url.isEmpty()) return PlayResult.NO_URL;
        if (ServerPolicy.rejection(s.url) != null) return PlayResult.REJECTED;
        Transport t = ServerPolicy.chooseTransport(s.url);
        if (t == Transport.NONE) return PlayResult.NO_TRANSPORT;
        if (s.playing && s.transport == t) {
            // Já tocando: se a estação do relay morreu (erro ou fim), "tocar" de novo tenta outra conexão.
            if (t == Transport.RELAY) RelayService.retryIfFailed(s.url);
            return PlayResult.UNCHANGED;
        }
        s.playing = true;
        s.transport = t;
        s.session++;
        s.status = "";
        return PlayResult.CHANGED;
    }

    /** Depois de ligar: no modo de frequência, sintoniza já (sem esperar o próximo ciclo do serviço). */
    static void afterPlay(TileRadio radio) {
        if (radio.state.mode == TuneMode.FREQUENCY && radio.state.playing) FrequencyService.retune(radio);
    }

    /** Para a rádio (jogador ou admin): esquece status e transmissor ouvido. Devolve false se já estava parada. */
    static boolean applyStop(RadioState s) {
        if (!s.playing) return false;
        s.playing = false;
        s.status = "";
        clearTuning(s);
        return true;
    }

    /** Esquece o transmissor ouvido (parou, trocou de modo). */
    static void clearTuning(RadioState s) {
        s.tunedUrl = "";
        s.tunedName = "";
        s.signal = 0;
        s.nowPlaying = "";
    }

    /**
     * Troca entre URL e frequência. Tocando, continua tocando no modo novo (ou para, se a URL da rádio não puder
     * tocar). Devolve true se mudou (quem chama marca a mudança).
     */
    static boolean setMode(TileRadio radio, TuneMode mode) {
        RadioState s = radio.state;
        if (mode == s.mode) return false;
        boolean wasPlaying = s.playing;
        s.mode = mode;
        s.status = "";
        clearTuning(s);
        if (wasPlaying) {
            // A fonte muda (URL própria ↔ transmissor): sessão nova mesmo se o transporte for o mesmo.
            s.playing = false;
            s.session++;
            if (applyPlay(s) == PlayResult.CHANGED) afterPlay(radio);
            else s.transport = Transport.NONE;
        }
        return true;
    }

    /** Liga a reprodução a pedido de um jogador. Devolve false se recusou. */
    static boolean startPlaying(EntityPlayerMP player, TileRadio radio) {
        switch (applyPlay(radio.state)) {
            case NO_URL:
                notice(player, radio, true, "akashicfm.notice.no_url", "");
                return false;
            case REJECTED: {
                UrlPolicy.PolicyException e = ServerPolicy.rejection(radio.state.url);
                notice(
                    player,
                    radio,
                    true,
                    e == null ? "akashicfm.policy.malformed" : e.translationKey(),
                    e == null ? "" : e.detail);
                return false;
            }
            case NO_TRANSPORT:
                if (FmConfig.Relay.enabled && ServerPolicy.isRelayAvailable()
                    && !RelayService.canRelay(radio.state.url)) {
                    notice(
                        player,
                        radio,
                        true,
                        "akashicfm.notice.relay_full",
                        String.valueOf(FmConfig.Relay.maxStations));
                } else {
                    notice(player, radio, true, "akashicfm.notice.no_transport", "");
                }
                return false;
            case CHANGED:
                afterPlay(radio);
                radio.markStateChanged();
                return true;
            default:
                return true;
        }
    }

    /** Troca a URL depois de passar pela política. Devolve false se recusou. */
    static boolean setUrl(EntityPlayerMP player, TileRadio radio, String raw) {
        RadioState s = radio.state;
        String url = TextSanitizer.cleanUrl(raw, RadioLimits.MAX_URL_LENGTH);
        boolean urlMode = s.mode == TuneMode.URL;
        if (url.isEmpty()) {
            // Sem URL a rádio para, mas só se é a URL que ela toca (sintonizada, toca a do transmissor).
            if (!s.url.isEmpty() || (urlMode && s.playing)) {
                s.url = "";
                if (urlMode) s.playing = false;
                radio.markStateChanged();
            }
            return false;
        }
        try {
            ServerPolicy.urlPolicy()
                .check(url);
        } catch (UrlPolicy.PolicyException e) {
            notice(player, radio, true, e.translationKey(), e.detail);
            return false;
        }
        if (!url.equals(s.url)) {
            s.url = url;
            if (urlMode) {
                if (s.playing) s.session++; // troca de estação ao vivo: clientes recomeçam com a URL nova
                s.status = "";
            }
            radio.markStateChanged();
            AuditLog.log(
                player.getCommandSenderName(),
                player.getUniqueID(),
                "radio.url",
                "dim " + radio.dimension() + " " + radio.pos() + " -> " + url);
        }
        return true;
    }

    private static void addStation(EntityPlayerMP player, TileRadio radio, String raw) {
        RadioState s = radio.state;
        String url = TextSanitizer.cleanUrl(raw, RadioLimits.MAX_URL_LENGTH);
        if (url.isEmpty() || s.stations.contains(url)) return;
        if (s.stations.size() >= RadioLimits.MAX_STATIONS) {
            notice(player, radio, true, "akashicfm.notice.stations_full", String.valueOf(RadioLimits.MAX_STATIONS));
            return;
        }
        try {
            ServerPolicy.urlPolicy()
                .check(url);
        } catch (UrlPolicy.PolicyException e) {
            notice(player, radio, true, e.translationKey(), e.detail);
            return;
        }
        s.stations.add(url);
        radio.markStateChanged();
    }

    /** Tira a caixa da lista da rádio e limpa o vínculo no TE da caixa, se o chunk dela estiver carregado. */
    public static void unlinkSpeaker(World world, TileRadio radio, Pos speaker) {
        radio.state.speakers.remove(speaker);
        if (world.blockExists(speaker.x, speaker.y, speaker.z)) {
            TileEntity te = world.getTileEntity(speaker.x, speaker.y, speaker.z);
            if (te instanceof TileSpeaker && radio.pos()
                .equals(((TileSpeaker) te).linkedRadio)) {
                ((TileSpeaker) te).linkedRadio = null;
                ((TileSpeaker) te).markChanged();
            }
        }
    }

    /** Sinal de redstone mudou (chamado pelo bloco). */
    public static void onRedstone(TileRadio radio, boolean powered) {
        RadioState s = radio.state;
        boolean rising = powered && !s.lastPowered;
        if (powered == s.lastPowered) return;
        s.lastPowered = powered;
        boolean wasPlaying = s.playing;
        int oldSession = s.session;
        switch (s.redstoneMode) {
            case WHILE_POWERED:
                if (powered) applyPlay(s);
                else s.playing = false;
                break;
            case TOGGLE_ON_PULSE:
                if (rising) {
                    if (s.playing) s.playing = false;
                    else applyPlay(s);
                }
                break;
            default:
                break;
        }
        if (s.playing && !wasPlaying) afterPlay(radio);
        if (s.playing != wasPlaying || s.session != oldSession) {
            radio.markStateChanged();
        } else {
            // Só lastPowered mudou: salva sem mandar pacote (um clock de redstone não pode inundar a rede).
            radio.markDirty();
        }
    }

    static int maxRange() {
        return RadioLimits.clamp(FmConfig.Limits.maxRange, RadioLimits.RANGE_MIN, RadioLimits.RANGE_HARD_MAX);
    }

    static void notice(EntityPlayerMP player, TileRadio radio, boolean error, String key, String arg) {
        if (player == null) return;
        FmNetwork.sendTo(new S2CRadioNotice(radio.xCoord, radio.yCoord, radio.zCoord, error, key, arg), player);
    }
}
