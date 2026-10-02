package com.akashiic.fm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.RadioLimits;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class PacketCodecTest {

    @Test
    void acaoIdaEVolta() {
        C2SRadioAction a = new C2SRadioAction(-5, 64, 1_000_000, C2SRadioAction.Action.SET_URL, 42, "http://a.com/x");
        ByteBuf buf = Unpooled.buffer();
        a.toBytes(buf);
        C2SRadioAction b = new C2SRadioAction();
        b.fromBytes(buf);
        assertEquals(-5, b.x);
        assertEquals(64, b.y);
        assertEquals(1_000_000, b.z);
        assertEquals(C2SRadioAction.Action.SET_URL, b.action);
        assertEquals(42, b.intArg);
        assertEquals("http://a.com/x", b.strArg);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void textoGiganteDeClienteMaliciosoECortadoNaLeitura() {
        StringBuilder big = new StringBuilder();
        while (big.length() < 16_000) big.append("abcdefghij"); // teto do varint de 2 bytes do FML: 16383
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0);
        buf.writeInt(0);
        buf.writeInt(0);
        buf.writeByte(C2SRadioAction.Action.SET_URL.ordinal());
        buf.writeInt(0);
        ByteBufUtils.writeUTF8String(buf, big.toString()); // o toBytes legítimo cortaria antes
        C2SRadioAction b = new C2SRadioAction();
        b.fromBytes(buf);
        assertEquals(RadioLimits.MAX_URL_LENGTH, b.strArg.length());
    }

    @Test
    void acaoDesconhecidaViraNula() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0);
        buf.writeInt(0);
        buf.writeInt(0);
        buf.writeByte(200);
        buf.writeInt(0);
        ByteBufUtils.writeUTF8String(buf, "");
        C2SRadioAction b = new C2SRadioAction();
        b.fromBytes(buf);
        assertNull(b.action);
    }

    @Test
    void permissoesComAlcancePreso() {
        S2CRadioPerms p = new S2CRadioPerms(1, 2, 3, true, false, 48);
        ByteBuf buf = Unpooled.buffer();
        p.toBytes(buf);
        S2CRadioPerms q = new S2CRadioPerms();
        q.fromBytes(buf);
        assertTrue(q.canControl);
        assertEquals(false, q.canAdmin);
        assertEquals(48, q.maxRange);

        ByteBuf bad = Unpooled.buffer();
        bad.writeInt(0);
        bad.writeInt(0);
        bad.writeInt(0);
        bad.writeBoolean(true);
        bad.writeBoolean(true);
        bad.writeShort(60000);
        S2CRadioPerms r = new S2CRadioPerms();
        r.fromBytes(bad);
        assertEquals(RadioLimits.RANGE_HARD_MAX, r.maxRange);
    }

    @Test
    void avisoComTextosLimitados() {
        StringBuilder big = new StringBuilder();
        while (big.length() < 1000) big.append('k');
        S2CRadioNotice n = new S2CRadioNotice(1, 2, 3, true, big.toString(), big.toString());
        ByteBuf buf = Unpooled.buffer();
        n.toBytes(buf);
        S2CRadioNotice m = new S2CRadioNotice();
        m.fromBytes(buf);
        assertTrue(m.error);
        assertEquals(96, m.key.length());
        assertEquals(256, m.arg.length());
    }
}
