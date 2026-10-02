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
 * Handlers rodam na thread de rede e só enfileiram trabalho.
 */
public final class FmNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(AkashicFM.MODID);

    private FmNetwork() {}

    public static void init() {
        int id = 0;
        CHANNEL.registerMessage(C2SRadioAction.Handler.class, C2SRadioAction.class, id++, Side.SERVER);
        CHANNEL.registerMessage(S2CRadioNotice.Handler.class, S2CRadioNotice.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(S2CRadioPerms.Handler.class, S2CRadioPerms.class, id++, Side.CLIENT);
    }

    public static void sendTo(IMessage message, EntityPlayerMP player) {
        if (player != null && player.playerNetServerHandler != null) CHANNEL.sendTo(message, player);
    }

    public static void sendToServer(IMessage message) {
        CHANNEL.sendToServer(message);
    }
}
