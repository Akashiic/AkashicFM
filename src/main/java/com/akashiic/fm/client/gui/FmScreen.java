package com.akashiic.fm.client.gui;

import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;

/** Tela de um bloco do mod (rádio, transmissor): recebe as respostas do servidor para aquele bloco. */
public interface FmScreen {

    boolean isFor(int x, int y, int z);

    void onPerms(S2CRadioPerms perms);

    void onNotice(S2CRadioNotice notice);
}
