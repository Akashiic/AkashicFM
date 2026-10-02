package com.akashiic.fm.client.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import com.akashiic.fm.audio.dsp.GainModel;
import com.akashiic.fm.audio.spatial.OcclusionTracer;
import com.akashiic.fm.common.SpeakerChannel;

/**
 * Uma fonte OpenAL com pool fixo de buffers. Nunca cria buffer novo durante a reprodução: os processados
 * são desenfileirados e reaproveitados, então a memória do OpenAL não cresce (o OpenFM vazava ~635 MB/h).
 * Só a thread principal usa, sempre com o contexto AL válido (garantido pela {@link AudioEngine}).
 */
final class Voice {

    static final int POOL_SIZE = 10;
    /**
     * Objetos AL do mod que existem agora (criados e ainda não apagados nem perdidos com o contexto). Diagnóstico:
     * com as reproduções estáveis, buffers = vozes × {@link #POOL_SIZE} e fontes = vozes, sempre.
     */
    static final AtomicInteger LIVE_SOURCES = new AtomicInteger(), LIVE_BUFFERS = new AtomicInteger();
    private static final double GAIN_TAU_SECONDS = 0.08;
    /** Porta abrindo ou fonte saindo de trás da quina: o abafado muda em ~0,1 s, sem clique. */
    private static final double OCCLUSION_TAU_SECONDS = 0.1;
    /** Variação da oclusão suavizada a partir da qual os filtros são reenviados ao OpenAL. */
    private static final float OCCLUSION_EPSILON = 0.004f;

    final SpeakerChannel channel;
    private int source;
    private final int[] buffers = new int[POOL_SIZE];
    private final ArrayDeque<Integer> free = new ArrayDeque<>(POOL_SIZE);
    /** Número de sequência do bloco em cada buffer da fila, na mesma ordem da fila do OpenAL. */
    private final ArrayDeque<Long> queuedSeqs = new ArrayDeque<>(POOL_SIZE);
    private final IntBuffer tmp = BufferUtils.createIntBuffer(1);
    private int queued;

    final double x, y, z;
    /** Posição relativa a quem ouve (portátil de quem carrega) e sem filtro nem reverb (fone). */
    final boolean relative, dry;
    private boolean positionDirty = true;
    /** Ganho do volume e da distância (sem a oclusão). */
    float targetGain;
    private float gain;
    float targetOcclusion;
    private float occlusion;
    private boolean occlusionInit;
    /** Instância EFX cujos filtros estão aplicados nesta fonte (0 = nenhuma) e a oclusão que eles representam. */
    private int efxId;
    private float appliedOcclusion = -1f;

    Voice(EmitterSpec spec) {
        this.channel = spec.channel;
        this.x = spec.x;
        this.y = spec.y;
        this.z = spec.z;
        this.relative = spec.relative;
        this.dry = spec.dry;
        this.targetGain = spec.gain;
        this.targetOcclusion = spec.occlusion;
    }

    /** Ganho e oclusão desejados neste tick. */
    void setTargets(EmitterSpec spec) {
        targetGain = spec.gain;
        targetOcclusion = spec.occlusion;
    }

    /** Cria a fonte e os buffers. Em falha (ex.: acabaram as fontes do OpenAL) libera o que criou e devolve false. */
    boolean create() {
        AL10.alGetError();
        source = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            source = 0;
            return false;
        }
        LIVE_SOURCES.incrementAndGet();
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        // Atenuação é do GainModel: o OpenAL só posiciona (pan/HRTF).
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 1f);
        AL10.alSourcef(source, AL10.AL_PITCH, 1f);
        AL10.alSourcef(source, AL10.AL_GAIN, 0f);
        for (int i = 0; i < POOL_SIZE; i++) {
            buffers[i] = AL10.alGenBuffers();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                buffers[i] = 0;
                release(true);
                return false;
            }
            LIVE_BUFFERS.incrementAndGet();
            free.add(buffers[i]);
        }
        gain = 0f;
        positionDirty = true;
        efxId = 0;
        appliedOcclusion = -1f;
        return true;
    }

    /** Libera fonte e buffers. Com {@code contextAlive}=false só esquece os ids (o contexto já morreu). */
    void release(boolean contextAlive) {
        if (source != 0) {
            if (contextAlive) {
                AL10.alSourceStop(source);
                AL10.alSourcei(source, AL10.AL_BUFFER, 0); // solta toda a fila
                AL10.alDeleteSources(source);
            }
            LIVE_SOURCES.decrementAndGet(); // apagada agora ou junto com o contexto que morreu
        }
        for (int i = 0; i < POOL_SIZE; i++) {
            if (buffers[i] != 0) {
                if (contextAlive) AL10.alDeleteBuffers(buffers[i]);
                LIVE_BUFFERS.decrementAndGet();
            }
            buffers[i] = 0;
        }
        if (contextAlive) AL10.alGetError();
        source = 0;
        free.clear();
        queuedSeqs.clear();
        queued = 0;
        efxId = 0;
    }

    boolean isCreated() {
        return source != 0;
    }

    int source() {
        return source;
    }

    int queued() {
        return queued;
    }

    /** Desenfileira tudo que já tocou e devolve ao pool. */
    void reclaimProcessed() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            tmp.clear();
            AL10.alSourceUnqueueBuffers(source, tmp);
            free.add(tmp.get(0));
            queuedSeqs.pollFirst();
            queued--;
        }
        if (queued < 0) queued = 0;
    }

    /** Fonte parada: solta a fila inteira e devolve todos os buffers ao pool. */
    void resetQueue() {
        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        free.clear();
        for (int b : buffers) if (b != 0) free.add(b);
        queuedSeqs.clear();
        queued = 0;
    }

    /** Enfileira o bloco {@code seq} (mono 16-bit). Devolve false se o pool está vazio (não deveria acontecer). */
    boolean queue(ShortBuffer mono, int sampleRate, long seq) {
        Integer id = free.poll();
        if (id == null) return false;
        AL10.alBufferData(id, AL10.AL_FORMAT_MONO16, mono, sampleRate);
        AL10.alSourceQueueBuffers(source, id);
        queuedSeqs.addLast(seq);
        queued++;
        return true;
    }

    /** Sequência do bloco que está tocando agora (o primeiro da fila), ou -1 com a fila vazia. */
    long headSeq() {
        Long s = queuedSeqs.peekFirst();
        return s == null ? -1 : s;
    }

    /** Sequências dos blocos na fila, do que está tocando ao último enfileirado. */
    long[] queuedSeqs() {
        long[] out = new long[queuedSeqs.size()];
        int i = 0;
        for (long s : queuedSeqs) out[i++] = s;
        return out;
    }

    /** Posição de reprodução em amostras, contada a partir do primeiro buffer ainda na fila. */
    int sampleOffset() {
        return AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
    }

    /** Numa fonte parada, define de onde ela começa quando tocar (relativo ao primeiro buffer da fila). */
    void setSampleOffset(int offset) {
        AL10.alSourcei(source, AL11.AL_SAMPLE_OFFSET, offset);
    }

    /** Velocidade (correção de deriva da sincronia). */
    void setPitch(float pitch) {
        AL10.alSourcef(source, AL10.AL_PITCH, pitch);
    }

    /** Mesma fonte física (posição e canal) que a spec, ignorando o ganho. */
    boolean matches(EmitterSpec spec) {
        return spec.channel == channel && spec.relative == relative
            && spec.dry == dry
            && Math.abs(spec.x - x) < 1e-3
            && Math.abs(spec.y - y) < 1e-3
            && Math.abs(spec.z - z) < 1e-3;
    }

    int state() {
        return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
    }

    /** Oclusão suavizada atual (diagnóstico). */
    float occlusion() {
        return occlusion;
    }

    /** Ganho aplicado agora no AL_GAIN da fonte (diagnóstico). */
    float appliedGain() {
        return gain;
    }

    /**
     * Aplica posição, oclusão e ganho (suavizados). Com EFX a oclusão vira low-pass no caminho direto e no envio
     * do reverb; sem EFX, só ganho.
     */
    void applyParams(double dtSeconds, Efx efx) {
        if (positionDirty) {
            AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            positionDirty = false;
        }
        // Voz nova já nasce com a oclusão certa (sem um instante de som limpo atrás da parede).
        if (!occlusionInit) {
            occlusion = targetOcclusion;
            occlusionInit = true;
        } else {
            occlusion = GainModel.smooth(occlusion, targetOcclusion, dtSeconds, OCCLUSION_TAU_SECONDS);
            if (Math.abs(targetOcclusion - occlusion) < 1e-3f) occlusion = targetOcclusion;
        }
        float occlusionGain;
        if (dry) {
            // Fone: nem filtro nem reverb (os da sala de quem ouve não fazem sentido dentro do ouvido).
            occlusionGain = 1f;
            if (efx != null && efxId != efx.id) {
                efx.detach(source);
                efxId = efx.id;
                appliedOcclusion = 0f;
            } else if (efx == null) {
                efxId = 0;
                appliedOcclusion = -1f;
            }
        } else if (efx != null) {
            occlusionGain = 1f;
            if (efxId != efx.id || Math.abs(occlusion - appliedOcclusion) > OCCLUSION_EPSILON
                || (occlusion != appliedOcclusion && occlusion == targetOcclusion)) {
                efx.direct(
                    source,
                    (float) OcclusionTracer.directGain(occlusion),
                    (float) OcclusionTracer.highFrequencyGain(occlusion));
                efx.send(
                    source,
                    (float) OcclusionTracer.sendGain(occlusion),
                    (float) OcclusionTracer.sendHighFrequencyGain(occlusion));
                efxId = efx.id;
                appliedOcclusion = occlusion;
            }
        } else {
            efxId = 0; // sem EFX: a engine já desligou os filtros antes de soltar os objetos
            appliedOcclusion = -1f;
            occlusionGain = (float) OcclusionTracer.gainOnly(occlusion);
        }
        float target = targetGain * occlusionGain;
        float next = GainModel.smooth(gain, target, dtSeconds, GAIN_TAU_SECONDS);
        // A aproximação exponencial nunca chega exatamente: encaixa no alvo para parar de escrever todo frame.
        if (Math.abs(target - next) < 1e-4f) next = target;
        if (next != gain) {
            gain = next;
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
        }
    }

    /** Tira filtro e envio desta fonte se foram aplicados por {@code efx} (antes de a engine soltar o EFX). */
    void detachEfx(Efx efx) {
        if (source != 0 && efxId == efx.id) efx.detach(source);
        efxId = 0;
        appliedOcclusion = -1f;
    }
}
