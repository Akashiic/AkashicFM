package com.akashiic.fm.network;

import com.akashiic.fm.common.RadioLimits;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Pedido do cliente para mudar uma rádio. É só uma intenção: o servidor valida tudo (chunk carregado,
 * distância, permissão, limites) no tick principal antes de aplicar, e nunca repassa este pacote.
 */
public final class C2SRadioAction implements IMessage {

    public enum Action {

        REQUEST_PERMS,
        PLAY,
        STOP,
        SET_URL,
        SET_VOLUME,
        SET_RANGE,
        ADD_STATION,
        REMOVE_STATION,
        PLAY_STATION,
        SET_SCREEN_TEXT,
        SET_SCREEN_COLOR,
        SET_ACCESS,
        SET_REDSTONE_MODE,
        UNLINK_SPEAKER,
        UNLINK_ALL_SPEAKERS;

        static Action byOrdinal(int o) {
            Action[] v = values();
            return o >= 0 && o < v.length ? v[o] : null;
        }
    }

    public int x, y, z;
    public Action action;
    public int intArg;
    public String strArg = "";

    public C2SRadioAction() {}

    public C2SRadioAction(int x, int y, int z, Action action, int intArg, String strArg) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.intArg = intArg;
        this.strArg = strArg == null ? "" : strArg;
    }

    public static C2SRadioAction of(int x, int y, int z, Action action) {
        return new C2SRadioAction(x, y, z, action, 0, "");
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = Action.byOrdinal(buf.readUnsignedByte());
        intArg = buf.readInt();
        String s = ByteBufUtils.readUTF8String(buf);
        // Corta já na leitura: nada maior que o limite de URL chega ao processamento.
        strArg = s.length() > RadioLimits.MAX_URL_LENGTH ? s.substring(0, RadioLimits.MAX_URL_LENGTH) : s;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(action == null ? 255 : action.ordinal());
        buf.writeInt(intArg);
        ByteBufUtils.writeUTF8String(
            buf,
            strArg.length() > RadioLimits.MAX_URL_LENGTH ? strArg.substring(0, RadioLimits.MAX_URL_LENGTH) : strArg);
    }

    /** Roda na thread de rede: só enfileira (com rate limit). O processamento é no tick do servidor. */
    public static final class Handler implements IMessageHandler<C2SRadioAction, IMessage> {

        @Override
        public IMessage onMessage(C2SRadioAction message, MessageContext ctx) {
            if (message.action == null) return null;
            ServerActionQueue.offer(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
