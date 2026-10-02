package com.akashiic.fm.client.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import com.akashiic.fm.audio.dsp.GainModel;
import com.akashiic.fm.common.SpeakerChannel;

/**
 * Uma fonte OpenAL com pool fixo de buffers. Nunca cria buffer novo durante a reprodução: os processados
 * são desenfileirados e reaproveitados, então a memória do OpenAL não cresce (o OpenFM vazava ~635 MB/h).
 * Só a thread principal usa, sempre com o contexto AL válido (garantido pela {@link AudioEngine}).
 */
final class Voice {

    static final int POOL_SIZE = 10;
    private static final double GAIN_TAU_SECONDS = 0.08;

    final SpeakerChannel channel;
    private int source;
    private final int[] buffers = new int[POOL_SIZE];
    private final ArrayDeque<Integer> free = new ArrayDeque<>(POOL_SIZE);
    private final IntBuffer tmp = BufferUtils.createIntBuffer(1);
    private int queued;

    double x, y, z;
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
            free.add(buffers[i]);
        }
        gain = 0f;
        positionDirty = true;
        return true;
    }

    /** Libera fonte e buffers. Com {@code contextAlive}=false só esquece os ids (o contexto já morreu). */
    void release(boolean contextAlive) {
        if (contextAlive && source != 0) {
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, 0); // solta toda a fila
            AL10.alDeleteSources(source);
        }
        for (int i = 0; i < POOL_SIZE; i++) {
            if (contextAlive && buffers[i] != 0) AL10.alDeleteBuffers(buffers[i]);
            buffers[i] = 0;
        }
        if (contextAlive) AL10.alGetError();
        source = 0;
        free.clear();
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
        queued = 0;
    }

    /** Enfileira um bloco mono 16-bit. Devolve false se o pool está vazio (não deveria acontecer). */
    boolean queue(ShortBuffer mono, int sampleRate) {
        Integer id = free.poll();
        if (id == null) return false;
        AL10.alBufferData(id, AL10.AL_FORMAT_MONO16, mono, sampleRate);
        AL10.alSourceQueueBuffers(source, id);
        queued++;
        return true;
    }

    int state() {
        return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
    }

    void moveTo(double nx, double ny, double nz) {
        if (nx != x || ny != y || nz != z) {
            x = nx;
            y = ny;
            z = nz;
            positionDirty = true;
        }
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
