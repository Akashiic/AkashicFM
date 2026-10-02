package com.akashiic.fm.client.audio;

import com.akashiic.fm.common.SpeakerChannel;

/**
 * Uma fonte de som desejada para uma reprodução: posição no mundo, qual canal toca (MIX, LEFT ou RIGHT;
 * STEREO já chega expandido em duas specs) e o ganho-alvo calculado pelo controlador.
 */
public final class EmitterSpec {

    public final double x, y, z;
    public final SpeakerChannel channel;
    public final float gain;

    public EmitterSpec(double x, double y, double z, SpeakerChannel channel, float gain) {
        if (channel == SpeakerChannel.STEREO) throw new IllegalArgumentException("STEREO deve ser expandido");
        this.x = x;
        this.y = y;
        this.z = z;
        this.channel = channel;
        this.gain = gain;
    }

    /** Mesma fonte física (posição e canal), ignorando o ganho. Mudou isso, as fontes AL são recriadas. */
    public boolean sameVoice(EmitterSpec o) {
        return o != null && channel == o.channel
            && Math.abs(x - o.x) < 1e-3
            && Math.abs(y - o.y) < 1e-3
            && Math.abs(z - o.z) < 1e-3;
    }
}
