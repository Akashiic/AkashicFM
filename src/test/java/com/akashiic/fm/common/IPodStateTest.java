package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.jupiter.api.Test;

class IPodStateTest {

    private static IPodTrack sc(int i) {
        return new IPodTrack(
            IPodTrack.Source.SOUNDCLOUD,
            "https://soundcloud.com/a/t" + i,
            "Faixa " + i,
            "Artista",
            100 + i);
    }

    private static List<IPodTrack> tracks(int n) {
        List<IPodTrack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(sc(i));
        return out;
    }

    private static IPodState withQueue(int n) {
        IPodState s = new IPodState();
        s.append(tracks(n), 200);
        s.sanitize();
        return s;
    }

    @Test
    void novoEstaVazioEParado() {
        IPodState s = IPodState.fromItemTag(null);
        assertFalse(s.on);
        assertEquals(-1, s.index);
        assertNull(s.current());
        assertEquals(RadioLimits.VOLUME_DEFAULT, s.volume);
        assertEquals(IPodState.Repeat.OFF, s.repeat);
    }

    @Test
    void nbtIdaEVolta() {
        IPodState s = withQueue(3);
        s.id = 42;
        s.play(1);
        s.paused = true;
        s.volume = 77;
        s.repeat = IPodState.Repeat.ONE;
        NBTTagCompound root = s.writeToItemTag(null);
        IPodState r = IPodState.fromItemTag(root);
        assertEquals(42, r.id);
        assertTrue(r.on);
        assertTrue(r.paused);
        assertEquals(1, r.index);
        assertEquals(s.session, r.session);
        assertEquals(77, r.volume);
        assertEquals(IPodState.Repeat.ONE, r.repeat);
        assertEquals(3, r.queue.size());
        assertEquals(sc(2).link, r.queue.get(2).link);
        assertEquals(102, r.queue.get(2).durationSec);
        assertEquals("Artista", r.queue.get(2).artist);
    }

    @Test
    void leiturasRapidasSemAFila() {
        assertEquals(0, IPodState.idOf(null));
        assertFalse(IPodState.playingOf(null));
        assertEquals(0, IPodState.idOf(new NBTTagCompound()));
        IPodState s = withQueue(2);
        s.id = 99;
        assertEquals(99, IPodState.idOf(s.writeToItemTag(null)));
        assertFalse(IPodState.playingOf(s.writeToItemTag(null)));
        s.play(0);
        assertTrue(IPodState.playingOf(s.writeToItemTag(null)));
        s.paused = true;
        assertFalse(IPodState.playingOf(s.writeToItemTag(null)));
    }

    @Test
    void nbtHostilEhSaneado() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("on", true);
        tag.setBoolean("paused", true);
        tag.setInteger("index", 999);
        tag.setByte("volume", (byte) -5);
        tag.setByte("repeat", (byte) 9);
        NBTTagList list = new NBTTagList();
        NBTTagCompound bad = new NBTTagCompound();
        bad.setByte("s", (byte) 7); // origem inexistente
        bad.setString("l", "https://soundcloud.com/x");
        list.appendTag(bad);
        NBTTagCompound noLink = new NBTTagCompound();
        noLink.setByte("s", (byte) 0);
        list.appendTag(noLink);
        NBTTagCompound ok = new NBTTagCompound();
        ok.setByte("s", (byte) 1);
        ok.setString("l", "https://www.youtube.com/watch?v=jNQXAC9IVRw");
        ok.setString("t", "§kTítulo‮");
        ok.setInteger("d", -3);
        list.appendTag(ok);
        tag.setTag("q", list);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag(IPodState.KEY, tag);
        IPodState s = IPodState.fromItemTag(root);
        assertEquals(1, s.queue.size());
        assertEquals("Título", s.queue.get(0).title);
        assertEquals(0, s.queue.get(0).durationSec);
        assertEquals(0, s.index); // fora da fila: volta para a primeira
        assertEquals(0, s.volume);
        assertEquals(IPodState.Repeat.OFF, s.repeat);

        NBTTagCompound emptyOn = new NBTTagCompound();
        emptyOn.setBoolean("on", true);
        emptyOn.setBoolean("paused", true);
        NBTTagCompound r2 = new NBTTagCompound();
        r2.setTag(IPodState.KEY, emptyOn);
        IPodState e = IPodState.fromItemTag(r2);
        assertFalse(e.on); // sem fila não toca
        assertFalse(e.paused);
    }

    @Test
    void filaRespeitaOLimiteEOOrcamento() {
        IPodState s = new IPodState();
        assertEquals(5, s.append(tracks(8), 5));
        assertEquals(5, s.queue.size());
        assertEquals(0, s.append(tracks(1), 5));
        // Títulos enormes e que não comprimem: o orçamento de texto corta antes das 200.
        IPodState big = new IPodState();
        Random rng = new Random(7);
        List<IPodTrack> heavy = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            StringBuilder t = new StringBuilder();
            for (int k = 0; k < IPodTrack.MAX_TITLE; k++) t.append((char) (0x4E00 + rng.nextInt(20000))); // CJK: 3
                                                                                                          // bytes
            heavy.add(
                new IPodTrack(IPodTrack.Source.SPOTIFY, "https://open.spotify.com/track/" + i, t.toString(), "x", 1));
        }
        int added = big.append(heavy, 200);
        assertTrue(added < 200, "o orçamento devia cortar, entraram " + added);
        assertTrue(big.textBytes() <= IPodState.MAX_TEXT_BYTES);
    }

    @Test
    void nbtDoPiorCasoCabeNoPacoteDoItem() throws Exception {
        // O 1.7.10 manda o NBT do item comprimido com tamanho num short: tem que caber em 32767 bytes.
        IPodState s = new IPodState();
        Random rng = new Random(11);
        List<IPodTrack> worst = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            StringBuilder t = new StringBuilder(), a = new StringBuilder(),
                l = new StringBuilder("https://soundcloud.com/");
            for (int k = 0; k < IPodTrack.MAX_TITLE; k++) t.append((char) (0x4E00 + rng.nextInt(20000)));
            for (int k = 0; k < IPodTrack.MAX_ARTIST; k++) a.append((char) (0xAC00 + rng.nextInt(11000)));
            while (l.length() < IPodTrack.MAX_LINK) l.append((char) ('a' + rng.nextInt(26)));
            worst.add(new IPodTrack(IPodTrack.Source.SOUNDCLOUD, l.toString(), t.toString(), a.toString(), 3600));
        }
        s.append(worst, 200);
        s.id = Long.MAX_VALUE;
        s.play(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] compressed = CompressedStreamTools.compress(s.writeToItemTag(null));
        out.write(compressed);
        assertTrue(compressed.length < 32767, "NBT comprimido com " + compressed.length + " bytes");
    }

    @Test
    void tirarFaixaMantemAAtual() {
        IPodState s = withQueue(5);
        s.play(3);
        assertFalse(s.remove(1)); // antes da atual
        assertEquals(2, s.index);
        assertEquals(sc(3).link, s.current().link);
        assertTrue(s.remove(2)); // a atual: a seguinte fica no lugar
        assertEquals(2, s.index);
        assertEquals(sc(4).link, s.current().link);
        assertTrue(s.remove(2)); // era a última: volta para a anterior
        assertEquals(1, s.index);
        assertFalse(s.remove(9));
        s.remove(0);
        s.remove(0);
        assertTrue(s.queue.isEmpty());
        assertFalse(s.on);
        assertEquals(-1, s.index);
    }

    @Test
    void proximaEAnteriorComRepeticao() {
        IPodState s = withQueue(3);
        s.play(2);
        assertEquals(-1, s.nextIndex(false)); // fim, sem repetir
        s.repeat = IPodState.Repeat.ALL;
        assertEquals(0, s.nextIndex(false));
        s.repeat = IPodState.Repeat.ONE;
        assertEquals(2, s.nextIndex(false)); // automática: a mesma
        assertEquals(0, s.nextIndex(true)); // pedida: a próxima (com volta, como repetir todas)
        s.play(0);
        s.repeat = IPodState.Repeat.OFF;
        assertEquals(0, s.previousIndex());
        s.repeat = IPodState.Repeat.ALL;
        assertEquals(2, s.previousIndex());
        s.play(1);
        assertEquals(0, s.previousIndex());
        assertEquals(IPodState.Repeat.ALL, IPodState.Repeat.OFF.next());
        assertEquals(IPodState.Repeat.OFF, IPodState.Repeat.ONE.next());
    }

    @Test
    void tocarMudaASessaoETiraAPausa() {
        IPodState s = withQueue(2);
        int session = s.session;
        s.paused = true;
        s.play(1);
        assertTrue(s.on);
        assertFalse(s.paused);
        assertEquals(session + 1, s.session);
        s.play(5); // fora da fila: nada
        assertEquals(1, s.index);
        assertEquals(session + 1, s.session);
    }

    @Test
    void embaralhaSoDepoisDaAtual() {
        IPodState s = withQueue(10);
        s.play(3);
        List<String> before = new ArrayList<>();
        for (IPodTrack t : s.queue) before.add(t.link);
        s.shuffleAfterCurrent(new Random(5));
        for (int i = 0; i <= 3; i++) assertEquals(before.get(i), s.queue.get(i).link);
        Set<String> rest = new HashSet<>(), restBefore = new HashSet<>(before.subList(4, 10));
        for (int i = 4; i < 10; i++) rest.add(s.queue.get(i).link);
        assertEquals(restBefore, rest);
        List<String> after = new ArrayList<>();
        for (IPodTrack t : s.queue) after.add(t.link);
        assertFalse(before.equals(after), "com a semente 5 a ordem muda");
        assertEquals(Arrays.asList(sc(3).link), Arrays.asList(s.current().link));
    }

    @Test
    void tocarAgoraPoeLogoDepoisDaAtual() {
        IPodState s = new IPodState();
        assertTrue(s.playNext(sc(9), 50));
        assertEquals(1, s.queue.size());
        assertEquals(0, s.index);
        assertTrue(s.on);

        s = withQueue(4);
        s.play(1);
        int session = s.session;
        assertTrue(s.playNext(sc(9), 50));
        assertEquals(5, s.queue.size());
        assertEquals(2, s.index);
        assertEquals(sc(9).link, s.current().link);
        assertEquals(sc(2).link, s.queue.get(3).link, "as seguintes andam uma casa");
        assertTrue(s.session > session);

        // Parado no começo da fila: entra depois da atual (a 0) e toca.
        s = withQueue(3);
        s.on = false;
        s.index = 0;
        assertTrue(s.playNext(sc(9), 50));
        assertEquals(1, s.index);
        assertTrue(s.on);
    }

    @Test
    void tocarAgoraNaoRepeteNaFila() {
        IPodState s = withQueue(5);
        s.play(1);
        // Já é a atual: recomeça.
        int session = s.session;
        assertTrue(s.playNext(sc(1), 50));
        assertEquals(5, s.queue.size());
        assertEquals(1, s.index);
        assertTrue(s.session > session);
        // Já está mais para a frente (posta por um clique): muda de lugar.
        assertTrue(s.playNext(sc(4), 50));
        assertEquals(5, s.queue.size());
        assertEquals(2, s.index);
        assertEquals(sc(4).link, s.current().link);
        assertEquals(sc(2).link, s.queue.get(3).link);
        assertEquals(sc(3).link, s.queue.get(4).link);
        // Antes da atual não conta: entra uma cópia depois da atual.
        assertTrue(s.playNext(sc(0), 50));
        assertEquals(6, s.queue.size());
        assertEquals(3, s.index);
    }

    @Test
    void tocarAgoraRespeitaOLimite() {
        IPodState s = withQueue(3);
        s.play(0);
        assertFalse(s.playNext(sc(9), 3));
        assertEquals(3, s.queue.size());
        assertEquals(0, s.index);
        assertFalse(s.playNext(null, 50));
        assertTrue(s.playNext(sc(2), 3), "mover não cresce a fila");
    }

    @Test
    void limparParaTudo() {
        IPodState s = withQueue(3);
        s.play(1);
        s.clear();
        assertTrue(s.queue.isEmpty());
        assertFalse(s.on);
        assertEquals(-1, s.index);
    }
}
