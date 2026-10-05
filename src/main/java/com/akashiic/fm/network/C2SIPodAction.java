package com.akashiic.fm.network;

import com.akashiic.fm.common.RadioLimits;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Pedido do cliente para o iPod de um slot do próprio inventário. Só uma intenção: o servidor confere que o slot tem
 * um iPod (o mesmo, pela identidade), valida o link (allowlist de hosts) e passa pelo mesmo rate limit das outras
 * ações.
 */
public final class C2SIPodAction implements IMessage {

    public enum Action {

        /** {@code strArg}: link do SoundCloud, YouTube ou Spotify, ou texto para buscar. */
        ADD,
        /** {@code intArg}: índice na fila. */
        PLAY,
        /** Liga (da faixa atual) ou pausa/retoma. */
        TOGGLE,
        STOP,
        NEXT,
        PREVIOUS,
        /** {@code intArg}: índice na fila. */
        REMOVE,
        CLEAR,
        SHUFFLE,
        /** Passa para o próximo modo de repetição. */
        REPEAT,
        /** {@code intArg}: 0-100. */
        VOLUME,
        /**
         * Busca por nome: {@code strArg} o texto, {@code intArg} = {@code requestId << 4 | serviço} (ordinal de
         * {@code IPodTrack.Source}). Um link colado vira {@link #ADD}.
         */
        SEARCH,
        /** Põe um resultado da última busca na fila: {@code intArg} = {@code requestId << 4 | índice}. */
        ADD_RESULT,
        /** Toca um resultado agora (logo depois da atual): {@code intArg} como em {@link #ADD_RESULT}. */
        PLAY_RESULT,
        /** A tela abriu: o servidor responde o que ela pode oferecer (busca do Spotify etc.). */
        HELLO;

        /** {@code requestId << 4 | baixo}, com o pedido limitado a 27 bits. */
        public static int pack(int requestId, int low) {
            return (requestId & 0x7FFFFFF) << 4 | (low & 0xF);
        }

        static Action byOrdinal(int o) {
            Action[] v = values();
            return o >= 0 && o < v.length ? v[o] : null;
        }
    }

    public int slot;
    /** Identidade do iPod que a tela viu (0 = item ainda sem identidade). */
    public long id;
    public Action action;
    public int intArg;
    public String strArg = "";

    public C2SIPodAction() {}

    public C2SIPodAction(int slot, long id, Action action, int intArg, String strArg) {
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
    public static final class Handler implements IMessageHandler<C2SIPodAction, IMessage> {

        @Override
        public IMessage onMessage(C2SIPodAction message, MessageContext ctx) {
            if (message.action == null) return null;
            ServerActionQueue.offer(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
