package com.akashiic.fm.server.ipod;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.content.ItemIPod;
import com.akashiic.fm.network.C2SIPodAction;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.server.Moderation;
import com.akashiic.fm.server.PortableActionHandler;

/**
 * Aplica as ações do jogador no próprio iPod (thread principal). Confere que o slot tem um iPod e que é o mesmo que
 * a tela via; o link passa pela allowlist de hosts ({@link YtDlp#classify}) antes de ir para o yt-dlp. O que toca de
 * fato é decidido a cada ciclo pelo {@link IPodService}.
 */
public final class IPodActionHandler {

    /** Avisos do iPod vão com esta coordenada (nenhum bloco tem y = -2): a tela do iPod os recebe. */
    public static final int NOTICE_Y = -2;

    private IPodActionHandler() {}

    public static void handle(EntityPlayerMP player, C2SIPodAction msg) {
        if (player == null || player.isDead || player.playerNetServerHandler == null) return;
        ItemStack stack = ItemIPod.at(player, msg.slot);
        if (stack == null) return;
        if (Moderation.isBlocked(player)) {
            notice(player, true, "akashicfm.notice.blocked");
            return;
        }
        IPodState s = ItemIPod.state(stack);
        // Outro iPod no slot (a tela via um, o slot tem outro): ignora em vez de mexer no errado.
        if (s.id != 0 && s.id != msg.id) return;
        boolean assigned = s.id == 0;
        if (assigned) s.id = PortableActionHandler.newId();
        if (!FmConfig.IPod.enabled && msg.action != C2SIPodAction.Action.STOP
            && msg.action != C2SIPodAction.Action.VOLUME) {
            notice(player, true, IPodService.STATUS_DISABLED);
            if (assigned) ItemIPod.save(stack, s);
            return;
        }
        boolean changed = apply(
            player,
            IPodTarget.item(player.getUniqueID(), s.id),
            s,
            msg.action,
            msg.intArg,
            msg.strArg);
        if (changed || assigned) ItemIPod.save(stack, s);
    }

    /** Agachado + botão direito com o iPod na mão: liga, pausa ou retoma. */
    public static void toggle(EntityPlayerMP player, int slot) {
        ItemStack stack = ItemIPod.at(player, slot);
        if (stack == null) return;
        if (Moderation.isBlocked(player)) {
            notice(player, true, "akashicfm.notice.blocked");
            return;
        }
        IPodState s = ItemIPod.state(stack);
        boolean assigned = s.id == 0;
        if (assigned) s.id = PortableActionHandler.newId();
        if (!FmConfig.IPod.enabled) {
            notice(player, true, IPodService.STATUS_DISABLED);
            if (assigned) ItemIPod.save(stack, s);
            return;
        }
        if (apply(player, IPodTarget.item(player.getUniqueID(), s.id), s, C2SIPodAction.Action.TOGGLE, 0, "")
            || assigned) ItemIPod.save(stack, s);
    }

    /** Sem jogador nem destino (testes): sem avisos nem adição. */
    static boolean apply(EntityPlayerMP player, IPodState s, C2SIPodAction.Action action, int intArg, String strArg) {
        return apply(player, null, s, action, intArg, strArg);
    }

    /**
     * Muta o estado; devolve true se mudou. {@code target} é o iPod da ação (para a adição em segundo plano, a
     * posição da faixa e os avisos); {@code player} e {@code target} podem ser null (testes).
     */
    static boolean apply(EntityPlayerMP player, IPodTarget target, IPodState s, C2SIPodAction.Action action, int intArg,
        String strArg) {
        switch (action) {
            case ADD: {
                String input = TextSanitizer.clean(strArg, RadioLimits.MAX_URL_LENGTH);
                if (input.isEmpty() || player == null || target == null) return false;
                String refused = IPodService.add(player, target, input);
                target.notice(player, refused != null, refused != null ? refused : "akashicfm.ipod.notice.adding");
                return false; // a fila muda quando a expansão terminar
            }
            case PLAY:
                if (intArg < 0 || intArg >= s.queue.size()) return false;
                s.play(intArg);
                return true;
            case TOGGLE:
                if (s.queue.isEmpty()) {
                    if (target != null) target.notice(player, true, "akashicfm.ipod.notice.empty");
                    return false;
                }
                if (!s.on) s.play(Math.max(0, s.index));
                else s.paused = !s.paused;
                return true;
            case STOP:
                if (!s.on) return false;
                s.on = false;
                s.paused = false;
                return true;
            case NEXT: {
                if (s.queue.isEmpty()) return false;
                int next = s.nextIndex(true);
                if (next < 0) { // fim da fila sem repetição: para
                    s.on = false;
                    s.paused = false;
                    s.index = 0;
                } else {
                    s.play(next);
                }
                return true;
            }
            case PREVIOUS: {
                if (s.queue.isEmpty()) return false;
                // Com a faixa já andando, "anterior" volta para o começo dela (como os tocadores).
                long position = target == null ? 0 : IPodService.positionMs(target.key(), s.id);
                if (s.on && position > IPodService.RESTART_THRESHOLD_MS) s.play(s.index);
                else s.play(s.previousIndex());
                return true;
            }
            case REMOVE: {
                if (intArg < 0 || intArg >= s.queue.size()) return false;
                boolean current = s.remove(intArg);
                // Tirou a que tocava: a que ficou no lugar dela começa (sessão nova).
                if (current && s.on) s.session++;
                return true;
            }
            case CLEAR:
                if (s.queue.isEmpty()) return false;
                s.clear();
                return true;
            case SHUFFLE:
                if (s.queue.size() - Math.max(0, s.index + 1) < 2) return false;
                s.shuffleAfterCurrent(ThreadLocalRandom.current());
                return true;
            case REPEAT:
                s.repeat = s.repeat.next();
                return true;
            case VOLUME: {
                int v = RadioLimits.clamp(intArg, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
                if (v == s.volume) return false;
                s.volume = v;
                return true;
            }
            default:
                return false;
        }
    }

    /** {@code status}: chave de tradução, com argumentos depois de '|'. */
    static void notice(EntityPlayerMP player, boolean error, String status) {
        if (player == null) return;
        String key = status, arg = "";
        int bar = status.indexOf('|');
        if (bar >= 0) {
            key = status.substring(0, bar);
            arg = status.substring(bar + 1);
        }
        FmNetwork.sendTo(new S2CRadioNotice(0, NOTICE_Y, 0, error, key, arg), player);
    }
}
