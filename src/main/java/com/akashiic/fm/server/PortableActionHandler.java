package com.akashiic.fm.server;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.PortableState;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.ItemPortableRadio;
import com.akashiic.fm.network.C2SPortableAction;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CRadioNotice;

/**
 * Aplica as ações do jogador no próprio rádio portátil (thread principal). Confere que o slot tem um portátil e que
 * é o mesmo que a tela via; a URL passa pela mesma política da rádio. O que toca de fato é decidido a cada ciclo
 * por {@link PortableSources}, que valida de novo (o NBT de um item pode vir de qualquer lugar, até do criativo).
 */
public final class PortableActionHandler {

    /** Avisos do portátil vão com esta coordenada (nenhum bloco tem y = -1): a tela do portátil os recebe. */
    public static final int NOTICE_Y = -1;
    /** Slots do inventário principal (barra + mochila). */
    static final int SLOTS = 36;

    private PortableActionHandler() {}

    public static void handle(EntityPlayerMP player, C2SPortableAction msg) {
        if (player == null || player.isDead || player.playerNetServerHandler == null) return;
        ItemStack stack = portableAt(player, msg.slot);
        if (stack == null) return;
        if (Moderation.isBlocked(player)) {
            notice(player, true, "akashicfm.notice.blocked", "");
            return;
        }
        PortableState s = ItemPortableRadio.state(stack);
        // Outro portátil no slot (a tela via um, o slot tem outro): ignora em vez de mexer no errado.
        if (s.id != 0 && s.id != msg.id) return;
        boolean assigned = s.id == 0;
        if (assigned) s.id = newId();
        boolean changed = apply(player, s, msg.action, msg.intArg, msg.strArg);
        if (changed || assigned) ItemPortableRadio.save(stack, s);
    }

    /** Agachado + botão direito com o portátil na mão: liga ou desliga. */
    public static void toggle(EntityPlayerMP player, int slot) {
        ItemStack stack = portableAt(player, slot);
        if (stack == null) return;
        if (Moderation.isBlocked(player)) {
            notice(player, true, "akashicfm.notice.blocked", "");
            return;
        }
        PortableState s = ItemPortableRadio.state(stack);
        boolean assigned = s.id == 0;
        if (assigned) s.id = newId();
        C2SPortableAction.Action a = s.on ? C2SPortableAction.Action.TURN_OFF : C2SPortableAction.Action.TURN_ON;
        if (apply(player, s, a, 0, "") || assigned) ItemPortableRadio.save(stack, s);
    }

    static ItemStack portableAt(EntityPlayerMP player, int slot) {
        if (slot < 0 || slot >= SLOTS || player.inventory == null) return null;
        ItemStack stack = player.inventory.mainInventory[slot];
        return stack != null && stack.getItem() instanceof ItemPortableRadio ? stack : null;
    }

    public static long newId() {
        long id;
        do id = ThreadLocalRandom.current()
            .nextLong();
        while (id == 0);
        return id;
    }

    /** Muta o estado; devolve true se mudou. {@code player} pode ser null (testes): sem avisos. */
    static boolean apply(EntityPlayerMP player, PortableState s, C2SPortableAction.Action action, int intArg,
        String strArg) {
        switch (action) {
            case TURN_ON: {
                if (s.on) return false;
                if (s.mode == TuneMode.URL && !playable(player, s.url)) return false;
                s.on = true;
                s.session++;
                return true;
            }
            case TURN_OFF:
                if (!s.on) return false;
                s.on = false;
                return true;
            case SET_MODE: {
                TuneMode mode = TuneMode.byOrdinal(intArg);
                if (mode == s.mode) return false;
                s.mode = mode;
                if (s.on) {
                    s.session++;
                    // Voltando para a URL sem uma URL que possa tocar: desliga (sintonizado sempre pode).
                    if (mode == TuneMode.URL && !playable(player, s.url)) s.on = false;
                }
                return true;
            }
            case SET_URL:
                return setUrl(player, s, strArg);
            case SET_FREQUENCY: {
                int f = Frequency.clamp(intArg);
                if (f == s.frequency) return false;
                s.frequency = f;
                return true;
            }
            case SET_VOLUME: {
                int v = RadioLimits.clamp(intArg, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
                if (v == s.volume) return false;
                s.volume = v;
                return true;
            }
            default:
                return false;
        }
    }

    private static boolean setUrl(EntityPlayerMP player, PortableState s, String raw) {
        String url = TextSanitizer.cleanUrl(raw, RadioLimits.MAX_URL_LENGTH);
        if (url.isEmpty()) {
            if (s.url.isEmpty()) return false;
            s.url = "";
            if (s.mode == TuneMode.URL) s.on = false;
            return true;
        }
        UrlPolicy.PolicyException e = ServerPolicy.rejection(url);
        if (e != null) {
            notice(player, true, e.translationKey(), e.detail);
            return false;
        }
        if (url.equals(s.url)) return false;
        s.url = url;
        if (s.on && s.mode == TuneMode.URL) s.session++; // troca de estação ao vivo
        if (player != null) {
            AuditLog.log(player.getCommandSenderName(), player.getUniqueID(), "portable.url", url);
        }
        return true;
    }

    /** A URL pode tocar agora (com aviso do motivo se não). */
    private static boolean playable(EntityPlayerMP player, String url) {
        if (url.isEmpty()) {
            notice(player, true, "akashicfm.notice.no_url", "");
            return false;
        }
        UrlPolicy.PolicyException e = ServerPolicy.rejection(url);
        if (e != null) {
            notice(player, true, e.translationKey(), e.detail);
            return false;
        }
        return true;
    }

    static void notice(EntityPlayerMP player, boolean error, String key, String arg) {
        if (player == null) return;
        FmNetwork.sendTo(new S2CRadioNotice(0, NOTICE_Y, 0, error, key, arg), player);
    }
}
