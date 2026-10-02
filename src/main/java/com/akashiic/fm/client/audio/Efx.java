package com.akashiic.fm.client.audio;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCdevice;
import org.lwjgl.openal.EFX10;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.audio.spatial.RoomModel;

/**
 * Objetos EFX do mod num contexto OpenAL: um filtro low-pass de rascunho, um efeito de reverb e um slot
 * auxiliar próprio. Só a {@link AudioEngine} cria e solta, sob o lock dela e com o contexto válido.
 * <p>
 * O EFX <b>copia</b> os parâmetros quando um filtro é ligado a uma fonte (e quando um efeito é carregado num
 * slot): mudar o filtro depois não afeta as fontes que já o receberam. Por isso um filtro só serve todas as
 * vozes: ajusta os ganhos e liga na fonte, voz a voz.
 * <p>
 * O reverb entra pela saída auxiliar 1 das fontes do mod (a 0 é a que o Hodgepodge usa nas fontes dele). Sem
 * sala (campo aberto ou reverb desligado), o efeito é descarregado do slot e o OpenAL não processa nada.
 */
final class Efx {

    /** Objetos EFX do mod vivos agora (diagnóstico de vazamento): 3 com reverb, 1 só com o filtro, 0 sem EFX. */
    static final AtomicInteger LIVE_OBJECTS = new AtomicInteger();
    static final int PREFERRED_SEND = 1;
    /** Ganho interno do reverb (o nível de cada sala vai no ganho do slot). */
    private static final float REVERB_GAIN = 0.5f;
    /** Histerese para carregar/descarregar o efeito conforme o nível da sala. */
    private static final float LOAD_WET = 0.002f, UNLOAD_WET = 0.001f;
    private static int nextId = 1;
    private static boolean failureLogged;

    /** Identidade desta instância: a voz sabe se os filtros dela foram aplicados por esta. */
    final int id;
    private int filter;
    private int effect;
    private int slot;
    final int sendIndex;
    final int maxSends;
    private RoomModel.Params pushed;
    private boolean effectLoaded;
    private float slotGain = -1f;

    private Efx(int filter, int effect, int slot, int sendIndex, int maxSends) {
        this.id = nextId++;
        this.filter = filter;
        this.effect = effect;
        this.slot = slot;
        this.sendIndex = sendIndex;
        this.maxSends = maxSends;
    }

    /** Cria os objetos no contexto atual; null se não há EFX ou algo falhou (o mod fica só no ganho). */
    static Efx create() {
        int filter = 0, effect = 0, slot = 0;
        try {
            ALCdevice device = AL.getDevice();
            if (device == null || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) return null;
            IntBuffer buf = BufferUtils.createIntBuffer(16);
            ALC10.alcGetInteger(device, EFX10.ALC_MAX_AUXILIARY_SENDS, buf);
            int sends = Math.max(0, buf.get(0));
            AL10.alGetError();

            filter = EFX10.alGenFilters();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                filter = 0;
                return fail("alGenFilters", 0, 0, 0);
            }
            LIVE_OBJECTS.incrementAndGet();
            EFX10.alFilteri(filter, EFX10.AL_FILTER_TYPE, EFX10.AL_FILTER_LOWPASS);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) return fail("AL_FILTER_LOWPASS", filter, 0, 0);

            // Reverb é opcional: sem saídas auxiliares, ou se falhar, fica só a oclusão.
            if (sends >= 1) {
                effect = EFX10.alGenEffects();
                if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                    LIVE_OBJECTS.incrementAndGet();
                    EFX10.alEffecti(effect, EFX10.AL_EFFECT_TYPE, EFX10.AL_EFFECT_REVERB);
                    EFX10.alEffectf(effect, EFX10.AL_REVERB_GAIN, REVERB_GAIN);
                    if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                        slot = EFX10.alGenAuxiliaryEffectSlots();
                        if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                            LIVE_OBJECTS.incrementAndGet();
                            EFX10.alAuxiliaryEffectSlotf(slot, EFX10.AL_EFFECTSLOT_GAIN, 0f);
                        } else {
                            slot = 0;
                        }
                    }
                    if (slot == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                        safeDelete(0, effect, slot);
                        effect = 0;
                        slot = 0;
                    }
                } else {
                    effect = 0;
                }
            }
            int send = slot == 0 ? 0 : Math.min(PREFERRED_SEND, sends - 1);
            return new Efx(filter, effect, slot, send, sends);
        } catch (Throwable t) {
            // EFX10 sem as funções carregadas (driver/camada sem EFX): nunca pode derrubar o áudio.
            if (!failureLogged) {
                failureLogged = true;
                AkashicFM.LOG.warn("AkashicFM: EFX indisponível, oclusão só pelo ganho", t);
            }
            safeDelete(filter, effect, slot);
            return null;
        }
    }

    private static Efx fail(String what, int filter, int effect, int slot) {
        if (!failureLogged) {
            failureLogged = true;
            AkashicFM.LOG.warn("AkashicFM: EFX falhou em {}; oclusão só pelo ganho", what);
        }
        safeDelete(filter, effect, slot);
        return null;
    }

    /** Apaga o que existir; cada id conta uma vez no contador, mesmo se a chamada ao OpenAL falhar. */
    private static void safeDelete(int filter, int effect, int slot) {
        if (slot != 0) {
            try {
                EFX10.alDeleteAuxiliaryEffectSlots(slot);
            } catch (Throwable ignored) {
                // o objeto morre com o contexto de qualquer jeito
            }
            LIVE_OBJECTS.decrementAndGet();
        }
        if (effect != 0) {
            try {
                EFX10.alDeleteEffects(effect);
            } catch (Throwable ignored) {
                // idem
            }
            LIVE_OBJECTS.decrementAndGet();
        }
        if (filter != 0) {
            try {
                EFX10.alDeleteFilters(filter);
            } catch (Throwable ignored) {
                // idem
            }
            LIVE_OBJECTS.decrementAndGet();
        }
        try {
            AL10.alGetError();
        } catch (Throwable ignored) {
            // sem contexto
        }
    }

    boolean hasReverb() {
        return slot != 0;
    }

    /** Low-pass do caminho direto da fonte (cópia dos parâmetros). */
    void direct(int source, float gain, float gainHf) {
        EFX10.alFilterf(filter, EFX10.AL_LOWPASS_GAIN, gain);
        EFX10.alFilterf(filter, EFX10.AL_LOWPASS_GAINHF, gainHf);
        AL10.alSourcei(source, EFX10.AL_DIRECT_FILTER, filter);
    }

    /** Liga a fonte ao reverb, com o próprio low-pass do envio. */
    void send(int source, float gain, float gainHf) {
        if (slot == 0) return;
        EFX10.alFilterf(filter, EFX10.AL_LOWPASS_GAIN, gain);
        EFX10.alFilterf(filter, EFX10.AL_LOWPASS_GAINHF, gainHf);
        AL11.alSource3i(source, EFX10.AL_AUXILIARY_SEND_FILTER, slot, sendIndex, filter);
    }

    /** Tira filtro e envio da fonte (antes de soltar estes objetos com a fonte ainda viva). */
    void detach(int source) {
        AL10.alSourcei(source, EFX10.AL_DIRECT_FILTER, EFX10.AL_FILTER_NULL);
        if (slot != 0) AL11.alSource3i(
            source,
            EFX10.AL_AUXILIARY_SEND_FILTER,
            EFX10.AL_EFFECTSLOT_NULL,
            sendIndex,
            EFX10.AL_FILTER_NULL);
    }

    /** Aplica a sala ao reverb. Sem sala, ou desligado, descarrega o efeito (custo zero no mixer). */
    void room(RoomModel.Params p, boolean enabled) {
        if (slot == 0) return;
        boolean want = enabled && p.wet > (effectLoaded ? UNLOAD_WET : LOAD_WET);
        if (!want) {
            if (effectLoaded) {
                EFX10.alAuxiliaryEffectSloti(slot, EFX10.AL_EFFECTSLOT_EFFECT, EFX10.AL_EFFECT_NULL);
                effectLoaded = false;
            }
            setSlotGain(0f);
            return;
        }
        if (!effectLoaded || p.differsFrom(pushed)) {
            EFX10.alEffectf(effect, EFX10.AL_REVERB_DECAY_TIME, p.decayTime);
            EFX10.alEffectf(effect, EFX10.AL_REVERB_DECAY_HFRATIO, p.decayHfRatio);
            EFX10.alEffectf(effect, EFX10.AL_REVERB_REFLECTIONS_DELAY, p.reflectionsDelay);
            EFX10.alEffectf(effect, EFX10.AL_REVERB_LATE_REVERB_DELAY, p.lateReverbDelay);
            // O slot guarda uma cópia: recarregar aplica os parâmetros (mesmo tipo: a cauda não é cortada).
            EFX10.alAuxiliaryEffectSloti(slot, EFX10.AL_EFFECTSLOT_EFFECT, effect);
            effectLoaded = true;
            pushed = p;
        }
        if (Math.abs(p.wet - slotGain) > 0.005f) setSlotGain(p.wet);
    }

    private void setSlotGain(float g) {
        if (g == slotGain) return;
        EFX10.alAuxiliaryEffectSlotf(slot, EFX10.AL_EFFECTSLOT_GAIN, g);
        slotGain = g;
    }

    /** Diagnóstico: ganho do slot lido do OpenAL, decaimento do efeito e se ele está carregado. */
    float slotGainFromAl() {
        return slot == 0 ? Float.NaN : EFX10.alGetAuxiliaryEffectSlotf(slot, EFX10.AL_EFFECTSLOT_GAIN);
    }

    float decayFromAl() {
        return effect == 0 ? Float.NaN : EFX10.alGetEffectf(effect, EFX10.AL_REVERB_DECAY_TIME);
    }

    boolean effectLoaded() {
        return effectLoaded;
    }

    RoomModel.Params pushed() {
        return pushed;
    }

    /**
     * Solta os objetos. Com {@code contextAlive}=false só esquece os ids (o contexto já morreu). Com o contexto
     * vivo, nenhuma fonte pode estar ligada ao slot (o OpenAL Soft recusa apagar slot em uso): as vozes já foram
     * soltas ou desligadas antes.
     */
    void release(boolean contextAlive) {
        if (contextAlive) {
            safeDelete(filter, effect, slot);
        } else {
            if (filter != 0) LIVE_OBJECTS.decrementAndGet();
            if (effect != 0) LIVE_OBJECTS.decrementAndGet();
            if (slot != 0) LIVE_OBJECTS.decrementAndGet();
        }
        filter = effect = slot = 0;
        effectLoaded = false;
    }
}
