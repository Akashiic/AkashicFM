package com.akashiic.fm.network;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Para o dono do iPod, a cada meio segundo enquanto ele toca: fase, posição e duração da faixa (a tela interpola entre
 * um e outro) e o motivo quando não toca. A fila e a faixa atual vêm do NBT do item, que o inventário sincroniza.
 */
public final class S2CIPodStatus implements IMessage {

    /** Fase mostrada na tela do dono. */
    public enum Phase {
        STOPPED,
        RESOLVING,
        PLAYING,
        PAUSED,
        ERROR
    }

    public long itemId;
    public Phase phase = Phase.STOPPED;
    public long positionMs;
    /** 0 = desconhecida. */
    public long durationMs;
    public int index = -1;
    /** Chave de tradução (argumento depois de '|'), ou vazio. */
    public String status = "";

    public S2CIPodStatus() {}

    public S2CIPodStatus(long itemId, Phase phase, long positionMs, long durationMs, int index, String status) {
        this.itemId = itemId;
        this.phase = phase;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.index = index;
        this.status = status == null ? "" : status;
    }

    void sanitize() {
        if (phase == null) phase = Phase.STOPPED;
        positionMs = Math.max(0, Math.min(positionMs, 48L * 3600 * 1000));
        durationMs = Math.max(0, Math.min(durationMs, 48L * 3600 * 1000));
        status = TextSanitizer.clean(status == null ? "" : status, RadioLimits.MAX_STATUS_LENGTH);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        itemId = buf.readLong();
        int p = buf.readUnsignedByte();
        Phase[] all = Phase.values();
        phase = p < all.length ? all[p] : Phase.STOPPED;
        positionMs = buf.readInt() & 0xFFFFFFFFL;
        durationMs = buf.readInt() & 0xFFFFFFFFL;
        index = buf.readShort();
        status = ByteBufUtils.readUTF8String(buf);
        sanitize();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        sanitize();
        buf.writeLong(itemId);
        buf.writeByte(phase.ordinal());
        buf.writeInt((int) positionMs);
        buf.writeInt((int) durationMs);
        buf.writeShort(Math.max(-1, Math.min(Short.MAX_VALUE, index)));
        ByteBufUtils.writeUTF8String(buf, status);
    }

    public static final class Handler implements IMessageHandler<S2CIPodStatus, IMessage> {

        @Override
        public IMessage onMessage(S2CIPodStatus message, MessageContext ctx) {
            AkashicFM.proxy.enqueueClientTask(() -> AkashicFM.proxy.onIPodStatus(message));
            return null;
        }
    }
}
