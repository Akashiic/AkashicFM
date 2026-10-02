package com.akashiic.fm.client.audio;

import com.akashiic.fm.common.SpeakerChannel;

/**
 * Uma fonte de som desejada para uma reprodução: posição no mundo, qual canal toca (MIX, LEFT ou RIGHT;
 * STEREO já chega expandido em duas specs), o ganho-alvo calculado pelo controlador (volume e distância) e a
 * oclusão por blocos entre o ouvinte e a fonte (0 = livre, 1 = bloqueada; a voz transforma em ganho e low-pass).
 */
public final class EmitterSpec {

    public final double x, y, z;
    public final SpeakerChannel channel;
    public final float gain;
    public final float occlusion;

    public EmitterSpec(double x, double y, double z, SpeakerChannel channel, float gain) {
        this(x, y, z, channel, gain, 0f);
    }

    public EmitterSpec(double x, double y, double z, SpeakerChannel channel, float gain, float occlusion) {
        if (channel == SpeakerChannel.STEREO) throw new IllegalArgumentException("STEREO deve ser expandido");
        this.x = x;
        this.y = y;
        this.z = z;
        this.channel = channel;
        this.gain = gain;
        this.occlusion = occlusion > 0 ? Math.min(1f, occlusion) : 0f; // NaN vira 0
    }

    /** Mesma fonte com outra oclusão. */
    public EmitterSpec withOcclusion(double occ) {
        return new EmitterSpec(x, y, z, channel, gain, (float) occ);
    }

    /** Mesma fonte física (posição e canal), ignorando ganho e oclusão. Mudou isso, as fontes AL são recriadas. */
    public boolean sameVoice(EmitterSpec o) {
        return o != null && channel == o.channel
            && Math.abs(x - o.x) < 1e-3
            && Math.abs(y - o.y) < 1e-3
            && Math.abs(z - o.z) < 1e-3;
    }
}
