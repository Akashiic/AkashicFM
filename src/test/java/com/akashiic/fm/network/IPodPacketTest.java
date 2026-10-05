package com.akashiic.fm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.common.RadioLimits;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class IPodPacketTest {

    @Test
    void acaoDoIPodIdaEVolta() {
        C2SIPodAction a = new C2SIPodAction(12, 987654321L, C2SIPodAction.Action.ADD, 3, "https://soundcloud.com/a/b");
        ByteBuf buf = Unpooled.buffer();
        a.toBytes(buf);
        C2SIPodAction b = new C2SIPodAction();
        b.fromBytes(buf);
        assertEquals(12, b.slot);
        assertEquals(987654321L, b.id);
        assertEquals(C2SIPodAction.Action.ADD, b.action);
        assertEquals(3, b.intArg);
        assertEquals("https://soundcloud.com/a/b", b.strArg);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void acaoDoIPodHostil() {
        StringBuilder big = new StringBuilder();
        while (big.length() < 16_000) big.append("abcdefghij");
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(250);
        buf.writeLong(0);
        buf.writeByte(77); // ação desconhecida: o handler descarta
        buf.writeInt(-5);
        ByteBufUtils.writeUTF8String(buf, big.toString());
        C2SIPodAction b = new C2SIPodAction();
        b.fromBytes(buf);
        assertNull(b.action);
        assertEquals(RadioLimits.MAX_URL_LENGTH, b.strArg.length());
    }

    @Test
    void empacotaPedidoEIndice() {
        int v = C2SIPodAction.Action.pack(123456, 9);
        assertEquals(123456, v >>> 4);
        assertEquals(9, v & 0xF);
        int big = C2SIPodAction.Action.pack(Integer.MAX_VALUE, 31);
        assertTrue(big >= 0, "nunca negativo");
        assertEquals(0x7FFFFFF, big >>> 4);
        assertEquals(15, big & 0xF);
    }

    @Test
    void resultadosIdaEVolta() {
        List<S2CIPodSearchResults.Entry> list = new ArrayList<>();
        list.add(new S2CIPodSearchResults.Entry("Get Lucky", "Daft Punk", 248));
        list.add(new S2CIPodSearchResults.Entry("Daft Punk - Around the World", "Daft Punk", 0));
        S2CIPodSearchResults a = new S2CIPodSearchResults(
            77,
            IPodTrack.Source.YOUTUBE,
            S2CIPodSearchResults.FLAG_ENABLED | S2CIPodSearchResults.FLAG_SPOTIFY_LINKS,
            "",
            list);
        ByteBuf buf = Unpooled.buffer();
        a.toBytes(buf);
        S2CIPodSearchResults b = new S2CIPodSearchResults();
        b.fromBytes(buf);
        assertEquals(0, buf.readableBytes());
        assertEquals(77, b.requestId);
        assertEquals(IPodTrack.Source.YOUTUBE, b.service);
        assertTrue(b.has(S2CIPodSearchResults.FLAG_SPOTIFY_LINKS));
        assertTrue(!b.has(S2CIPodSearchResults.FLAG_SPOTIFY_SEARCH));
        assertEquals(2, b.results.size());
        assertEquals(
            "Daft Punk - Get Lucky",
            b.results.get(0)
                .display());
        assertEquals(
            "Daft Punk - Around the World",
            b.results.get(1)
                .display());
        assertEquals(248, b.results.get(0).durationSec);

        S2CIPodSearchResults hello = new S2CIPodSearchResults(0, null, 7, "", null);
        buf = Unpooled.buffer();
        hello.toBytes(buf);
        b = new S2CIPodSearchResults();
        b.fromBytes(buf);
        assertEquals(null, b.service);
        assertEquals(7, b.flags);
        assertTrue(b.results.isEmpty());
    }

    @Test
    void resultadosHostisSaoSaneados() {
        StringBuilder big = new StringBuilder("§c");
        while (big.length() < 5000) big.append("y");
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(-9);
        buf.writeByte(200); // serviço inexistente
        buf.writeByte(0xFF); // flags desconhecidas
        ByteBufUtils.writeUTF8String(buf, big.toString());
        buf.writeByte(40); // 40 resultados: só 10 ficam
        for (int i = 0; i < 40; i++) {
            ByteBufUtils.writeUTF8String(buf, big.toString());
            ByteBufUtils.writeUTF8String(buf, big.toString());
            buf.writeInt(i == 0 ? -1 : Integer.MAX_VALUE);
        }
        S2CIPodSearchResults b = new S2CIPodSearchResults();
        b.fromBytes(buf);
        assertEquals(0, buf.readableBytes(), "lê tudo, mesmo o que descarta");
        assertEquals(0, b.requestId);
        assertEquals(null, b.service);
        assertEquals(7, b.flags);
        assertTrue(b.status.length() <= RadioLimits.MAX_STATUS_LENGTH);
        assertEquals(S2CIPodSearchResults.MAX_RESULTS, b.results.size());
        for (S2CIPodSearchResults.Entry e : b.results) {
            assertTrue(e.title.length() <= IPodTrack.MAX_TITLE);
            assertTrue(e.artist.length() <= IPodTrack.MAX_ARTIST);
            assertTrue(!e.title.contains("§"));
            assertTrue(e.durationSec >= 0 && e.durationSec <= IPodTrack.MAX_DURATION_SEC);
        }
    }

    @Test
    void statusIdaEVolta() {
        S2CIPodStatus a = new S2CIPodStatus(
            42L,
            S2CIPodStatus.Phase.PAUSED,
            61_500,
            213_000,
            7,
            "akashicfm.ipod.status.paused");
        ByteBuf buf = Unpooled.buffer();
        a.toBytes(buf);
        S2CIPodStatus b = new S2CIPodStatus();
        b.fromBytes(buf);
        assertEquals(42L, b.itemId);
        assertEquals(S2CIPodStatus.Phase.PAUSED, b.phase);
        assertEquals(61_500, b.positionMs);
        assertEquals(213_000, b.durationMs);
        assertEquals(7, b.index);
        assertEquals("akashicfm.ipod.status.paused", b.status);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void statusHostilEhSaneado() {
        StringBuilder big = new StringBuilder("§k");
        while (big.length() < 4000) big.append("x");
        ByteBuf buf = Unpooled.buffer();
        buf.writeLong(1);
        buf.writeByte(200); // fase inexistente
        buf.writeInt(-1); // 4294967295 ms sem sinal: passa do teto de 48 h
        buf.writeInt(5);
        buf.writeShort(-1);
        ByteBufUtils.writeUTF8String(buf, big.toString());
        S2CIPodStatus b = new S2CIPodStatus();
        b.fromBytes(buf);
        assertEquals(S2CIPodStatus.Phase.STOPPED, b.phase);
        assertEquals(48L * 3600 * 1000, b.positionMs);
        assertEquals(-1, b.index);
        assertTrue(b.status.length() <= RadioLimits.MAX_STATUS_LENGTH);
        assertTrue(!b.status.contains("§"));
    }
}
