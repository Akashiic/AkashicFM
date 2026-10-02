package com.akashiic.fm.network;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.RadioLimits;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** O servidor começa (ou para) de mandar o áudio de uma estação do relay para este jogador. */
public final class S2CListen implements IMessage {

    public boolean start;
    public int stationId;
    /** URL da estação (a mesma do estado das rádios), para o cliente saber quais rádios ela alimenta. */
    public String url = "";
    /** Atraso fixo entre o PTS e a reprodução, igual para todos os ouvintes. */
    public int latencyMs;

    public S2CListen() {}

    public static S2CListen start(int stationId, String url, int latencyMs) {
        S2CListen m = new S2CListen();
        m.start = true;
        m.stationId = stationId;
        m.url = url;
        m.latencyMs = latencyMs;
        return m;
    }

    public static S2CListen stop(int stationId) {
        S2CListen m = new S2CListen();
        m.stationId = stationId;
        return m;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        start = buf.readBoolean();
        stationId = buf.readInt();
        String u = ByteBufUtils.readUTF8String(buf);
        url = u.length() > RadioLimits.MAX_URL_LENGTH ? u.substring(0, RadioLimits.MAX_URL_LENGTH) : u;
        latencyMs = RadioLimits.clamp(buf.readUnsignedShort(), 100, 10_000);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(start);
        buf.writeInt(stationId);
        ByteBufUtils.writeUTF8String(buf, url.length() > RadioLimits.MAX_URL_LENGTH ? url.substring(0, 512) : url);
        buf.writeShort(latencyMs);
    }

    /** Thread de rede do cliente: o registro do relay é thread-safe, então trata direto (sem esperar o tick). */
    public static final class Handler implements IMessageHandler<S2CListen, IMessage> {

        @Override
        public IMessage onMessage(S2CListen message, MessageContext ctx) {
            AkashicFM.proxy.onRelayListen(message);
            return null;
        }
    }
}
