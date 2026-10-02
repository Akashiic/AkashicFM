package com.akashiic.fm.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.audio.relay.FrameRing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class RelayPacketTest {

    private static List<FrameRing.Frame> frames(int n, long seq0, long pts0) {
        List<FrameRing.Frame> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] data = new byte[100 + i];
            for (int j = 0; j < data.length; j++) data[j] = (byte) (i * 7 + j);
            out.add(new FrameRing.Frame(seq0 + i, pts0 + 20L * i + (i >= 3 ? 500 : 0), data)); // um salto de PTS
                                                                                               // (rebase)
        }
        return out;
    }

    @Test
    void audioIdaEVolta() {
        List<FrameRing.Frame> in = frames(10, 1_000_000_000L, 123_456_789L);
        ByteBuf buf = Unpooled.buffer();
        new S2CAudio(7, in).toBytes(buf);
        S2CAudio out = new S2CAudio();
        out.fromBytes(buf);
        assertFalse(out.invalid);
        assertEquals(7, out.stationId);
        assertEquals(10, out.frames.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(in.get(i).seq, out.frames.get(i).seq);
            assertEquals(in.get(i).ptsMs, out.frames.get(i).ptsMs);
            assertArrayEquals(in.get(i).data, out.frames.get(i).data);
        }
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void audioCompacto() {
        ByteBuf buf = Unpooled.buffer();
        new S2CAudio(1, frames(10, 5, 5)).toBytes(buf);
        int payload = 0;
        for (FrameRing.Frame f : frames(10, 5, 5)) payload += f.data.length;
        assertTrue(buf.readableBytes() - payload < 21 + 10 * 5, "cabeçalho de " + (buf.readableBytes() - payload));
    }

    @Test
    void audioMalformadoEDescartadoSemExcecao() {
        ByteBuf zero = Unpooled.buffer();
        zero.writeInt(1);
        zero.writeByte(0);
        S2CAudio a = new S2CAudio();
        a.fromBytes(zero);
        assertTrue(a.invalid);

        ByteBuf huge = Unpooled.buffer();
        huge.writeInt(1);
        huge.writeByte(1);
        huge.writeLong(0);
        huge.writeLong(0);
        huge.writeShort(0x7FFF); // varShort de tamanho absurdo
        huge.writeByte(0x7F);
        S2CAudio b = new S2CAudio();
        b.fromBytes(huge);
        assertTrue(b.invalid);
    }

    @Test
    void listenIdaEVolta() {
        ByteBuf buf = Unpooled.buffer();
        S2CListen.start(42, "https://x.com/live", 1500)
            .toBytes(buf);
        S2CListen m = new S2CListen();
        m.fromBytes(buf);
        assertTrue(m.start);
        assertEquals(42, m.stationId);
        assertEquals("https://x.com/live", m.url);
        assertEquals(1500, m.latencyMs);
        ByteBuf stop = Unpooled.buffer();
        S2CListen.stop(42)
            .toBytes(stop);
        S2CListen s = new S2CListen();
        s.fromBytes(stop);
        assertFalse(s.start);
        assertEquals(42, s.stationId);
    }

    @Test
    void relogioIdaEVolta() {
        ByteBuf buf = Unpooled.buffer();
        new S2CClockPong(1, 2, 3).toBytes(buf);
        S2CClockPong p = new S2CClockPong();
        p.fromBytes(buf);
        assertEquals(1, p.t0);
        assertEquals(2, p.t1);
        assertEquals(3, p.t2);
        ByteBuf ping = Unpooled.buffer();
        new C2SClockPing(99).toBytes(ping);
        C2SClockPing q = new C2SClockPing();
        q.fromBytes(ping);
        assertEquals(99, q.clientMicros);
    }
}
