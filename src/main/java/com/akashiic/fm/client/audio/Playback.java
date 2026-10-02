package com.akashiic.fm.client.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

/**
 * Uma estação sendo ouvida: um {@link AudioFeed} e uma {@link Voice} por fonte (rádio em estéreo, caixas).
 * Todas as vozes recebem o mesmo bloco de PCM ao mesmo tempo e começam juntas (alSourcePlayv), então
 * ficam alinhadas. Num underrun todas param e recomeçam juntas depois do prebuffer.
 */
final class Playback {

    /** 2048 frames a 48 kHz ≈ 42,7 ms por buffer. */
    static final int CHUNK_FRAMES = 2048;
    /** Buffers na fila de cada fonte (≈ 341 ms): aguenta frames lentos sem picotar. */
    static final int TARGET_CHUNKS = 8;
    /** Antes de (re)começar, espera ter isto no feed (≈ 171 ms). */
    static final int PREBUFFER_CHUNKS = 4;
    private static final long RETRY_AFTER_FAILURE_NANOS = 2_000_000_000L;

    enum State {
        WAITING,
        PLAYING
    }

    final String key;
    final AudioFeed feed;
    private final List<Voice> voices = new ArrayList<>();
    private List<EmitterSpec> desired = Collections.emptyList();
    private boolean structureChanged = true;
    private int voicesGeneration = Integer.MIN_VALUE;
    private State state = State.WAITING;
    private boolean paused;
    /** nanoTime tem origem arbitrária (pode ser negativo): o prazo só vale com a flag ligada. */
    private boolean retryPending;
    private long retryAfterNanos;
    long lastTouchedNanos;

    private final short[] stereo = new short[CHUNK_FRAMES * 2];
    private final ShortBuffer mono = BufferUtils.createShortBuffer(CHUNK_FRAMES);
    private IntBuffer sourceVector = BufferUtils.createIntBuffer(4);

    Playback(String key, AudioFeed feed) {
        this.key = key;
        this.feed = feed;
    }

    State state() {
        return state;
    }

    int voiceCount() {
        return voices.size();
    }

    /** Controlador (thread principal): fontes desejadas e ganhos-alvo deste tick. */
    void setEmitters(List<EmitterSpec> specs) {
        boolean same = specs.size() == desired.size();
        for (int i = 0; same && i < specs.size(); i++) same = specs.get(i)
            .sameVoice(desired.get(i));
        desired = new ArrayList<>(specs);
        if (!same) {
            structureChanged = true;
            return;
        }
        for (int i = 0; i < voices.size() && i < specs.size(); i++) voices.get(i).targetGain = specs.get(i).gain;
    }

    /** Reprodução de arquivo terminou e já tocou tudo. */
    boolean isDone() {
        return feed.isFinished() && state == State.WAITING;
    }

    /**
     * Um frame. {@code generation} é a geração do contexto AL: se mudou, os ids antigos são inválidos e as
     * vozes são recriadas sem chamar o OpenAL com eles.
     */
    void update(int generation, double dtSeconds, boolean gamePaused, long now) {
        if (voicesGeneration != generation) {
            for (Voice v : voices) v.release(false);
            voices.clear();
            voicesGeneration = generation;
            structureChanged = true;
        }
        if (structureChanged) {
            if (retryPending && now - retryAfterNanos < 0) return;
            retryPending = false;
            rebuildVoices(now);
            if (voices.isEmpty()) return;
        }
        if (voices.isEmpty()) return;

        if (gamePaused != paused) {
            paused = gamePaused;
            if (state == State.PLAYING) {
                if (paused) AL10.alSourcePause(vector());
                else AL10.alSourcePlay(vector());
            }
        }
        for (Voice v : voices) v.reclaimProcessed();

        if (!paused) {
            if (state == State.WAITING) {
                int need = PREBUFFER_CHUNKS * CHUNK_FRAMES;
                if (feed.available() >= need || (feed.producerDone() && feed.available() > 0)) {
                    fill();
                    AL10.alSourcePlay(vector());
                    state = State.PLAYING;
                }
            } else {
                fill();
                if (anyStopped()) {
                    // Underrun (o feed não acompanhou): para tudo e recomeça junto depois do prebuffer.
                    for (Voice v : voices) v.resetQueue();
                    state = State.WAITING;
                }
            }
        }
        for (Voice v : voices) v.applyParams(dtSeconds);
    }

    /** Enfileira blocos em todas as vozes até o alvo ou até acabar o PCM disponível. */
    private void fill() {
        while (maxQueued() < TARGET_CHUNKS) {
            int avail = feed.available();
            int frames;
            if (avail >= CHUNK_FRAMES) frames = CHUNK_FRAMES;
            else if (feed.producerDone() && avail > 0) frames = avail; // último pedaço do arquivo
            else return;
            int got = feed.read(stereo, frames);
            if (got <= 0) return;
            for (Voice v : voices) {
                fillMono(v, got);
                if (!v.queue(mono, AudioFeed.SAMPLE_RATE)) return;
            }
        }
    }

    private void fillMono(Voice v, int frames) {
        mono.clear();
        switch (v.channel) {
            case LEFT:
                for (int i = 0; i < frames; i++) mono.put(stereo[2 * i]);
                break;
            case RIGHT:
                for (int i = 0; i < frames; i++) mono.put(stereo[2 * i + 1]);
                break;
            default: // MIX
                for (int i = 0; i < frames; i++) mono.put((short) ((stereo[2 * i] + stereo[2 * i + 1]) >> 1));
                break;
        }
        mono.flip();
    }

    private int maxQueued() {
        int max = 0;
        for (Voice v : voices) max = Math.max(max, v.queued());
        return max;
    }

    private boolean anyStopped() {
        for (Voice v : voices) if (v.state() != AL10.AL_PLAYING) return true;
        return false;
    }

    private IntBuffer vector() {
        if (sourceVector.capacity() < voices.size()) sourceVector = BufferUtils.createIntBuffer(voices.size());
        sourceVector.clear();
        for (Voice v : voices) sourceVector.put(v.source());
        sourceVector.flip();
        return sourceVector;
    }

    private void rebuildVoices(long now) {
        for (Voice v : voices) v.release(true);
        voices.clear();
        state = State.WAITING;
        paused = false;
        for (EmitterSpec spec : desired) {
            Voice v = new Voice(spec);
            if (!v.create()) {
                // Sem fontes livres no OpenAL: desiste por ora e tenta de novo depois, sem travar o frame.
                for (Voice created : voices) created.release(true);
                voices.clear();
                retryPending = true;
                retryAfterNanos = now + RETRY_AFTER_FAILURE_NANOS;
                return;
            }
            voices.add(v);
        }
        structureChanged = false;
    }

    /** Libera as fontes. {@code contextAlive}=false: só esquece os ids. */
    void releaseVoices(boolean contextAlive) {
        for (Voice v : voices) v.release(contextAlive);
        voices.clear();
        structureChanged = true;
        state = State.WAITING;
    }

    /** Fim da reprodução: fontes e feed (rede, thread). */
    void release(boolean contextAlive) {
        releaseVoices(contextAlive);
        feed.close();
    }
}
