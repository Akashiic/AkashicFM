package com.akashiic.fm.network;

import com.akashiic.fm.AkashicFM;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Resposta de sincronia: t0 (do cliente), t1 (chegada no servidor) e t2 (saída do servidor), em µs. */
public final class S2CClockPong implements IMessage {

    public long t0, t1, t2;

    public S2CClockPong() {}

    public S2CClockPong(long t0, long t1, long t2) {
        this.t0 = t0;
        this.t1 = t1;
        this.t2 = t2;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        t0 = buf.readLong();
        t1 = buf.readLong();
        t2 = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(t0);
        buf.writeLong(t1);
        buf.writeLong(t2);
    }

    /** Thread de rede do cliente: carimba t3 na chegada, antes de qualquer fila. */
    public static final class Handler implements IMessageHandler<S2CClockPong, IMessage> {

        @Override
        public IMessage onMessage(S2CClockPong message, MessageContext ctx) {
            AkashicFM.proxy.onClockPong(message, System.nanoTime() / 1000);
            return null;
        }
    }
}
