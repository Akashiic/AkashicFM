package com.akashiic.fm.server;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.Permissions;
import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.network.C2SRadioAction;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;

/**
 * Ações dos jogadores no transmissor (mesmo pacote da rádio; o {@link RadioActionHandler} despacha pelo TE, já
 * tendo conferido jogador e chunk carregado). Aqui: distância, permissão e conteúdo, nessa ordem. A URL passa pela
 * mesma política da rádio: um transmissor é só outra forma de fazer as rádios tocarem uma URL.
 */
public final class TransmitterActionHandler {

    private static final double MAX_USE_DISTANCE_SQ = 64.0;

    private TransmitterActionHandler() {}

    static void handle(EntityPlayerMP player, C2SRadioAction msg, TileTransmitter t) {
        if (player.getDistanceSq(msg.x + 0.5, msg.y + 0.5, msg.z + 0.5) > MAX_USE_DISTANCE_SQ) {
            notice(player, t, true, "akashicfm.notice.too_far", "");
            return;
        }
        TransmitterState s = t.state;
        boolean control = Permissions.canControl(s.owner, s.access, player);
        boolean admin = Permissions.canAdmin(s.owner, player);

        switch (msg.action) {
            case REQUEST_PERMS:
                FmNetwork.sendTo(
                    new S2CRadioPerms(msg.x, msg.y, msg.z, control, admin, RadioActionHandler.maxRange()),
                    player);
                return;
            case PLAY:
                if (!control) break;
                if (!msg.strArg.isEmpty() && !setUrl(player, t, msg.strArg)) return;
                startBroadcast(player, t);
                return;
            case STOP:
                if (!control) break;
                if (s.broadcasting) {
                    s.broadcasting = false;
                    t.markStateChanged();
                }
                return;
            case SET_URL:
                if (!control) break;
                setUrl(player, t, msg.strArg);
                return;
            case SET_FREQUENCY:
                if (!control) break;
                int f = Frequency.clamp(msg.intArg);
                if (f != s.frequency) {
                    s.frequency = f;
                    t.markStateChanged();
                    audit(player, t, "a frequência", Frequency.format(f));
                }
                return;
            case SET_SCREEN_TEXT:
                if (!admin) break;
                String name = TextSanitizer.clean(msg.strArg, RadioLimits.MAX_SCREEN_TEXT);
                if (!name.equals(s.name)) {
                    s.name = name;
                    t.markStateChanged();
                }
                return;
            case SET_ACCESS:
                if (!admin) break;
                RadioAccess access = RadioAccess.byOrdinal(msg.intArg);
                if (access != s.access) {
                    s.access = access;
                    t.markStateChanged();
                }
                return;
            case SET_REDSTONE_MODE:
                if (!admin) break;
                RedstoneMode mode = RedstoneMode.byOrdinal(msg.intArg);
                if (mode != s.redstoneMode) {
                    s.redstoneMode = mode;
                    s.lastPowered = t.getWorldObj()
                        .isBlockIndirectlyGettingPowered(msg.x, msg.y, msg.z);
                    t.markStateChanged();
                }
                return;
            default:
                return; // ações só da rádio (volume, favoritas, caixas): não se aplicam
        }
        notice(player, t, true, "akashicfm.notice.no_permission", "");
    }

    /**
     * Muta o estado para transmitir, sem notificar. A URL passa de novo pela política (pode ter vindo do item ou
     * sido salva antes de o admin mudar a allowlist). Devolve false se não pode transmitir.
     */
    static boolean applyBroadcast(TransmitterState s) {
        if (s.url.isEmpty() || ServerPolicy.rejection(s.url) != null) return false;
        s.broadcasting = true;
        return true;
    }

    private static void startBroadcast(EntityPlayerMP player, TileTransmitter t) {
        TransmitterState s = t.state;
        if (s.url.isEmpty()) {
            notice(player, t, true, "akashicfm.notice.no_url", "");
            return;
        }
        UrlPolicy.PolicyException e = ServerPolicy.rejection(s.url);
        if (e != null) {
            notice(player, t, true, e.translationKey(), e.detail);
            return;
        }
        boolean was = s.broadcasting;
        applyBroadcast(s);
        if (!was) t.markStateChanged();
        if (TileTransmitter.energyRequired() && !t.hasEnergyReserve()) {
            notice(player, t, false, "akashicfm.notice.no_energy", "");
        }
    }

    /** Troca a URL depois de passar pela política. Devolve false se recusou (ou apagou). */
    static boolean setUrl(EntityPlayerMP player, TileTransmitter t, String raw) {
        TransmitterState s = t.state;
        String url = TextSanitizer.cleanUrl(raw, RadioLimits.MAX_URL_LENGTH);
        if (url.isEmpty()) {
            if (!s.url.isEmpty() || s.broadcasting) {
                s.url = "";
                s.broadcasting = false;
                t.markStateChanged();
            }
            return false;
        }
        try {
            ServerPolicy.urlPolicy()
                .check(url);
        } catch (UrlPolicy.PolicyException e) {
            notice(player, t, true, e.translationKey(), e.detail);
            return false;
        }
        if (!url.equals(s.url)) {
            s.url = url;
            t.markStateChanged();
            audit(player, t, "a URL", url);
        }
        return true;
    }

    /** Sinal de redstone mudou (chamado pelo bloco). Mesma semântica da rádio. */
    public static void onRedstone(TileTransmitter t, boolean powered) {
        TransmitterState s = t.state;
        if (powered == s.lastPowered) return;
        boolean rising = powered && !s.lastPowered;
        s.lastPowered = powered;
        boolean was = s.broadcasting;
        switch (s.redstoneMode) {
            case WHILE_POWERED:
                if (powered) applyBroadcast(s);
                else s.broadcasting = false;
                break;
            case TOGGLE_ON_PULSE:
                if (rising) {
                    if (s.broadcasting) s.broadcasting = false;
                    else applyBroadcast(s);
                }
                break;
            default:
                break;
        }
        if (s.broadcasting != was) t.markStateChanged();
        else t.markDirty(); // só lastPowered: salva sem pacote (clock de redstone não inunda a rede)
    }

    private static void audit(EntityPlayerMP player, TileTransmitter t, String what, String value) {
        AkashicFM.LOG.info(
            "[audit] {} ({}) mudou {} do transmissor em dim {} [{}] para {}",
            player.getCommandSenderName(),
            player.getUniqueID(),
            what,
            t.dimension(),
            t.pos(),
            value);
    }

    static void notice(EntityPlayerMP player, TileTransmitter t, boolean error, String key, String arg) {
        if (player == null) return;
        FmNetwork.sendTo(new S2CRadioNotice(t.xCoord, t.yCoord, t.zCoord, error, key, arg), player);
    }
}
