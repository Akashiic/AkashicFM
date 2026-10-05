package com.akashiic.fm;

import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.compat.baubles.BaublesCompat;
import com.akashiic.fm.compat.oc.OcCompat;
import com.akashiic.fm.content.FmContent;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.dev.DevE2E;
import com.akashiic.fm.dev.E2EServer;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CAudio;
import com.akashiic.fm.network.S2CClockPong;
import com.akashiic.fm.network.S2CIPodSearchResults;
import com.akashiic.fm.network.S2CIPodStatus;
import com.akashiic.fm.network.S2CListen;
import com.akashiic.fm.network.S2CPortableSources;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;
import com.akashiic.fm.network.ServerActionQueue;
import com.akashiic.fm.server.AuditLog;
import com.akashiic.fm.server.FmCommand;
import com.akashiic.fm.server.FrequencyService;
import com.akashiic.fm.server.Moderation;
import com.akashiic.fm.server.PortableSources;
import com.akashiic.fm.server.RadioScripting;
import com.akashiic.fm.server.ServerEvents;
import com.akashiic.fm.server.ServerPolicy;
import com.akashiic.fm.server.ServerRadioRegistry;
import com.akashiic.fm.server.ipod.IPodService;
import com.akashiic.fm.server.relay.RelayService;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
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
            ConfigurationManager.registerConfig(FmConfig.Transmitter.class);
            ConfigurationManager.registerConfig(FmConfig.Portable.class);
            ConfigurationManager.registerConfig(FmConfig.IPod.class);
            ConfigurationManager.registerConfig(FmConfig.OpenComputers.class);
            ConfigurationManager.registerConfig(FmConfig.Recipes.class);
            ConfigurationManager.registerConfig(FmConfig.Client.class);
        } catch (ConfigException e) {
            throw new RuntimeException("AkashicFM: falha ao registrar o config", e);
        }
        FmNetwork.init();
        FmContent.registerBlocksAndItems();
        // Fone nos slots de cabeça/brinco: com o Baubles Expanded, garante que esses slots existam.
        if (Loader.isModLoaded("Baubles|Expanded")) BaublesCompat.requestSlots();
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
        // OpenComputers opcional: os drivers ficam num pacote que só carrega com o OC instalado. Um OC com API
        // incompatível desliga só os componentes, nunca derruba o jogo.
        if (Loader.isModLoaded("OpenComputers")) {
            try {
                OcCompat.register();
            } catch (LinkageError | RuntimeException e) {
                AkashicFM.LOG.warn("OpenComputers: versão incompatível, componentes desligados ({})", e.toString());
            }
        }
    }

    public void serverStarting(FMLServerStartingEvent event) {
        if (DevE2E.enabled()) E2EServer.register();
        ServerPolicy.setRelayAvailable(FmConfig.Relay.enabled);
        FmCommand.markServerThread();
        event.registerServerCommand(new FmCommand());
    }

    public void serverStopped(FMLServerStoppedEvent event) {
        ServerPolicy.setRelayAvailable(false);
        IPodService.shutdown();
        RelayService.shutdown();
        FrequencyService.clear();
        PortableSources.clear();
        Moderation.clear();
        FmCommand.clear();
        RadioScripting.clear();
        AuditLog.close();
        ServerRadioRegistry.clear();
        ServerActionQueue.clear();
    }

    // Ganchos do cliente: no servidor dedicado não fazem nada.

    public void onClientRadioLoaded(TileRadio radio) {}

    public void onClientRadioUnloaded(TileRadio radio) {}

    public void onClientRadioUpdated(TileRadio radio) {}

    public void openRadioGui(World world, int x, int y, int z) {}

    public void openTransmitterGui(World world, int x, int y, int z) {}

    /** Tela do rádio portátil do slot (cliente). */
    public void openPortableGui(int slot) {}

    /** Tela do iPod do slot (cliente). */
    public void openIPodGui(int slot) {}

    public void onIPodStatus(S2CIPodStatus message) {}

    public void onIPodSearchResults(S2CIPodSearchResults message) {}

    public void onPortableSources(S2CPortableSources message) {}

    /** Executa na thread principal do cliente (no servidor dedicado, descarta). */
    public void enqueueClientTask(Runnable task) {}

    public void onRadioNotice(S2CRadioNotice notice) {}

    public void onRadioPerms(S2CRadioPerms perms) {}

    /** Thread de rede do cliente. */
    public void onRelayListen(S2CListen message) {}

    /** Thread de rede do cliente. */
    public void onRelayAudio(S2CAudio message) {}

    /** Thread de rede do cliente; {@code t3} carimbado na chegada (µs). */
    public void onClockPong(S2CClockPong message, long t3) {}
}
