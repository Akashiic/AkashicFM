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
     * Thread de rede do servidor: carimba a chegada (t1) aqui mesmo, antes de qualquer fila, e deixa a resposta
     * para o tick (só a thread principal envia). O tempo na fila entra em t2 - t1 e não vira erro.
     */
    public static final class Handler implements IMessageHandler<C2SClockPing, IMessage> {

        @Override
        public IMessage onMessage(C2SClockPing message, MessageContext ctx) {
            RelayService
                .offerClockPing(ctx.getServerHandler().playerEntity, message.clientMicros, ServerClock.nowMicros());
            return null;
        }
    }
}
