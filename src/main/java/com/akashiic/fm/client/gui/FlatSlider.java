package com.akashiic.fm.client.gui;

import java.util.function.IntConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;

/**
 * Slider inteiro de {@code min} a {@code max}. Arrastar só muda o valor na tela; o valor vai para o servidor
 * uma vez, ao soltar o botão do mouse (um arrasto não vira dezenas de pacotes).
 */
final class FlatSlider extends FlatButton {

    private final String labelKey;
    private final IntConsumer onRelease;
    private int min, max, value;
    private boolean dragging;

    FlatSlider(int id, int x, int y, int w, int h, String labelKey, int min, int max, int value,
        IntConsumer onRelease) {
        super(id, x, y, w, h, "");
        this.labelKey = labelKey;
        this.onRelease = onRelease;
        setRange(min, max);
        setValue(value);
    }

    void setRange(int newMin, int newMax) {
        min = newMin;
        max = Math.max(newMin, newMax);
        value = clamp(value);
        updateLabel();
    }

    /** Valor vindo do servidor. Ignorado enquanto o jogador arrasta. */
    void setValue(int v) {
        if (dragging) return;
        value = clamp(v);
        updateLabel();
    }

    int value() {
        return value;
    }

    boolean isDragging() {
        return dragging;
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!super.mousePressed(mc, mouseX, mouseY)) return false;
        dragging = true;
        updateFromMouse(mouseX);
        return true;
    }

    @Override
    protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;
        if (dragging) {
            if (!enabled) dragging = false;
            else updateFromMouse(mouseX);
        }
        // Barra de progresso discreta atrás do texto, com uma alça fina na posição atual.
        int track = width - 2;
        int fill = xPosition + 1 + (max == min ? 0 : Math.round((value - min) * (float) track / (max - min)));
        drawRect(xPosition + 1, yPosition + 1, fill, yPosition + height - 1, enabled ? 0x553A7BD5 : 0x33505050);
        drawRect(
            Math.max(xPosition + 1, fill - 1),
            yPosition + 1,
            Math.min(xPosition + width - 1, fill + 1),
            yPosition + height - 1,
            enabled ? 0xFF8FB8FF : 0xFF606060);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        if (!dragging) return;
        dragging = false;
        if (enabled) onRelease.accept(value);
    }

    private void updateFromMouse(int mouseX) {
        float frac = (mouseX - (xPosition + 1)) / (float) Math.max(1, width - 2);
        frac = Math.max(0f, Math.min(1f, frac));
        value = clamp(min + Math.round(frac * (max - min)));
        updateLabel();
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    private void updateLabel() {
        displayString = I18n.format(labelKey, value);
    }
}
