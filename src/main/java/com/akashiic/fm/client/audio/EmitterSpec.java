package com.akashiic.fm.client.audio;

import com.akashiic.fm.common.SpeakerChannel;

/**
 * Uma fonte de som desejada para uma reprodução: posição no mundo, qual canal toca (MIX, LEFT ou RIGHT;
 * STEREO já chega expandido em duas specs), o ganho-alvo calculado pelo controlador (volume e distância) e a
 * oclusão por blocos entre o ouvinte e a fonte (0 = livre, 1 = bloqueada; a voz transforma em ganho e low-pass).
 * <p>
 * {@code relative}: a posição é relativa a quem ouve (o rádio portátil de quem o carrega), não ao mundo.
 * {@code dry}: sem filtro e sem envio ao reverb (fone: o som vai direto ao ouvido).
 */
public final class EmitterSpec {

    public final double x, y, z;
    public final SpeakerChannel channel;
    public final float gain;
    public final float occlusion;
    public final boolean relative, dry;

    public EmitterSpec(double x, double y, double z, SpeakerChannel channel, float gain) {
        this(x, y, z, channel, gain, 0f);
    }

    public EmitterSpec(double x, double y, double z, SpeakerChannel channel, float gain, float occlusion) {
        this(x, y, z, channel, gain, occlusion, false, false);
    }

    private EmitterSpec(double x, double y, double z, SpeakerChannel channel, float gain, float occlusion,
        boolean relative, boolean dry) {
        if (channel == SpeakerChannel.STEREO) throw new IllegalArgumentException("STEREO deve ser expandido");
        this.relative = relative;
        this.dry = dry;
        this.x = x;
        this.y = y;
        this.z = z;
        this.channel = channel;
        this.gain = gain;
        this.occlusion = occlusion > 0 ? Math.min(1f, occlusion) : 0f; // NaN vira 0
    }

    /** Fonte presa a quem ouve (posição relativa ao ouvinte), sem oclusão. */
    public static EmitterSpec relative(double x, double y, double z, SpeakerChannel channel, float gain, boolean dry) {
        return new EmitterSpec(x, y, z, channel, gain, 0f, true, dry);
    }

    /** Mesma fonte com outra oclusão (fonte relativa não tem oclusão: fica como está). */
    public EmitterSpec withOcclusion(double occ) {
        if (relative) return this;
        return new EmitterSpec(x, y, z, channel, gain, (float) occ, false, dry);
    }

    /**
     * Mesma fonte física (posição, canal e tipo), ignorando ganho e oclusão. Mudou isso, as fontes AL são recriadas.
     */
    public boolean sameVoice(EmitterSpec o) {
        return o != null && channel == o.channel
            && relative == o.relative
            && dry == o.dry
            && Math.abs(x - o.x) < 1e-3
            && Math.abs(y - o.y) < 1e-3
            && Math.abs(z - o.z) < 1e-3;
    }
}
