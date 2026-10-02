package com.akashiic.fm.dev;

import net.minecraft.block.Block;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
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
 * Lado servidor do E2E: escolhe o transporte e, a cada mensagem "e2e:..." no chat, registra no log os chunks
 * carregados e os contadores da fila de ações (é assim que o teste prova que pacotes maliciosos não carregaram
 * chunk nenhum). Também responde aos pedidos do roteiro: estatísticas do relay, chunk carregado e
 * {@code e2e:fill} (cuboide de um bloco, para as paredes e salas do teste de acústica; o 1.7.10 não tem /fill).
 */
public final class E2EServer {

    public static void register() {
        boolean direct = "direct".equals(DevE2E.transport());
        FmConfig.Relay.enabled = !direct;
        FmConfig.Direct.enabled = direct;
        // Alcances curtos: o teste de antenas cabe perto da rádio, onde o chunk está carregado (view-distance 4).
        FmConfig.Transmitter.baseRange = DevE2E.TRANSMITTER_BASE_RANGE;
        FmConfig.Transmitter.rangePerAntenna = DevE2E.TRANSMITTER_RANGE_PER_ANTENNA;
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
        if (event.message.startsWith("e2e:fill ") && event.player != null) {
            fill(event.player.worldObj, event.message.split(" "));
            event.player.addChatMessage(new ChatComponentText("e2e-result fill " + event.message.substring(9)));
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

    /** e2e:fill x0 y0 z0 x1 y1 z1 modid:bloco — até 4096 blocos, notificando os clientes. */
    private static void fill(World world, String[] a) {
        int x0 = Integer.parseInt(a[1]), y0 = Integer.parseInt(a[2]), z0 = Integer.parseInt(a[3]);
        int x1 = Integer.parseInt(a[4]), y1 = Integer.parseInt(a[5]), z1 = Integer.parseInt(a[6]);
        Block block = Block.getBlockFromName(a[7]);
        if (block == null) throw new IllegalArgumentException("bloco desconhecido: " + a[7]);
        int lx = Math.min(x0, x1), hx = Math.max(x0, x1), ly = Math.max(0, Math.min(y0, y1)),
            hy = Math.min(255, Math.max(y0, y1)), lz = Math.min(z0, z1), hz = Math.max(z0, z1);
        long volume = (long) (hx - lx + 1) * (hy - ly + 1) * (hz - lz + 1);
        if (volume > 4096) throw new IllegalArgumentException("volume grande demais: " + volume);
        for (int x = lx; x <= hx; x++) for (int y = ly; y <= hy; y++) for (int z = lz; z <= hz; z++) {
            world.setBlock(x, y, z, block, 0, 3);
        }
        DevE2E.log("fill {} blocos de {} em ({},{},{})..({},{},{})", volume, a[7], lx, ly, lz, hx, hy, hz);
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
