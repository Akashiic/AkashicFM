package com.akashiic.fm.network;

import com.akashiic.fm.AkashicFM;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Resposta do servidor a uma ação (sucesso ou motivo da recusa), mostrada na GUI da rádio. */
public final class S2CRadioNotice implements IMessage {

    public int x, y, z;
    public boolean error;
    /** Chave de tradução (lang). */
    public String key = "";
    /** Argumento opcional da tradução. */
    public String arg = "";

    public S2CRadioNotice() {}

    public S2CRadioNotice(int x, int y, int z, boolean error, String key, String arg) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.error = error;
        this.key = key;
        this.arg = arg == null ? "" : arg;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        error = buf.readBoolean();
        key = limit(ByteBufUtils.readUTF8String(buf), 96);
        arg = limit(ByteBufUtils.readUTF8String(buf), 256);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(error);
        ByteBufUtils.writeUTF8String(buf, limit(key, 96));
        ByteBufUtils.writeUTF8String(buf, limit(arg, 256));
    }

    private static String limit(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static final class Handler implements IMessageHandler<S2CRadioNotice, IMessage> {

        @Override
        public IMessage onMessage(S2CRadioNotice message, MessageContext ctx) {
            AkashicFM.proxy.enqueueClientTask(() -> AkashicFM.proxy.onRadioNotice(message));
            return null;
        }
    }
}
