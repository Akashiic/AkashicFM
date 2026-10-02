package com.akashiic.fm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class PortablePacketTest {

    @Test
    void acaoDoPortatilIdaEVolta() {
        C2SPortableAction a = new C2SPortableAction(
            35,
            -1234567890123L,
            C2SPortableAction.Action.SET_URL,
            7,
            "http://a.com/x");
        ByteBuf buf = Unpooled.buffer();
        a.toBytes(buf);
        C2SPortableAction b = new C2SPortableAction();
        b.fromBytes(buf);
        assertEquals(35, b.slot);
        assertEquals(-1234567890123L, b.id);
        assertEquals(C2SPortableAction.Action.SET_URL, b.action);
        assertEquals(7, b.intArg);
        assertEquals("http://a.com/x", b.strArg);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void acaoDoPortatilHostil() {
        StringBuilder big = new StringBuilder();
        while (big.length() < 16_000) big.append("abcdefghij");
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(250); // slot fora: o servidor recusa (só 0..35)
        buf.writeLong(0);
        buf.writeByte(99); // ação desconhecida
        buf.writeInt(0);
        ByteBufUtils.writeUTF8String(buf, big.toString());
        C2SPortableAction b = new C2SPortableAction();
        b.fromBytes(buf);
        assertEquals(250, b.slot);
        assertNull(b.action);
        assertEquals(RadioLimits.MAX_URL_LENGTH, b.strArg.length());
    }

    private static S2CPortableSources.Entry entry(int id) {
        S2CPortableSources.Entry e = new S2CPortableSources.Entry();
        e.entityId = id;
        e.session = 3;
        e.url = "https://stream.example.com/live";
        e.transport = Transport.RELAY;
        e.volume = 55;
        e.range = 16;
        e.headphones = true;
        e.title = "Artista - Música";
        e.mode = TuneMode.FREQUENCY;
        e.frequency = 987;
        e.signal = 81;
        e.station = "Perto FM";
        e.status = "akashicfm.status.reconnecting";
        e.x = -1234.5;
        e.y = 64;
        e.z = 98765.25;
        return e;
    }

    @Test
    void fontesIdaEVolta() {
        List<S2CPortableSources.Entry> list = new ArrayList<>();
        list.add(entry(10));
        list.add(entry(-4));
        ByteBuf buf = Unpooled.buffer();
        new S2CPortableSources(list).toBytes(buf);
        S2CPortableSources b = new S2CPortableSources();
        b.fromBytes(buf);
        assertEquals(0, buf.readableBytes());
        assertEquals(2, b.entries.size());
        S2CPortableSources.Entry e = b.entries.get(1);
        assertEquals(-4, e.entityId);
        assertEquals(entry(-4).signature(), e.signature());
        assertTrue(e.headphones);
        assertEquals(TuneMode.FREQUENCY, e.mode);
        assertEquals(-1234.5, e.x, 1e-9);
        assertEquals(64, e.y, 1e-3);
        assertEquals(98765.25, e.z, 1e-9);
    }

    @Test
    void fontesLimitadasESaneadas() {
        List<S2CPortableSources.Entry> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) list.add(entry(i));
        S2CPortableSources msg = new S2CPortableSources(list);
        assertEquals(S2CPortableSources.MAX_ENTRIES, msg.entries.size());

        // Valores hostis escritos à mão: saneados na leitura.
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(1);
        buf.writeInt(1);
        buf.writeInt(1);
        ByteBufUtils.writeUTF8String(buf, "http://a.com/\u0000x");
        buf.writeByte(200); // transporte fora do enum
        buf.writeByte(250); // volume acima de 100
        buf.writeShort(60000); // alcance absurdo
        buf.writeBoolean(false);
        ByteBufUtils.writeUTF8String(buf, "§cTítulo");
        buf.writeByte(9); // modo fora do enum
        buf.writeShort(-5);
        buf.writeByte(255);
        ByteBufUtils.writeUTF8String(buf, "");
        ByteBufUtils.writeUTF8String(buf, "");
        buf.writeDouble(Double.NaN);
        buf.writeFloat(Float.POSITIVE_INFINITY);
        buf.writeDouble(1e300);
        S2CPortableSources b = new S2CPortableSources();
        b.fromBytes(buf);
        S2CPortableSources.Entry e = b.entries.get(0);
        assertFalse(e.url.contains("\u0000"));
        assertEquals(Transport.values()[Transport.values().length - 1], e.transport);
        assertEquals(RadioLimits.VOLUME_MAX, e.volume);
        assertEquals(256, e.range);
        assertFalse(e.title.contains("§"));
        assertEquals(TuneMode.URL, e.mode);
        assertEquals(Frequency.MIN, e.frequency);
        assertEquals(100, e.signal);
        assertEquals(0, e.x, 0);
        assertEquals(0, e.y, 0);
        assertEquals(3.0e7, e.z, 0);
    }
}
