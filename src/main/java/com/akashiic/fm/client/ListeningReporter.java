package com.akashiic.fm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.network.C2SListening;
import com.akashiic.fm.network.FmNetwork;

/**
 * Diz ao servidor se este cliente está ouvindo as rádios: áudio do mod ligado (tecla "silenciar rádios" ou tela de
 * config) e nenhum volume que as rádios usam em zero. Sem ouvir, o servidor tira o jogador da audiência do relay e
 * não gasta banda com ele. Manda uma vez por conexão e a cada mudança. Thread principal do cliente.
 */
public final class ListeningReporter {

    /** O último estado mandado nesta conexão, ou null se ainda nada. */
    private static Boolean sent;

    private ListeningReporter() {}

    /** Fim do tick do cliente. */
    public static void tick(Minecraft mc) {
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) return;
        boolean now = listening(mc);
        if (sent != null && sent == now) return;
        FmNetwork.sendToServer(new C2SListening(now));
        sent = now;
    }

    /** As mesmas condições em que o controlador deixa as rádios tocarem. */
    public static boolean listening(Minecraft mc) {
        return FmConfig.Client.enableAudio && FmConfig.Client.radioVolume > 0
            && mc.gameSettings.getSoundLevel(SoundCategory.MASTER) > 0f
            && mc.gameSettings.getSoundLevel(SoundCategory.RECORDS) > 0f;
    }

    /** Desconectou: a próxima conexão começa mandando de novo. */
    public static void reset() {
        sent = null;
    }
}
