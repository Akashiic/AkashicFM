package com.akashiic.fm.compat.oc;

import com.akashiic.fm.AkashicFM;

import li.cil.oc.api.Driver;

/**
 * Integração opcional com o OpenComputers: registra os drivers da rádio ({@code openfm_radio}, o nome do OpenFM, para
 * os scripts antigos funcionarem) e do transmissor ({@code akashicfm_transmitter}). O computador alcança o bloco por
 * um Adaptador encostado nele. Só é chamado com o OC instalado: sem ele, nenhuma classe deste pacote carrega.
 */
public final class OcCompat {

    private OcCompat() {}

    public static void register() {
        Driver.add(new RadioDriver());
        Driver.add(new TransmitterDriver());
        AkashicFM.LOG.info("OpenComputers: componentes openfm_radio e akashicfm_transmitter registrados");
    }
}
