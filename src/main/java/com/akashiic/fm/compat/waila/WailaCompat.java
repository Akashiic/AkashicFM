package com.akashiic.fm.compat.waila;

import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;

import cpw.mods.fml.common.FMLCommonHandler;
import mcp.mobius.waila.api.IWailaRegistrar;

/**
 * Integração opcional com o WAILA. Só é carregada pelo próprio WAILA, via IMC ("register"), quando ele está
 * instalado: nenhuma classe do mod referencia esta diretamente, então sem o WAILA nada daqui é carregado.
 */
public final class WailaCompat {

    private WailaCompat() {}

    /**
     * Chamado pelo WAILA (nome passado por IMC no init do mod). O corpo do tooltip é desenhado no cliente e usa
     * classes do cliente: num servidor dedicado com WAILA não registra nada.
     */
    public static void register(IWailaRegistrar registrar) {
        if (!FMLCommonHandler.instance()
            .getSide()
            .isClient()) return;
        WailaRadioProvider provider = new WailaRadioProvider();
        registrar.registerBodyProvider(provider, TileRadio.class);
        registrar.registerBodyProvider(provider, TileSpeaker.class);
    }
}
