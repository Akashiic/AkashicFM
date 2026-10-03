package com.akashiic.fm.network;

import com.akashiic.fm.server.relay.RelayService;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * O cliente está ouvindo as rádios ou não (áudio do mod desligado, tecla "silenciar rádios", volume em zero). Quem
 * não ouve sai da audiência do relay como quem está longe: o servidor para de mandar áudio a ele (banda de upload)
 * e o cliente para de decodificar. Manda ao entrar e a cada mudança.
 */
public final class C2SListening implements IMessage {

    public boolean listening = true;

    public C2SListening() {}

    public C2SListening(boolean listening) {
        this.listening = listening;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        listening = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(listening);
    }

    /** Só grava um booleano num conjunto thread-safe: a audiência o lê no próximo ciclo (sem amplificação). */
    public static final class Handler implements IMessageHandler<C2SListening, IMessage> {

        @Override
        public IMessage onMessage(C2SListening message, MessageContext ctx) {
            RelayService.setListening(ctx.getServerHandler().playerEntity.getUniqueID(), message.listening);
            return null;
        }
    }
}
