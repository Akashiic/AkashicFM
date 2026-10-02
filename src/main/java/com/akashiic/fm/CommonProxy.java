package com.akashiic.fm;

import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.content.FmContent;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.dev.DevE2E;
import com.akashiic.fm.dev.E2EServer;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;
import com.akashiic.fm.network.ServerActionQueue;
import com.akashiic.fm.server.ServerEvents;
import com.akashiic.fm.server.ServerRadioRegistry;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

/** Lado comum (servidor dedicado). O cliente estende em {@link ClientProxy}. */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        try {
            ConfigurationManager.registerConfig(FmConfig.Relay.class);
            ConfigurationManager.registerConfig(FmConfig.Direct.class);
            ConfigurationManager.registerConfig(FmConfig.Policy.class);
            ConfigurationManager.registerConfig(FmConfig.Limits.class);
            ConfigurationManager.registerConfig(FmConfig.Protection.class);
            ConfigurationManager.registerConfig(FmConfig.Recipes.class);
            ConfigurationManager.registerConfig(FmConfig.Client.class);
        } catch (ConfigException e) {
            throw new RuntimeException("AkashicFM: falha ao registrar o config", e);
        }
        FmNetwork.init();
        FmContent.registerBlocksAndItems();
        AkashicFM.LOG.info(
            "AkashicFM {} carregado (relay={}, direto={})",
            Tags.VERSION,
            FmConfig.Relay.enabled,
            FmConfig.Direct.enabled);
    }

    public void init(FMLInitializationEvent event) {
        FmContent.registerRecipes();
        ServerEvents events = new ServerEvents();
        FMLCommonHandler.instance()
            .bus()
            .register(events);
        MinecraftForge.EVENT_BUS.register(events);
    }

    public void serverStarting(FMLServerStartingEvent event) {
        if (DevE2E.enabled()) E2EServer.register();
    }

    public void serverStopped(FMLServerStoppedEvent event) {
        ServerRadioRegistry.clear();
        ServerActionQueue.clear();
    }

    // Ganchos do cliente: no servidor dedicado não fazem nada.

    public void onClientRadioLoaded(TileRadio radio) {}

    public void onClientRadioUnloaded(TileRadio radio) {}

    public void onClientRadioUpdated(TileRadio radio) {}

    public void openRadioGui(World world, int x, int y, int z) {}

    /** Executa na thread principal do cliente (no servidor dedicado, descarta). */
    public void enqueueClientTask(Runnable task) {}

    public void onRadioNotice(S2CRadioNotice notice) {}

    public void onRadioPerms(S2CRadioPerms perms) {}
}
