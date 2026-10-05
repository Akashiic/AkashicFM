package com.akashiic.fm.server.ipod;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.content.ItemIPod;

/**
 * O iPod a que uma ação se refere, mesmo desligado: para onde vai uma adição que termina depois (o jogador pode ter
 * mudado o item de lugar) e para onde vão os avisos. Thread principal.
 */
interface IPodTarget {

    /** A chave da estação do relay deste iPod (a mesma do {@link IPodHost} quando ele toca). */
    String key();

    /** A identidade do aparelho. */
    long id();

    /** O estado de agora, com como gravá-lo, se o iPod ainda está ao alcance do jogador; senão null. */
    Binding bind(EntityPlayerMP player);

    /** Aviso na tela do iPod ({@code status}: chave de tradução, com argumentos depois de '|'). */
    void notice(EntityPlayerMP player, boolean error, String status);

    /** O estado lido e como gravá-lo. */
    final class Binding {

        final IPodState state;
        final Runnable save;

        Binding(IPodState state, Runnable save) {
            this.state = state;
            this.save = save;
        }
    }

    /** O iPod item de {@code owner} com esta identidade, em qualquer slot do inventário. */
    static IPodTarget item(UUID owner, long id) {
        return new IPodTarget() {

            @Override
            public String key() {
                return IPodService.keyOf(owner);
            }

            @Override
            public long id() {
                return id;
            }

            @Override
            public Binding bind(EntityPlayerMP player) {
                if (player == null || !owner.equals(player.getUniqueID())) return null;
                ItemStack st = ItemIPod.findById(player, id);
                if (st == null) return null; // o iPod saiu do inventário
                IPodState s = ItemIPod.state(st);
                return new Binding(s, () -> ItemIPod.save(st, s));
            }

            @Override
            public void notice(EntityPlayerMP player, boolean error, String status) {
                IPodActionHandler.notice(player, error, status);
            }
        };
    }
}
