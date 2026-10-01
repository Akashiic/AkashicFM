package com.akashiic.fm;

import com.akashiic.fm.common.FmConfig;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        try {
            ConfigurationManager.registerConfig(FmConfig.Relay.class);
            ConfigurationManager.registerConfig(FmConfig.Direct.class);
            ConfigurationManager.registerConfig(FmConfig.Policy.class);
            ConfigurationManager.registerConfig(FmConfig.Limits.class);
        } catch (ConfigException e) {
            throw new RuntimeException("AkashicFM: falha ao registrar o config", e);
        }
        AkashicFM.LOG.info(
            "AkashicFM {} carregado (relay={}, direto={})",
            Tags.VERSION,
            FmConfig.Relay.enabled,
            FmConfig.Direct.enabled);
    }

    public void init(FMLInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
