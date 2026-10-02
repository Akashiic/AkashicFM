package com.akashiic.fm.client.gui;

import net.minecraft.client.gui.GuiScreen;

import com.akashiic.fm.AkashicFM;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.SimpleGuiConfig;

/**
 * Tela de config do mod. As opções do cliente (volume das rádios, limite de simultâneas, streams diretos)
 * valem na hora; as do servidor só têm efeito no singleplayer, onde o servidor é o próprio jogo.
 */
public final class FmConfigGui extends SimpleGuiConfig {

    public FmConfigGui(GuiScreen parent) throws ConfigException {
        super(parent, AkashicFM.MODID, AkashicFM.NAME);
    }
}
