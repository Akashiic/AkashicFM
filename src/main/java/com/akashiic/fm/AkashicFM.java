package com.akashiic.fm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLInterModComms;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

@Mod(
    modid = AkashicFM.MODID,
    name = AkashicFM.NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    // Vale esta faixa, não a do mcmod.info (lá useDependencyInformation=false; o ModDependencyTest confere).
    dependencies = "required-after:gtnhlib@[" + AkashicFM.MIN_GTNHLIB + ",)",
    guiFactory = "com.akashiic.fm.client.gui.FmGuiFactory")
public class AkashicFM {

    public static final String MODID = "akashicfm";
    public static final String NAME = "AkashicFM";
    /**
     * O GTNHLib mais antigo testado com o mod (o do GTNH 2.7.4). Com um mais antigo, o FML mostra a tela de
     * dependência: a 0.5.15 nem tem o construtor da tela de config que o mod usa. Abaixo da 0.9.62 (GTNH 2.7 e 2.8) o
     * /fm reload vai pelo caminho alternativo do {@code ConfigReload}.
     */
    public static final String MIN_GTNHLIB = "0.5.23";
    public static final Logger LOG = LogManager.getLogger(NAME);

    @SidedProxy(clientSide = "com.akashiic.fm.ClientProxy", serverSide = "com.akashiic.fm.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        // WAILA opcional: ele mesmo carrega a classe de integração (nenhuma referência direta a ela no mod).
        if (Loader.isModLoaded("Waila")) {
            FMLInterModComms.sendMessage("Waila", "register", "com.akashiic.fm.compat.waila.WailaCompat.register");
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped(event);
    }
}
