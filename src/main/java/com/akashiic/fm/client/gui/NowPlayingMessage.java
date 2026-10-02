package com.akashiic.fm.client.gui;

import net.minecraft.client.Minecraft;

/**
 * Mensagem "Tocando agora" acima da barra de itens, a mesma dos discos da jukebox (traduzida pelo próprio
 * Minecraft, com o efeito arco-íris). É o lugar que o jogo reserva para música, então não briga com conquistas,
 * minimapa nem outros painéis. Quem decide quando mostrar é o {@link com.akashiic.fm.common.NowPlayingTracker}.
 * Thread principal.
 */
public final class NowPlayingMessage {

    public static final NowPlayingMessage INSTANCE = new NowPlayingMessage();

    /** O vanilla mostra a mensagem por 60 ticks. */
    static final long DURATION_MS = 3000;

    private long shownAtMs;
    private boolean shown;
    /** Diagnóstico (E2E): quantas mensagens e o último título. */
    private int shownCount;
    private String lastText = "";

    private NowPlayingMessage() {}

    /** @param subtitle texto da tela da rádio (nome da estação), ou vazio */
    public void show(String text, String subtitle, long nowMs) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null) return;
        String line = subtitle == null || subtitle.isEmpty() ? text : text + " · " + subtitle;
        mc.ingameGUI.setRecordPlayingMessage(line);
        shown = true;
        shownAtMs = nowMs;
        shownCount++;
        lastText = text;
    }

    public void reset() {
        shown = false;
    }

    public int shownCount() {
        return shownCount;
    }

    public String lastText() {
        return lastText;
    }

    /** Ainda na tela (para o E2E saber quando capturar). */
    public boolean visible(long nowMs) {
        return shown && nowMs - shownAtMs < DURATION_MS;
    }
}
