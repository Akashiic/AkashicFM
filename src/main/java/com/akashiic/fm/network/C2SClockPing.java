package com.akashiic.fm.network;

import com.akashiic.fm.server.relay.RelayService;
import com.akashiic.fm.server.relay.ServerClock;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Pedido de sincronia de relógio: leva o relógio do cliente (t0, µs). */
public final class C2SClockPing implements IMessage {

    public long clientMicros;

    public C2SClockPing() {}

    public C2SClockPing(long clientMicros) {
        this.clientMicros = clientMicros;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        clientMicros = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(clientMicros);
    }

    /**
     * t1 é a hora de chegada na camada de rede ({@link ClockStamps}), não a hora deste handler (que roda no tick,
     * depois da fila do vanilla). A resposta sai no tick seguinte com t2; o tempo parado entra em t2 − t1.
     */
    public static final class Handler implements IMessageHandler<C2SClockPing, IMessage> {

        @Override
        public IMessage onMessage(C2SClockPing message, MessageContext ctx) {
            long t1 = ClockStamps
                .take(ctx.getServerHandler().netManager, message.clientMicros, ServerClock.nowMicros());
            RelayService.offerClockPing(ctx.getServerHandler().playerEntity, message.clientMicros, t1);
            return null;
        }
    }
}
