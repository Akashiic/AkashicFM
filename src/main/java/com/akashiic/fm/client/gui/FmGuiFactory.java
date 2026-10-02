package com.akashiic.fm.client.gui;

import net.minecraft.client.gui.GuiScreen;

import com.gtnewhorizon.gtnhlib.config.SimpleGuiFactory;

/** Botão "Config" da lista de mods: abre o config do AkashicFM (gerado pelo GTNHLib a partir do FmConfig). */
public final class FmGuiFactory implements SimpleGuiFactory {

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return FmConfigGui.class;
    }
}
