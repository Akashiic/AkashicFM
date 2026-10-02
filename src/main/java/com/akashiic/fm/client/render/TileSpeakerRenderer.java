package com.akashiic.fm.client.render;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.akashiic.fm.audio.dsp.SpectrumAnalyzer;
import com.akashiic.fm.client.audio.AudioEngine;
import com.akashiic.fm.client.audio.RadioAudioController;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.content.Facing;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;

/**
 * Cone da caixa pulsando com os graves da música que ela toca. Desenha por cima da face da frente o mesmo
 * disco do cone da textura, um pouco maior e mais para fora conforme o grave; em repouso coincide com a face.
 * O disco (raio 6,6 px) fica dentro da moldura mesmo no máximo, e a luz é a do bloco em frente com o
 * sombreamento do vanilla para aquela face, como a face de verdade.
 */
public final class TileSpeakerRenderer extends TileEntitySpecialRenderer {

    private static final double MAX_DISTANCE_SQ = 24 * 24;
    private static final int SEGMENTS = 24;
    /** Raio do cone na textura (px) e centro (px, em coordenadas 0..16). */
    private static final double CONE_RADIUS_PX = 6.6, CENTER_PX = 8.0;
    private static final double MAX_SCALE = 0.07, MAX_PUSH = 0.012;

    private final float[] bands = new float[SpectrumAnalyzer.BANDS];

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partialTicks) {
        if (!(te instanceof TileSpeaker) || !FmConfig.Client.radioVisualizer) return;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        if (cx * cx + cy * cy + cz * cz > MAX_DISTANCE_SQ) return;
        TileSpeaker sp = (TileSpeaker) te;
        float pulse = pulse(sp);
        if (pulse < 0.01f) return;

        World world = te.getWorldObj();
        int facing = Facing.sanitize(te.getBlockMetadata());
        Block block = te.getBlockType();
        if (block == null) return;
        IIcon icon = block.getIcon(facing, facing);
        if (icon == null) return;

        int fx = te.xCoord, fz = te.zCoord;
        float shade;
        switch (facing) {
            case Facing.NORTH:
                fz--;
                shade = 0.8f;
                break;
            case Facing.SOUTH:
                fz++;
                shade = 0.8f;
                break;
            case Facing.WEST:
                fx--;
                shade = 0.6f;
                break;
            default:
                fx++;
                shade = 0.6f;
                break;
        }
        int light = world.getLightBrightnessForSkyBlocks(fx, te.yCoord, fz, 0);

        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, cz);
        GL11.glRotatef(TileRadioRenderer.rotationFor(facing), 0f, 1f, 0f);
        GL11.glTranslated(0, 0, 0.5 + 0.002 + MAX_PUSH * pulse);
        float prevX = OpenGlHelper.lastBrightnessX, prevY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light & 0xFFFF, light >>> 16);
        GL11.glDisable(GL11.GL_LIGHTING);
        bindTexture(TextureMap.locationBlocksTexture);
        GL11.glColor4f(shade, shade, shade, 1f);

        double scale = 1 + MAX_SCALE * pulse;
        double r = CONE_RADIUS_PX / 16.0 * scale;
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLES);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / SEGMENTS, a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
            // Centro, depois a borda em sentido anti-horário visto de frente (+Z): face voltada para quem olha.
            vertex(t, icon, 0, 0, 0, 0);
            vertex(t, icon, Math.cos(a0) * r, Math.sin(a0) * r, Math.cos(a0), Math.sin(a0));
            vertex(t, icon, Math.cos(a1) * r, Math.sin(a1) * r, Math.cos(a1), Math.sin(a1));
        }
        t.draw();

        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevX, prevY);
        GL11.glPopMatrix();
    }

    /**
     * Vértice do disco: posição em blocos (x para a direita de quem olha, y para cima) e o mesmo ponto do cone na
     * textura (unidade: raio do cone), com v crescendo para baixo como na face do vanilla.
     */
    private static void vertex(Tessellator t, IIcon icon, double px, double py, double ux, double uy) {
        double u = CENTER_PX + ux * CONE_RADIUS_PX, v = CENTER_PX - uy * CONE_RADIUS_PX;
        t.addVertexWithUV(px, py, 0, icon.getInterpolatedU(u), icon.getInterpolatedV(v));
    }

    /** 0..1: quanto o cone se mexe agora, pelos graves (3 primeiras bandas) da reprodução da rádio ligada. */
    private float pulse(TileSpeaker sp) {
        Pos link = sp.linkedRadio;
        if (link == null) return 0f;
        World world = sp.getWorldObj();
        if (world == null || !world.blockExists(link.x, link.y, link.z)) return 0f;
        TileEntity rt = world.getTileEntity(link.x, link.y, link.z);
        if (!(rt instanceof TileRadio)) return 0f;
        if (AudioEngine.INSTANCE.visuals(RadioAudioController.playbackKey((TileRadio) rt), bands) < 0) return 0f;
        float bass = Math.max(bands[0], Math.max(bands[1], bands[2]));
        float p = (bass - 0.45f) / 0.55f;
        return p <= 0 ? 0f : Math.min(1f, p);
    }
}
