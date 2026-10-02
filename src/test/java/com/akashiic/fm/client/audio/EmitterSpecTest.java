package com.akashiic.fm.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.SpeakerChannel;

class EmitterSpecTest {

    @Test
    void fonteRelativaNaoTemOclusao() {
        EmitterSpec r = EmitterSpec.relative(0, 0, 0, SpeakerChannel.MIX, 0.5f, false);
        assertTrue(r.relative);
        assertFalse(r.dry);
        assertSame(r, r.withOcclusion(0.9));
        assertEquals(0f, r.occlusion);
    }

    @Test
    void tipoDaFonteFazParteDaIdentidade() {
        EmitterSpec world = new EmitterSpec(0, 0, 0, SpeakerChannel.MIX, 0.5f);
        EmitterSpec rel = EmitterSpec.relative(0, 0, 0, SpeakerChannel.MIX, 0.5f, false);
        EmitterSpec dry = EmitterSpec.relative(0, 0, 0, SpeakerChannel.MIX, 0.5f, true);
        // Mesma posição e canal, mas fonte AL diferente (relativa, sem filtro): não podem ser reaproveitadas.
        assertFalse(world.sameVoice(rel));
        assertFalse(rel.sameVoice(dry));
        assertTrue(rel.sameVoice(EmitterSpec.relative(0, 0, 0, SpeakerChannel.MIX, 0.9f, false)));
        EmitterSpec occluded = world.withOcclusion(0.4);
        assertFalse(occluded.relative);
        assertEquals(0.4f, occluded.occlusion, 1e-6);
        assertTrue(world.sameVoice(occluded));
    }
}
