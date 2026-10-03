package com.akashiic.fm.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.akashiic.fm.AkashicFM;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * Canal de rede do mod. Regra: só a thread principal envia (o SimpleNetworkWrapper do 1.7.10 guarda o
 * destino em atributos de um canal compartilhado, então enviar de duas threads pode trocar o destinatário).
 * No 1.7.10 os pacotes de mod passam pela fila do vanilla e os handlers rodam na thread principal; mesmo
 * assim todos são seguros em qualquer thread (só enfileiram ou usam estruturas thread-safe).
 */
public final class FmNetwork {

    public static final String CHANNEL_NAME = AkashicFM.MODID;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);

    private FmNetwork() {}

    public static void init() {
        int id = 0;
        CHANNEL.registerMessage(C2SRadioAction.Handler.class, C2SRadioAction.class, id++, Side.SERVER);
        CHANNEL.registerMessage(S2CRadioNotice.Handler.class, S2CRadioNotice.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(S2CRadioPerms.Handler.class, S2CRadioPerms.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(S2CListen.Handler.class, S2CListen.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(S2CAudio.Handler.class, S2CAudio.class, id++, Side.CLIENT);
        // ClockStamps identifica ping/pong pelo discriminador: os ids 5 e 6 não podem mudar.
        CHANNEL.registerMessage(
            C2SClockPing.Handler.class,
            C2SClockPing.class,
            ClockStamps.PING_DISCRIMINATOR,
            Side.SERVER);
        CHANNEL.registerMessage(
            S2CClockPong.Handler.class,
            S2CClockPong.class,
            ClockStamps.PONG_DISCRIMINATOR,
            Side.CLIENT);
        // Fase 6b, depois dos ids fixos do relógio.
        id = ClockStamps.PONG_DISCRIMINATOR + 1;
        CHANNEL.registerMessage(C2SPortableAction.Handler.class, C2SPortableAction.class, id++, Side.SERVER);
        CHANNEL.registerMessage(S2CPortableSources.Handler.class, S2CPortableSources.class, id++, Side.CLIENT);
        // Fase 7b.
        CHANNEL.registerMessage(C2SListening.Handler.class, C2SListening.class, id++, Side.SERVER);
        // Fase 8c.
        CHANNEL.registerMessage(C2SIPodAction.Handler.class, C2SIPodAction.class, id++, Side.SERVER);
        CHANNEL.registerMessage(S2CIPodStatus.Handler.class, S2CIPodStatus.class, id++, Side.CLIENT);
    }

    public static void sendTo(IMessage message, EntityPlayerMP player) {
        if (player != null && player.playerNetServerHandler != null) CHANNEL.sendTo(message, player);
    }

    public static void sendToServer(IMessage message) {
        CHANNEL.sendToServer(message);
    }
}
