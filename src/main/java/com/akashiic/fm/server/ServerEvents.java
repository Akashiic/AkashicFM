package com.akashiic.fm.server;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Permissions;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.network.ServerActionQueue;
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
        ServerActionQueue.drain();
        RelayService.tick();
        if (++tickCounter % MAINTENANCE_INTERVAL_TICKS == 0) SpeakerLinks.maintain();
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player == null) return;
        ServerActionQueue.LIMITER.forget(event.player.getUniqueID());
        RelayService.forget(event.player.getUniqueID());
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
        } else if (te instanceof TileSpeaker) {
            TileSpeaker speaker = (TileSpeaker) te;
            allowed = speaker.owner == null || SpeakerLinks.canAdminSpeaker(speaker, player)
                || (player != null && !(player instanceof FakePlayer) && Permissions.isOp(player));
        } else {
            return;
        }
        if (!allowed) {
            event.setCanceled(true);
            if (player != null && !(player instanceof FakePlayer)) {
                player.addChatMessage(new ChatComponentTranslation("akashicfm.protection.not_yours"));
            }
        }
    }
}
