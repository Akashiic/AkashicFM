package com.akashiic.fm.server;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Permissions;
import com.akashiic.fm.content.BlockCeilingSpeaker;
import com.akashiic.fm.content.TileCeilingSpeaker;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.network.ServerActionQueue;
import com.akashiic.fm.server.ipod.IPodService;
import com.akashiic.fm.server.relay.RelayService;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Eventos do lado do servidor. Registrado no barramento do FML (ticks, login) e no do Forge (quebra). */
public final class ServerEvents {

    private static final int MAINTENANCE_INTERVAL_TICKS = 100;
    private int tickCounter;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        FmCommand.runQueued(); // /fm que chegou pelo RCON
        ServerActionQueue.drain();
        FrequencyService.tick(); // antes do relay: a audiência já vê a URL sintonizada neste tick
        IPodService.tick(); // antes dos portáteis: a fonte do iPod já sai com a estação deste ciclo
        PortableSources.tick(); // idem: o relay já vê quem precisa das estações dos portáteis
        RelayService.tick();
        if (++tickCounter % MAINTENANCE_INTERVAL_TICKS == 0) SpeakerLinks.maintain();
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player == null) return;
        ServerActionQueue.LIMITER.forget(event.player.getUniqueID());
        RelayService.forget(event.player.getUniqueID());
        IPodService.forget(event.player.getUniqueID());
    }

    /**
     * Protege rádios e caixas privadas. Nunca lança: máquinas do GregTech e outros FakePlayers só são
     * recusados (o OpenFM dava NPE aqui e quebrava os mineradores do GT no mapa inteiro).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.world == null || event.world.isRemote || !FmConfig.Protection.protectPrivateBlocks) return;
        TileEntity te = event.world.getTileEntity(event.x, event.y, event.z);
        EntityPlayer player = event.getPlayer();
        boolean allowed;
        if (te instanceof TileRadio) {
            allowed = Permissions.canBreak(((TileRadio) te).state, player);
        } else if (te instanceof TileTransmitter) {
            TileTransmitter t = (TileTransmitter) te;
            allowed = Permissions.canBreak(t.state.owner, t.state.access, player);
        } else if (te instanceof TileSpeaker) {
            allowed = canBreakSpeaker((TileSpeaker) te, player);
        } else {
            allowed = true;
        }
        String reason = "akashicfm.protection.not_yours";
        // O bloco que segura um alto-falante de teto/parede: quebrá-lo derrubaria a caixa de outro jogador.
        if (allowed && holdsProtectedSpeaker(event.world, event.x, event.y, event.z, player)) {
            allowed = false;
            reason = "akashicfm.protection.holds_speaker";
        }
        if (!allowed) {
            event.setCanceled(true);
            if (player != null && !(player instanceof FakePlayer)) {
                player.addChatMessage(new ChatComponentTranslation(reason));
            }
        }
    }

    private static boolean canBreakSpeaker(TileSpeaker speaker, EntityPlayer player) {
        return speaker.owner == null || SpeakerLinks.canAdminSpeaker(speaker, player)
            || (player != null && !(player instanceof FakePlayer) && Permissions.isOp(player));
    }

    /** Algum alto-falante de teto/parede preso em (x, y, z) que este jogador não pode derrubar. Não carrega chunk. */
    static boolean holdsProtectedSpeaker(World world, int x, int y, int z, EntityPlayer player) {
        int[][] around = { { 0, -1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
        for (int[] d : around) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (ny < 0 || ny > 255 || !world.blockExists(nx, ny, nz)) continue;
            // Barato primeiro: isto roda em toda quebra (mineradores do GregTech incluídos).
            if (!(world.getBlock(nx, ny, nz) instanceof BlockCeilingSpeaker)) continue;
            TileEntity te = world.getTileEntity(nx, ny, nz);
            if (!(te instanceof TileCeilingSpeaker)) continue;
            int[] support = ((TileCeilingSpeaker) te).supportPos();
            if (support[0] == x && support[1] == y && support[2] == z && !canBreakSpeaker((TileSpeaker) te, player))
                return true;
        }
        return false;
    }
}
