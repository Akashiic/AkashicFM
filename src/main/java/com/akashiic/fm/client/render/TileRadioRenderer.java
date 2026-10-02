package com.akashiic.fm.client.render;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.akashiic.fm.audio.dsp.SpectrumAnalyzer;
import com.akashiic.fm.client.NowPlaying;
import com.akashiic.fm.client.audio.AudioEngine;
import com.akashiic.fm.client.audio.RadioAudioController;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.content.Facing;
import com.akashiic.fm.content.TileRadio;

/**
 * Tela da rádio, na face da frente, com brilho próprio (não escurece à noite): o texto (escolhido ou o título
 * atual) e, enquanto toca para este jogador, barras de espectro discretas atrás dele. Texto maior que a tela rola
 * como letreiro, caractere a caractere, então nunca é preciso recortar com scissor no mundo.
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
    /** Altura da tela em pixels de fonte (de 4/16 a 8/16 do bloco). */
    private static final float SCREEN_HEIGHT_PX = (4f / 16f) / SCALE;
    private static final int BAR_ALPHA = 0x60;
    private final float[] bands = new float[SpectrumAnalyzer.BANDS];

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partialTicks) {
        if (!(te instanceof TileRadio)) return;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        if (cx * cx + cy * cy + cz * cz > MAX_DISTANCE_SQ) return;
        TileRadio radio = (TileRadio) te;
        RadioState s = radio.state;
        String text = screenText(radio);
        boolean bars = FmConfig.Client.radioVisualizer && s.playing
            && AudioEngine.INSTANCE.visuals(RadioAudioController.playbackKey(radio), bands) >= 0;
        if (text.isEmpty() && !bars) return;
        FontRenderer font = func_147498_b();
        if (font == null) return;

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
        if (bars) drawBars(color);
        if (!text.isEmpty()) {
            String visible = marquee(font, text, System.nanoTime());
            font.drawString(visible, -font.getStringWidth(visible) / 2, -4, color);
        }
        GL11.glDepthMask(true);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevX, prevY);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopMatrix();
    }

    /**
     * O que a tela mostra: o texto escolhido; sem texto, enquanto toca, o título atual ou a estação (sintonizada,
     * com a frequência na frente: "98.7 FM ♪ título", ou "98.7 FM · sem sinal").
     */
    static String screenText(TileRadio radio) {
        RadioState s = radio.state;
        if (!s.screenText.isEmpty()) return s.screenText;
        if (!s.playing) return "";
        String dial = NowPlaying.dial(s);
        if (dial.isEmpty()) return "\u266A " + NowPlaying.label(radio);
        if (s.tunedUrl.isEmpty()) return dial + " · " + StatCollector.translateToLocal("akashicfm.screen.no_signal");
        return dial + " \u266A " + NowPlaying.label(radio);
    }

    /**
     * Uma barra por banda, de baixo para cima, na cor da tela e translúcida (o texto fica legível por cima). Em
     * pixels de fonte: a tela vai de -largura/2 a +largura/2 e de -altura/2 a +altura/2 (y para baixo).
     */
    private void drawBars(int rgb) {
        float half = SCREEN_WIDTH_PX / 2f, bottom = SCREEN_HEIGHT_PX / 2f - 1f, maxH = SCREEN_HEIGHT_PX - 2f;
        float gap = 1f, w = (SCREEN_WIDTH_PX - gap * (bands.length - 1)) / bands.length;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_I(rgb, BAR_ALPHA);
        for (int b = 0; b < bands.length; b++) {
            float h = Math.max(0.5f, bands[b] * maxH);
            float x0 = -half + b * (w + gap), x1 = x0 + w;
            // Mesma ordem dos quads do FontRenderer (a escala com y negativo inverte o sentido; o culling não come).
            t.addVertex(x0, bottom - h, 0);
            t.addVertex(x0, bottom, 0);
            t.addVertex(x1, bottom, 0);
            t.addVertex(x1, bottom - h, 0);
        }
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
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
