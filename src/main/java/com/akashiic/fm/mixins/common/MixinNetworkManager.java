package com.akashiic.fm.mixins.common;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashiic.fm.network.ClockStamps;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.channel.ChannelHandlerContext;

/**
 * Carimba, na thread do netty, a chegada dos pings/pongs de sincronia do AkashicFM (ver {@link ClockStamps}).
 * Só lê; o pacote segue o caminho normal para a fila do vanilla. {@code channelRead0} é método do netty, com o
 * mesmo nome em produção, por isso {@code remap = false}.
 */
@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V",
        at = @At("HEAD"),
        remap = false)
    private void akashicfm$stampClock(ChannelHandlerContext ctx, Packet packet, CallbackInfo ci) {
        if (packet instanceof FMLProxyPacket) ClockStamps.onArrival(this, (FMLProxyPacket) packet);
    }
}
