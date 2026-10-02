package com.akashiic.fm.network;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.RadioLimits;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** O que o jogador pode fazer na rádio, segundo o servidor (a GUI habilita os controles com isso). */
public final class S2CRadioPerms implements IMessage {

    public int x, y, z;
    public boolean canControl;
    public boolean canAdmin;
    /** Alcance máximo do config do servidor (o slider da GUI vai até aqui). */
    public int maxRange;

    public S2CRadioPerms() {}

    public S2CRadioPerms(int x, int y, int z, boolean canControl, boolean canAdmin, int maxRange) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.canControl = canControl;
        this.canAdmin = canAdmin;
        this.maxRange = maxRange;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        canControl = buf.readBoolean();
        canAdmin = buf.readBoolean();
        maxRange = RadioLimits.clamp(buf.readUnsignedShort(), RadioLimits.RANGE_MIN, RadioLimits.RANGE_HARD_MAX);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(canControl);
        buf.writeBoolean(canAdmin);
        buf.writeShort(maxRange);
    }

    public static final class Handler implements IMessageHandler<S2CRadioPerms, IMessage> {

        @Override
        public IMessage onMessage(S2CRadioPerms message, MessageContext ctx) {
            AkashicFM.proxy.enqueueClientTask(() -> AkashicFM.proxy.onRadioPerms(message));
            return null;
        }
    }
}
