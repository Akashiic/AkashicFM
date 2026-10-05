package com.akashiic.fm.server.ipod;

import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.network.S2CIPodStatus;

/**
 * Um iPod tocando, como o {@link IPodService} o vê a cada ciclo: o item no inventário de um jogador (ou, depois, um
 * bloco). O serviço só conhece isto, a fila ({@link IPodState}) e a estação do relay pela chave. Thread principal.
 */
interface IPodHost {

    /** A chave da estação do relay (com o prefixo {@code ipod:}): única por host. */
    String key();

    /** A identidade do aparelho: outra identidade na mesma chave é outro aparelho, e a sessão recomeça. */
    long id();

    /** O estado deste ciclo; o serviço o muda e chama {@link #save()}. */
    IPodState state();

    /** Grava o estado de {@link #state()} (no item, no bloco). */
    void save();

    /** O status para quem vê a tela (fase, posição, motivo). */
    void sendStatus(S2CIPodStatus status);
}
