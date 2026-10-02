package com.akashiic.fm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;

import com.akashiic.fm.client.audio.AudioEngine;
import com.akashiic.fm.client.audio.RadioAudioController;
import com.akashiic.fm.client.relay.ClockSync;
import com.akashiic.fm.client.relay.RelayClient;
import com.akashiic.fm.client.spatial.OcclusionField;
import com.akashiic.fm.client.spatial.RoomProbe;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Liga o mod ao ciclo do cliente. Registrado nos dois barramentos (FML para ticks e rede, Forge para mundo).
 * <ul>
 * <li>início do tick: executa o que a rede deixou na fila;</li>
 * <li>fim do tick: o controlador decide o que toca e com que ganho;</li>
 * <li>início do frame: a engine alimenta o OpenAL;</li>
 * <li>mundo descarregado ou desconexão: silêncio imediato, nada fica tocando sozinho.</li>
 * </ul>
 */
public final class ClientEvents {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ClientTaskQueue.drain();
            ClockSync.tick(Minecraft.getMinecraft());
        } else {
            RadioAudioController.tick(Minecraft.getMinecraft());
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) AudioEngine.INSTANCE.frame();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        // No singleplayer o evento também chega pelos mundos do servidor integrado: só o do cliente importa.
        if (event.world == null || !event.world.isRemote) return;
        ClientRadioRegistry.removeWorld(event.world);
        AudioEngine.INSTANCE.stopAll();
        OcclusionField.INSTANCE.clear();
        RoomProbe.INSTANCE.reset();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        // O relay é thread-safe: encerra já. O resto vai para a thread principal; se já houver um mundo novo
        // quando a tarefa rodar (reconexão rápida), as rádios dele ficam.
        RelayClient.clear();
        ClientTaskQueue.add(() -> {
            ClockSync.reset();
            RadioAudioController.resetNowPlaying();
            ClientPortables.clear();
            World current = Minecraft.getMinecraft().theWorld;
            ClientRadioRegistry.retainWorld(current);
            if (current == null) AudioEngine.INSTANCE.stopAll();
        });
    }
}
