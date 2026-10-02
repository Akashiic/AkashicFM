package com.akashiic.fm.client.gui;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import com.akashiic.fm.common.Frequency;

/**
 * Seletor de frequência das telas: « ‹ 98.7 MHz › » (±1,0 e ±0,1 MHz). Girar o dial não manda um pacote por
 * clique: o valor novo vai para o servidor {@link #SEND_DELAY_MS} depois do último clique. Até o servidor
 * confirmar, a tela mostra o valor escolhido (sem voltar ao antigo por um instante).
 */
final class FrequencyDial {

    static final long SEND_DELAY_MS = 300;
    /** Sem confirmação nesse tempo (recusado, sem permissão), volta a mostrar o valor do servidor. */
    static final long CONFIRM_TIMEOUT_MS = 2000;
    private static final int BUTTON_W = 14, BUTTON_H = 16;

    final FlatButton downBig, down, up, upBig;
    private final int x, y, width;
    private final IntConsumer sender;
    /** Escolhido e ainda não enviado (-1 = nada). */
    private int pending = -1;
    private long changedAtMs;
    /** Enviado e ainda não confirmado pelo estado (-1 = nada). */
    private int awaiting = -1;
    private long sentAtMs;

    /** Ocupa {@code width} pixels a partir de x; os quatro botões usam ids {@code firstId}..{@code firstId + 3}. */
    FrequencyDial(int firstId, int x, int y, int width, IntConsumer sender) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.sender = sender;
        downBig = new FlatButton(firstId, x, y, BUTTON_W, BUTTON_H, "«");
        down = new FlatButton(firstId + 1, x + BUTTON_W + 2, y, BUTTON_W, BUTTON_H, "‹");
        up = new FlatButton(firstId + 2, x + width - 2 * BUTTON_W - 2, y, BUTTON_W, BUTTON_H, "›");
        upBig = new FlatButton(firstId + 3, x + width - BUTTON_W, y, BUTTON_W, BUTTON_H, "»");
    }

    FlatButton[] buttons() {
        return new FlatButton[] { downBig, down, up, upBig };
    }

    /** Trata o clique se for de um dos botões; {@code current} é a frequência do estado. */
    boolean handle(int id, int current, long nowMs) {
        int delta;
        if (id == downBig.id) delta = -10;
        else if (id == down.id) delta = -1;
        else if (id == up.id) delta = 1;
        else if (id == upBig.id) delta = 10;
        else return false;
        int next = Frequency.clamp(shown(current, nowMs) + delta);
        pending = next;
        changedAtMs = nowMs;
        return true;
    }

    /** A cada tick da tela: manda o valor escolhido quando o jogador parou de girar. */
    void tick(int current, long nowMs) {
        if (awaiting >= 0 && (awaiting == current || nowMs - sentAtMs > CONFIRM_TIMEOUT_MS)) awaiting = -1;
        if (pending >= 0 && nowMs - changedAtMs >= SEND_DELAY_MS) flush(current, nowMs);
    }

    /** Manda já o que estiver pendente (a tela vai fechar). */
    void flush(int current, long nowMs) {
        if (pending < 0) return;
        int value = pending;
        pending = -1;
        if (value == current) return;
        awaiting = value;
        sentAtMs = nowMs;
        sender.accept(value);
    }

    /** O que a tela mostra: o escolhido, o enviado ainda sem confirmação, ou o do estado. */
    int shown(int current, long nowMs) {
        if (pending >= 0) return pending;
        if (awaiting >= 0 && nowMs - sentAtMs <= CONFIRM_TIMEOUT_MS) return awaiting;
        return current;
    }

    void setEnabled(boolean enabled) {
        for (FlatButton b : buttons()) b.enabled = enabled;
    }

    void setVisible(boolean visible) {
        for (FlatButton b : buttons()) b.visible = visible;
    }

    boolean visible() {
        return up.visible;
    }

    /** Desenha o valor entre os botões. */
    void draw(FontRenderer font, int current, boolean enabled, long nowMs) {
        if (!visible()) return;
        int l = x + 2 * BUTTON_W + 4, r = x + width - 2 * BUTTON_W - 4;
        Gui.drawRect(l, y, r, y + BUTTON_H, FlatButton.BORDER);
        Gui.drawRect(l + 1, y + 1, r - 1, y + BUTTON_H - 1, 0xFF0E1A12);
        String text = Frequency.format(shown(current, nowMs)) + " MHz";
        int color = !enabled ? FlatButton.TEXT_DISABLED : pending >= 0 || awaiting >= 0 ? 0xFFFFE070 : 0xFF7CFF8A;
        font.drawString(text, (l + r - font.getStringWidth(text)) / 2, y + (BUTTON_H - 8) / 2, color);
    }
}
