package com.akashiic.fm.client.render;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;

import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.content.Facing;
import com.akashiic.fm.content.TileTransmitter;

/**
 * Visor do transmissor, na face da frente e com brilho próprio: a frequência ("98.7 FM"), âmbar no ar e apagada
 * fora dele. Mesma geometria da tela da rádio (a textura da frente tem o visor no mesmo lugar).
 */
public final class TileTransmitterRenderer extends TileEntitySpecialRenderer {

    private static final double MAX_DISTANCE_SQ = 24 * 24;
    private static final float SCALE = 1f / 90f;
    private static final float SCREEN_CENTER_Y = 0.5f - 6f / 16f;
    private static final int ON_AIR = 0xFFB000, OFF_AIR = 0x553A00;

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partialTicks) {
        if (!(te instanceof TileTransmitter)) return;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        if (cx * cx + cy * cy + cz * cz > MAX_DISTANCE_SQ) return;
        FontRenderer font = func_147498_b();
        if (font == null) return;
        TransmitterState s = ((TileTransmitter) te).state;
        String text = Frequency.format(s.frequency) + " FM";
        int color = s.active() ? ON_AIR : OFF_AIR;

        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, cz);
        GL11.glRotatef(TileRadioRenderer.rotationFor(Facing.sanitize(te.getBlockMetadata())), 0f, 1f, 0f);
        GL11.glTranslatef(0f, SCREEN_CENTER_Y, 0.5f + 0.004f);
        GL11.glScalef(SCALE, -SCALE, SCALE);
        GL11.glNormal3f(0f, 0f, -1f);
        float prevX = OpenGlHelper.lastBrightnessX, prevY = OpenGlHelper.lastBrightnessY;
        GL11.glDisable(GL11.GL_LIGHTING);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
        GL11.glDepthMask(false);
        font.drawString(text, -font.getStringWidth(text) / 2, -4, color);
        GL11.glDepthMask(true);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevX, prevY);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopMatrix();
    }
}
