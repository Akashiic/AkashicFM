package com.akashiic.fm.client.render;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;

import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.content.Facing;
import com.akashiic.fm.content.TileRadio;

/**
 * Texto da tela da rádio, na face da frente, com brilho próprio (não escurece à noite). Texto maior que a
 * tela rola como letreiro, caractere a caractere, então nunca é preciso recortar com scissor no mundo.
 */
public final class TileRadioRenderer extends TileEntitySpecialRenderer {

    /** Além disso o texto é ilegível: não desenha. */
    private static final double MAX_DISTANCE_SQ = 24 * 24;
    /** Mesma escala do texto das placas. */
    private static final float SCALE = 1f / 90f;
    /** Largura da tela na textura (de 2/16 a 14/16) em pixels de fonte. */
    private static final int SCREEN_WIDTH_PX = (int) ((12f / 16f) / SCALE);
    /** Centro vertical da tela, acima do centro do bloco (a tela fica entre 4/16 e 8/16 a partir do topo). */
    private static final float SCREEN_CENTER_Y = 0.5f - 6f / 16f;
    private static final long MARQUEE_STEP_NANOS = 250_000_000L;
    private static final String MARQUEE_GAP = "   ";

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partialTicks) {
        if (!(te instanceof TileRadio)) return;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        if (cx * cx + cy * cy + cz * cz > MAX_DISTANCE_SQ) return;
        RadioState s = ((TileRadio) te).state;
        String text = screenText(s);
        if (text.isEmpty()) return;
        FontRenderer font = func_147498_b();
        if (font == null) return;

        String visible = marquee(font, text, System.nanoTime());
        int color = s.playing ? s.screenColor & 0xFFFFFF : dim(s.screenColor);

        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, cz);
        GL11.glRotatef(rotationFor(Facing.sanitize(te.getBlockMetadata())), 0f, 1f, 0f);
        GL11.glTranslatef(0f, SCREEN_CENTER_Y, 0.5f + 0.004f);
        GL11.glScalef(SCALE, -SCALE, SCALE);
        GL11.glNormal3f(0f, 0f, -1f);

        float prevX = OpenGlHelper.lastBrightnessX, prevY = OpenGlHelper.lastBrightnessY;
        GL11.glDisable(GL11.GL_LIGHTING);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
        GL11.glDepthMask(false);
        font.drawString(visible, -font.getStringWidth(visible) / 2, -4, color);
        GL11.glDepthMask(true);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevX, prevY);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopMatrix();
    }

    /** O que a tela mostra: o texto escolhido; sem texto, o título atual ou o host da estação enquanto toca. */
    static String screenText(RadioState s) {
        if (!s.screenText.isEmpty()) return s.screenText;
        if (!s.playing) return "";
        if (!s.nowPlaying.isEmpty()) return "♪ " + s.nowPlaying;
        return "♪ " + hostOf(s.url);
    }

    static String hostOf(String url) {
        int start = url.indexOf("://");
        start = start < 0 ? 0 : start + 3;
        int end = start;
        while (end < url.length()) {
            char c = url.charAt(end);
            if (c == '/' || c == ':' || c == '?' || c == '#') break;
            end++;
        }
        String host = url.substring(start, end);
        int at = host.lastIndexOf('@');
        return at >= 0 ? host.substring(at + 1) : host;
    }

    private static String marquee(FontRenderer font, String text, long nanos) {
        if (font.getStringWidth(text) <= SCREEN_WIDTH_PX) return text;
        String loop = text + MARQUEE_GAP;
        int offset = (int) ((nanos / MARQUEE_STEP_NANOS) % loop.length());
        if (offset < 0) offset += loop.length(); // nanoTime pode ser negativo
        return font.trimStringToWidth(loop.substring(offset) + loop, SCREEN_WIDTH_PX);
    }

    /** Rotação em Y que leva a face +Z do modelo para a face da frente. */
    static float rotationFor(int facing) {
        switch (facing) {
            case Facing.NORTH:
                return 180f;
            case Facing.EAST:
                return 90f;
            case Facing.WEST:
                return -90f;
            default:
                return 0f; // SOUTH
        }
    }

    private static int dim(int rgb) {
        int r = (rgb >> 16 & 0xFF) / 3, g = (rgb >> 8 & 0xFF) / 3, b = (rgb & 0xFF) / 3;
        return r << 16 | g << 8 | b;
    }
}
