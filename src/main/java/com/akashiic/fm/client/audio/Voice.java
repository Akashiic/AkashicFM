package com.akashiic.fm.client.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import com.akashiic.fm.audio.dsp.GainModel;
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

    final SpeakerChannel channel;
    private int source;
    private final int[] buffers = new int[POOL_SIZE];
    private final ArrayDeque<Integer> free = new ArrayDeque<>(POOL_SIZE);
    /** Número de sequência do bloco em cada buffer da fila, na mesma ordem da fila do OpenAL. */
    private final ArrayDeque<Long> queuedSeqs = new ArrayDeque<>(POOL_SIZE);
    private final IntBuffer tmp = BufferUtils.createIntBuffer(1);
    private int queued;

    final double x, y, z;
    private boolean positionDirty = true;
    float targetGain;
    private float gain;

    Voice(EmitterSpec spec) {
        this.channel = spec.channel;
        this.x = spec.x;
        this.y = spec.y;
        this.z = spec.z;
        this.targetGain = spec.gain;
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
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
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
        return spec.channel == channel && Math.abs(spec.x - x) < 1e-3
            && Math.abs(spec.y - y) < 1e-3
            && Math.abs(spec.z - z) < 1e-3;
    }

    int state() {
        return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
    }

    /** Aplica posição e ganho (suavizado) na fonte. */
    void applyParams(double dtSeconds) {
        if (positionDirty) {
            AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            positionDirty = false;
        }
        float next = GainModel.smooth(gain, targetGain, dtSeconds, GAIN_TAU_SECONDS);
        // A aproximação exponencial nunca chega exatamente: encaixa no alvo para parar de escrever todo frame.
        if (Math.abs(targetGain - next) < 1e-4f) next = targetGain;
        if (next != gain) {
            gain = next;
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
        }
    }
}
