package com.akashiic.fm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
