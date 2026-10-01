package com.akashiic.fm.client.audio;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EFX10;

import com.akashiic.fm.AkashiFM;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Spike 0a: descobre o que o OpenAL do cliente suporta (fonte posicional, EFX low-pass e reverb,
 * fontes livres) dentro do contexto de áudio do próprio Minecraft. Roda uma vez, só com a variável
 * de ambiente AKASHIFM_PROBE_AUDIO=1, e escreve o resultado no log com o prefixo "[Spike 0a]".
 */
public final class AlCapabilityProbe {

    private static final int MAX_SOURCES_TO_COUNT = 256;
    private int ticks;
    private boolean done;

    public static void registerIfRequested() {
        if ("1".equals(System.getenv("AKASHIFM_PROBE_AUDIO"))) {
            FMLCommonHandler.instance()
                .bus()
                .register(new AlCapabilityProbe());
            AkashiFM.LOG.info("[Spike 0a] sonda de OpenAL agendada");
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        // o SoundManager do 1.7.10 sobe numa thread própria: espera o contexto AL existir
        if (++ticks < 100 || !AL.isCreated()) return;
        done = true;
        try {
            run();
        } catch (Throwable t) {
            AkashiFM.LOG.error("[Spike 0a] a sonda falhou", t);
        }
    }

    private static void run() {
        log("java=%s, lwjgl3ify=%s", System.getProperty("java.version"), classExists("org.lwjglx.openal.AL"));
        log(
            "AL_VERSION=%s | AL_RENDERER=%s | AL_VENDOR=%s",
            AL10.alGetString(AL10.AL_VERSION),
            AL10.alGetString(AL10.AL_RENDERER),
            AL10.alGetString(AL10.AL_VENDOR));
        log("dispositivo=%s", ALC10.alcGetString(AL.getDevice(), ALC10.ALC_DEVICE_SPECIFIER));
        AL10.alGetError();

        int source = AL10.alGenSources();
        check("alGenSources");
        AL10.alSource3f(source, AL10.AL_POSITION, 100.5f, 64.5f, -200.5f);
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f);
        AL10.alSourcef(source, AL10.AL_GAIN, 0f); // mudo: é só um teste
        FloatBuffer pos = BufferUtils.createFloatBuffer(3);
        AL10.alGetSource(source, AL10.AL_POSITION, pos);
        log(
            "fonte posicional: AL_POSITION lido de volta = (%.1f, %.1f, %.1f) %s",
            pos.get(0),
            pos.get(1),
            pos.get(2),
            check("AL_POSITION") ? "OK" : "ERRO");

        int buffer = AL10.alGenBuffers();
        ShortBuffer pcm = BufferUtils.createShortBuffer(4800);
        for (int i = 0; i < 4800; i++) pcm.put((short) (8000 * Math.sin(2 * Math.PI * 440 * i / 48000.0)));
        pcm.flip();
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcm, 48000);
        AL10.alSourceQueueBuffers(source, buffer);
        AL10.alSourcePlay(source);
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        log(
            "streaming mono 48 kHz em fila: estado=%s %s",
            state == AL10.AL_PLAYING ? "AL_PLAYING" : state,
            check("queue/play") ? "OK" : "ERRO");

        probeEfx(source);

        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        AL10.alDeleteSources(source);
        AL10.alDeleteBuffers(buffer);
        check("cleanup");

        log("fontes livres agora (limite da contagem %d): %d", MAX_SOURCES_TO_COUNT, countFreeSources());
        log("concluído");
    }

    private static void probeEfx(int source) {
        boolean ext;
        try {
            ext = ALC10.alcIsExtensionPresent(AL.getDevice(), "ALC_EXT_EFX");
        } catch (Throwable t) {
            log("ALC_EXT_EFX: não deu para consultar (%s)", t);
            return;
        }
        log("ALC_EXT_EFX presente: %s", ext);
        if (!ext) return;
        try {
            IntBuffer sends = BufferUtils.createIntBuffer(1);
            ALC10.alcGetInteger(AL.getDevice(), EFX10.ALC_MAX_AUXILIARY_SENDS, sends);
            log("ALC_MAX_AUXILIARY_SENDS=%d", sends.get(0));

            int filter = EFX10.alGenFilters();
            EFX10.alFilteri(filter, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);
            EFX10.alFilterf(filter, EFX10.AL_LOWPASS_GAIN, 0.7f);
            EFX10.alFilterf(filter, EFX10.AL_LOWPASS_GAINHF, 0.2f);
            AL10.alSourcei(source, EFX10.AL_DIRECT_FILTER, filter);
            log("low-pass (oclusão) na fonte: %s", check("lowpass") ? "OK" : "ERRO");

            int effect = EFX10.alGenEffects();
            EFX10.alEffecti(effect, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_REVERB);
            EFX10.alEffectf(effect, EFX10.AL_REVERB_DECAY_TIME, 2.5f);
            int slot = EFX10.alGenAuxiliaryEffectSlots();
            EFX10.alAuxiliaryEffectSloti(slot, EFX10.AL_EFFECTSLOT_EFFECT, effect);
            AL11.alSource3i(source, EFX10.AL_AUXILIARY_SEND_FILTER, slot, 0, EFX10.AL_FILTER_NULL);
            log("reverb em aux slot + send da fonte: %s", check("reverb") ? "OK" : "ERRO");

            AL10.alSourcei(source, EFX10.AL_DIRECT_FILTER, EFX10.AL_FILTER_NULL);
            AL11.alSource3i(source, EFX10.AL_AUXILIARY_SEND_FILTER, EFX10.AL_EFFECTSLOT_NULL, 0, EFX10.AL_FILTER_NULL);
            EFX10.alDeleteAuxiliaryEffectSlots(slot);
            EFX10.alDeleteEffects(effect);
            EFX10.alDeleteFilters(filter);
            check("efx cleanup");
        } catch (Throwable t) {
            log("EFX10 indisponível neste ambiente: %s", t);
        }
    }

    /** Aloca fontes até o OpenAL recusar e solta todas em seguida. */
    private static int countFreeSources() {
        List<Integer> got = new ArrayList<>();
        AL10.alGetError();
        for (int i = 0; i < MAX_SOURCES_TO_COUNT; i++) {
            int s = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) break;
            got.add(s);
        }
        for (int s : got) AL10.alDeleteSources(s);
        AL10.alGetError();
        return got.size();
    }

    private static boolean check(String what) {
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR) {
            log("erro AL em %s: 0x%X", what, err);
            return false;
        }
        return true;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, AlCapabilityProbe.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String fmt, Object... args) {
        AkashiFM.LOG.info("[Spike 0a] " + String.format(fmt, args));
    }
}
