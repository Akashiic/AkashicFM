package com.akashiic.fm.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;

/** Botão chapado desenhado com retângulos: funciona em qualquer altura (o do vanilla só fica certo com 20 px). */
class FlatButton extends GuiButton {

    static final int BG = 0xFF2A2F36;
    static final int BG_HOVER = 0xFF3A4452;
    static final int BG_DISABLED = 0xFF1C1F24;
    static final int BORDER = 0xFF4A5260;
    static final int TEXT = 0xFFE0E0E0;
    static final int TEXT_HOVER = 0xFFFFFFA0;
    static final int TEXT_DISABLED = 0xFF707070;

    /** Cor de texto fixa (0 = usa as cores padrão). */
    int textColor;
    /** Cor de uma amostra desenhada à esquerda do texto (-1 = sem amostra). */
    int swatch = -1;
    /** Texto alinhado à esquerda (linhas de lista). */
    boolean alignLeft;

    FlatButton(int id, int x, int y, int w, int h, String text) {
        super(id, x, y, w, h, text);
    }

    boolean isHovered(int mouseX, int mouseY) {
        return visible && mouseX >= xPosition
            && mouseY >= yPosition
            && mouseX < xPosition + width
            && mouseY < yPosition + height;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;
        boolean hover = enabled && isHovered(mouseX, mouseY);
        drawRect(xPosition, yPosition, xPosition + width, yPosition + height, BORDER);
        drawRect(
            xPosition + 1,
            yPosition + 1,
            xPosition + width - 1,
            yPosition + height - 1,
            !enabled ? BG_DISABLED : hover ? BG_HOVER : BG);
        mouseDragged(mc, mouseX, mouseY);
        FontRenderer font = mc.fontRenderer;
        int color = !enabled ? TEXT_DISABLED : textColor != 0 ? textColor : hover ? TEXT_HOVER : TEXT;
        int textY = yPosition + (height - 8) / 2;
        int left = xPosition + 4;
        if (swatch >= 0) {
            drawRect(left, yPosition + 3, left + height - 6, yPosition + height - 3, 0xFF000000 | swatch);
            left += height - 3;
        }
        String s = font.trimStringToWidth(displayString, xPosition + width - 4 - left);
        if (alignLeft || swatch >= 0) {
            font.drawStringWithShadow(s, left, textY, color);
        } else {
            font.drawStringWithShadow(s, xPosition + (width - font.getStringWidth(s)) / 2, textY, color);
        }
    }
}
