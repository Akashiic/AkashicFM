package com.akashiic.fm.network;

import com.akashiic.fm.common.RadioLimits;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Pedido do cliente para mudar o rádio portátil de um slot do próprio inventário. Só uma intenção: o servidor
 * confere que o slot tem um portátil (o mesmo, pela identidade), aplica a mesma política de URL e limites da rádio
 * e passa pelo mesmo rate limit das ações de bloco.
 */
public final class C2SPortableAction implements IMessage {

    public enum Action {

        TURN_ON,
        TURN_OFF,
        SET_MODE,
        SET_URL,
        SET_FREQUENCY,
        SET_VOLUME;

        static Action byOrdinal(int o) {
            Action[] v = values();
            return o >= 0 && o < v.length ? v[o] : null;
        }
    }

    public int slot;
    /** Identidade do portátil que a tela viu (0 = item ainda sem identidade). */
    public long id;
    public Action action;
    public int intArg;
    public String strArg = "";

    public C2SPortableAction() {}

    public C2SPortableAction(int slot, long id, Action action, int intArg, String strArg) {
        this.slot = slot;
        this.id = id;
        this.action = action;
        this.intArg = intArg;
        this.strArg = strArg == null ? "" : strArg;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        slot = buf.readUnsignedByte();
        id = buf.readLong();
        action = Action.byOrdinal(buf.readUnsignedByte());
        intArg = buf.readInt();
        String s = ByteBufUtils.readUTF8String(buf);
        strArg = s.length() > RadioLimits.MAX_URL_LENGTH ? s.substring(0, RadioLimits.MAX_URL_LENGTH) : s;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(slot);
        buf.writeLong(id);
        buf.writeByte(action == null ? 255 : action.ordinal());
        buf.writeInt(intArg);
        ByteBufUtils.writeUTF8String(
            buf,
            strArg.length() > RadioLimits.MAX_URL_LENGTH ? strArg.substring(0, RadioLimits.MAX_URL_LENGTH) : strArg);
    }

    /** Só enfileira (com o rate limit das ações); o processamento é no início do tick do servidor. */
    public static final class Handler implements IMessageHandler<C2SPortableAction, IMessage> {

        @Override
        public IMessage onMessage(C2SPortableAction message, MessageContext ctx) {
            if (message.action == null) return null;
            ServerActionQueue.offer(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
