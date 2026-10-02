package com.akashiic.fm.dev;

import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.network.ServerActionQueue;
import com.akashiic.fm.server.ServerRadioRegistry;
import com.akashiic.fm.server.relay.RelayService;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Lado servidor do E2E: liga o modo direto (o relay é da Fase 3) e, a cada mensagem "e2e:..." no chat,
 * registra no log os chunks carregados e os contadores da fila de ações. É assim que o teste prova que
 * pacotes maliciosos não carregaram chunk nenhum.
 */
public final class E2EServer {

    public static void register() {
        boolean direct = "direct".equals(DevE2E.transport());
        FmConfig.Relay.enabled = !direct;
        FmConfig.Direct.enabled = direct;
        MinecraftForge.EVENT_BUS.register(new E2EServer());
        DevE2E.log("servidor pronto (transporte {})", direct ? "direto" : "relay");
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (event.message == null || !event.message.startsWith("e2e:")) return;
        if (event.message.startsWith("e2e:relay-stats") && event.player != null) {
            long bytes = RelayService.bytesSentTo(event.player.getUniqueID());
            DevE2E.log(
                "relay: {} recebeu {} bytes; estações={} ouvintes={}",
                event.username,
                bytes,
                RelayService.stationCount(),
                RelayService.listenerCount());
            event.player.addChatMessage(
                new ChatComponentText(
                    "e2e-result relay bytes=" + bytes
                        + " at="
                        + System.nanoTime() / 1_000_000L
                        + " stations="
                        + RelayService.stationCount()));
        }
        if (event.message.startsWith("e2e:check-unloaded ") && event.player != null) {
            // Responde ao cliente se o chunk da coordenada está carregado (chunkExists nunca carrega).
            String[] a = event.message.split(" ");
            int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
            boolean loaded = ((ChunkProviderServer) event.player.worldObj.getChunkProvider())
                .chunkExists(x >> 4, z >> 4);
            DevE2E.log("chunk do bloco ({}, {}) carregado={}", x, z, loaded);
            event.player.addChatMessage(new ChatComponentText("e2e-result chunk " + x + " " + z + " loaded=" + loaded));
        }
        DevE2E.log(
            "server mark from={} msg='{}' chunks={} radios={} {}",
            event.username,
            event.message,
            loadedChunks(),
            ServerRadioRegistry.snapshot()
                .size(),
            ServerActionQueue.stats());
    }

    static int loadedChunks() {
        int total = 0;
        for (WorldServer w : DimensionManager.getWorlds()) {
            if (w != null && w.getChunkProvider() instanceof ChunkProviderServer) {
                total += ((ChunkProviderServer) w.getChunkProvider()).getLoadedChunkCount();
            }
        }
        return total;
    }
}
