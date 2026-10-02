package com.akashiic.fm.network;

import java.util.ArrayList;
import java.util.List;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.relay.FrameRing;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Frames Opus de uma estação, em ordem. Formato: estação, quantidade, sequência e PTS do primeiro e, por
 * frame, os deltas de sequência e PTS (varint) e os bytes. Pacote fora dos limites é descartado inteiro.
 */
public final class S2CAudio implements IMessage {

    public static final int MAX_FRAMES = 64;
    /** Maior pacote Opus possível. */
    public static final int MAX_FRAME_BYTES = 1275;

    public int stationId;
    public final List<FrameRing.Frame> frames = new ArrayList<>();
    /** Lido de um pacote inválido (o handler ignora). */
    public boolean invalid;

    public S2CAudio() {}

    public S2CAudio(int stationId, List<FrameRing.Frame> frames) {
        this.stationId = stationId;
        this.frames.addAll(frames);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        stationId = buf.readInt();
        int n = buf.readUnsignedByte();
        if (n == 0 || n > MAX_FRAMES) {
            invalid = true;
            return;
        }
        long seq = buf.readLong();
        long pts = buf.readLong();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                int dSeq = ByteBufUtils.readVarInt(buf, 5);
                int dPts = ByteBufUtils.readVarInt(buf, 5);
                if (dSeq <= 0 || dPts < 0) {
                    invalid = true;
                    return;
                }
                seq += dSeq;
                pts += dPts;
            }
            int len = ByteBufUtils.readVarShort(buf);
            if (len <= 0 || len > MAX_FRAME_BYTES || len > buf.readableBytes()) {
                invalid = true;
                return;
            }
            byte[] data = new byte[len];
            buf.readBytes(data);
            frames.add(new FrameRing.Frame(seq, pts, data));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int n = Math.min(frames.size(), MAX_FRAMES);
        buf.writeInt(stationId);
        buf.writeByte(n);
        if (n == 0) return;
        FrameRing.Frame first = frames.get(0);
        buf.writeLong(first.seq);
        buf.writeLong(first.ptsMs);
        for (int i = 0; i < n; i++) {
            FrameRing.Frame f = frames.get(i);
            if (i > 0) {
                FrameRing.Frame prev = frames.get(i - 1);
                ByteBufUtils.writeVarInt(buf, (int) (f.seq - prev.seq), 5);
                ByteBufUtils.writeVarInt(buf, (int) (f.ptsMs - prev.ptsMs), 5);
            }
            ByteBufUtils.writeVarShort(buf, f.data.length);
            buf.writeBytes(f.data);
        }
    }

    /** Thread de rede do cliente: entrega direto à fila do decoder da estação (thread-safe). */
    public static final class Handler implements IMessageHandler<S2CAudio, IMessage> {

        @Override
        public IMessage onMessage(S2CAudio message, MessageContext ctx) {
            if (!message.invalid) AkashicFM.proxy.onRelayAudio(message);
            return null;
        }
    }
}
