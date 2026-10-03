package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.network.C2SIPodAction.Action;

/** As ações do iPod sobre o estado do item (sem jogador: sem avisos nem adição). */
class IPodActionTest {

    private static IPodState queue(int n) {
        IPodState s = new IPodState();
        List<IPodTrack> l = new ArrayList<>();
        for (int i = 0; i < n; i++)
            l.add(new IPodTrack(IPodTrack.Source.SOUNDCLOUD, "https://soundcloud.com/a/" + i, "t" + i, "a", 60));
        s.append(l, 200);
        s.sanitize();
        return s;
    }

    private static boolean apply(IPodState s, Action a, int arg) {
        return IPodActionHandler.apply(null, s, a, arg, "");
    }

    @Test
    void tocarPausarRetomarEParar() {
        IPodState s = queue(3);
        assertTrue(apply(s, Action.TOGGLE, 0)); // parado: liga da atual
        assertTrue(s.on);
        assertFalse(s.paused);
        int session = s.session;
        assertTrue(apply(s, Action.TOGGLE, 0));
        assertTrue(s.paused);
        assertTrue(apply(s, Action.TOGGLE, 0));
        assertFalse(s.paused);
        assertEquals(session, s.session); // pausa não troca de faixa
        assertTrue(apply(s, Action.STOP, 0));
        assertFalse(s.on);
        assertFalse(apply(s, Action.STOP, 0));
        assertFalse(apply(queue(0), Action.TOGGLE, 0)); // fila vazia
    }

    @Test
    void tocarIndiceValidado() {
        IPodState s = queue(3);
        assertTrue(apply(s, Action.PLAY, 2));
        assertEquals(2, s.index);
        assertFalse(apply(s, Action.PLAY, 3));
        assertFalse(apply(s, Action.PLAY, -1));
        assertEquals(2, s.index);
    }

    @Test
    void proximaNoFimParaEAnteriorVolta() {
        IPodState s = queue(2);
        apply(s, Action.PLAY, 0);
        assertTrue(apply(s, Action.NEXT, 0));
        assertEquals(1, s.index);
        assertTrue(apply(s, Action.NEXT, 0)); // fim sem repetir
        assertFalse(s.on);
        assertEquals(0, s.index);
        apply(s, Action.PLAY, 1);
        assertTrue(apply(s, Action.PREVIOUS, 0)); // sem posição (não toca no serviço): a anterior
        assertEquals(0, s.index);
        s.repeat = IPodState.Repeat.ONE;
        assertTrue(apply(s, Action.NEXT, 0)); // repetir uma não prende o botão
        assertEquals(1, s.index);
    }

    @Test
    void tirarAAtualComecaAQueFicouNoLugar() {
        IPodState s = queue(3);
        apply(s, Action.PLAY, 1);
        int session = s.session;
        assertTrue(apply(s, Action.REMOVE, 1));
        assertEquals(1, s.index);
        assertEquals("https://soundcloud.com/a/2", s.current().link);
        assertEquals(session + 1, s.session);
        assertTrue(apply(s, Action.REMOVE, 0)); // outra: a atual continua tocando sem recomeçar
        assertEquals(session + 1, s.session);
        assertFalse(apply(s, Action.REMOVE, 7));
    }

    @Test
    void limparEmbaralharRepetirEVolume() {
        IPodState s = queue(4);
        apply(s, Action.PLAY, 0);
        assertTrue(apply(s, Action.SHUFFLE, 0));
        assertEquals("https://soundcloud.com/a/0", s.current().link);
        apply(s, Action.PLAY, 3);
        assertFalse(apply(s, Action.SHUFFLE, 0)); // nada depois da atual
        assertTrue(apply(s, Action.REPEAT, 0));
        assertEquals(IPodState.Repeat.ALL, s.repeat);
        assertTrue(apply(s, Action.VOLUME, 250));
        assertEquals(100, s.volume);
        assertFalse(apply(s, Action.VOLUME, 100));
        assertTrue(apply(s, Action.CLEAR, 0));
        assertFalse(s.on);
        assertFalse(apply(s, Action.CLEAR, 0));
    }

    @Test
    void adicionarSemJogadorNaoFazNada() {
        IPodState s = queue(1);
        assertFalse(IPodActionHandler.apply(null, s, Action.ADD, 0, "https://soundcloud.com/a/b"));
        assertEquals(1, s.queue.size());
    }
}
