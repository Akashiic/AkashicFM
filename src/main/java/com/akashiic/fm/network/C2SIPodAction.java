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
    /** É para o bloco do iPod em (x, y, z), não para o item do slot. */
    public boolean block;
    public int x, y, z;

    public C2SIPodAction() {}

    public C2SIPodAction(int slot, long id, Action action, int intArg, String strArg) {
        this.slot = slot;
        this.id = id;
        this.action = action;
        this.intArg = intArg;
        this.strArg = strArg == null ? "" : strArg;
    }

    /** Para o bloco do iPod em (x, y, z). */
    public static C2SIPodAction forBlock(int x, int y, int z, long id, Action action, int intArg, String strArg) {
        C2SIPodAction m = new C2SIPodAction(0, id, action, intArg, strArg);
        m.block = true;
        m.x = x;
        m.y = y;
        m.z = z;
        return m;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        slot = buf.readUnsignedByte();
        id = buf.readLong();
        action = Action.byOrdinal(buf.readUnsignedByte());
        intArg = buf.readInt();
        String s = ByteBufUtils.readUTF8String(buf);
        strArg = s.length() > RadioLimits.MAX_URL_LENGTH ? s.substring(0, RadioLimits.MAX_URL_LENGTH) : s;
        // Sem o campo, ou com as coordenadas cortadas (pacote truncado): é do item.
        block = buf.isReadable() && buf.readBoolean() && buf.readableBytes() >= 12;
        if (block) {
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
        }
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
        buf.writeBoolean(block);
        if (block) {
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
        }
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
