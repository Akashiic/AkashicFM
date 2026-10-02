package com.akashiic.fm.client.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

/**
 * Uma estação sendo ouvida: um {@link AudioFeed} e uma {@link Voice} por fonte (rádio em estéreo, caixas).
 * Todas as vozes recebem o mesmo bloco de PCM, com o mesmo número de sequência, e começam juntas
 * (alSourcePlayv), então ficam alinhadas. Num underrun todas param e recomeçam juntas depois do prebuffer.
 * <p>
 * As vozes são reconciliadas por identidade (posição + canal), não pela ordem da lista: uma caixa que sai
 * apaga só a fonte dela, e uma que entra durante a reprodução recebe cópia dos blocos que as outras ainda
 * têm na fila e começa no mesmo ponto delas ({@code AL_SAMPLE_OFFSET}). Nada para nem pula.
 */
final class Playback {

    /** 2048 frames a 48 kHz ≈ 42,7 ms por buffer. */
    static final int CHUNK_FRAMES = 2048;
    /** Buffers na fila de cada fonte (≈ 341 ms): aguenta frames lentos sem picotar. */
    static final int TARGET_CHUNKS = 8;
    /** Antes de (re)começar, espera ter isto no feed (≈ 171 ms). */
    static final int PREBUFFER_CHUNKS = 4;
    /** Blocos recentes guardados para uma voz nova entrar alinhada (cobre a fila inteira com folga). */
    static final int HISTORY_CHUNKS = Voice.POOL_SIZE + 2;
    private static final long RETRY_AFTER_FAILURE_NANOS = 2_000_000_000L;

    enum State {
        WAITING,
        PLAYING
    }

    final String key;
    final AudioFeed feed;
    private final List<Voice> voices = new ArrayList<>();
    private List<EmitterSpec> desired = Collections.emptyList();
    /** O conjunto de fontes desejado mudou (ou a geração do contexto): reconciliar no próximo frame. */
    private boolean membershipChanged = true;
    private int voicesGeneration = Integer.MIN_VALUE;
    private State state = State.WAITING;
    private boolean paused;
    /** nanoTime tem origem arbitrária (pode ser negativo): o prazo só vale com a flag ligada. */
    private boolean retryPending;
    private long retryAfterNanos;
    long lastTouchedNanos;
    /**
     * Diagnóstico: frames entregues ao OpenAL, underruns, inícios (prebuffer → tocando) e vozes que entraram
     * com a reprodução em andamento.
     */
    long framesQueued;
    int underruns;
    int starts;
    int joins;

    private final short[] stereo = new short[CHUNK_FRAMES * 2];
    private final ShortBuffer mono = BufferUtils.createShortBuffer(CHUNK_FRAMES);
    private IntBuffer sourceVector = BufferUtils.createIntBuffer(4);

    // Histórico circular dos últimos blocos (estéreo intercalado), indexado pela sequência.
    private final short[][] history = new short[HISTORY_CHUNKS][CHUNK_FRAMES * 2];
    private final int[] historyFrames = new int[HISTORY_CHUNKS];
    private final long[] historySeq = new long[HISTORY_CHUNKS];
    private long nextSeq;

    Playback(String key, AudioFeed feed) {
        this.key = key;
        this.feed = feed;
        Arrays.fill(historySeq, -1);
    }

    State state() {
        return state;
    }

    int voiceCount() {
        return voices.size();
    }

    /** Vozes com a fonte AL em AL_PLAYING. Só com o contexto válido (chama o OpenAL). */
    int playingVoices() {
        int n = 0;
        for (Voice v : voices) if (v.isCreated() && v.state() == AL10.AL_PLAYING) n++;
        return n;
    }

    /**
     * Controlador (thread principal): fontes desejadas e ganhos-alvo deste tick. Atualiza o ganho das vozes
     * que continuam e só marca reconciliação se o conjunto de fontes mudou (a ordem não importa).
     */
    void setEmitters(List<EmitterSpec> specs) {
        desired = new ArrayList<>(specs);
        boolean[] used = new boolean[desired.size()];
        boolean changed = false;
        for (Voice v : voices) {
            int idx = match(v, used);
            if (idx < 0) {
                changed = true;
                continue;
            }
            used[idx] = true;
            v.targetGain = desired.get(idx).gain;
        }
        for (boolean u : used) if (!u) changed = true;
        if (changed) membershipChanged = true;
    }

    private int match(Voice v, boolean[] used) {
        for (int i = 0; i < desired.size(); i++) {
            if (!used[i] && v.matches(desired.get(i))) return i;
        }
        return -1;
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
            state = State.WAITING;
            membershipChanged = true;
        }
        if (membershipChanged && !(retryPending && now - retryAfterNanos < 0)) reconcile(now);
        if (voices.isEmpty()) {
            state = State.WAITING;
            return;
        }

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
                    starts++;
                }
            } else {
                fill();
                if (anyStopped()) {
                    // Underrun (o feed não acompanhou): para tudo e recomeça junto depois do prebuffer.
                    for (Voice v : voices) v.resetQueue();
                    state = State.WAITING;
                    underruns++;
                }
            }
        }
        for (Voice v : voices) v.applyParams(dtSeconds);
    }

    /** Tira as vozes que não são mais desejadas e cria as que faltam (entrando alinhadas se já está tocando). */
    private void reconcile(long now) {
        retryPending = false;
        boolean[] used = new boolean[desired.size()];
        Iterator<Voice> it = voices.iterator();
        while (it.hasNext()) {
            Voice v = it.next();
            int idx = match(v, used);
            if (idx < 0) {
                v.release(true);
                it.remove();
            } else {
                used[idx] = true;
                v.targetGain = desired.get(idx).gain;
            }
        }
        Voice reference = state == State.PLAYING && !voices.isEmpty() ? voices.get(0) : null;
        for (int i = 0; i < desired.size(); i++) {
            if (used[i]) continue;
            Voice v = new Voice(desired.get(i));
            if (!v.create()) {
                // Sem fontes livres no OpenAL: fica com as que já tem e tenta o resto depois.
                retryPending = true;
                retryAfterNanos = now + RETRY_AFTER_FAILURE_NANOS;
                break;
            }
            voices.add(v);
            if (reference != null) joinAligned(v, reference);
        }
        membershipChanged = retryPending;
        if (voices.isEmpty()) state = State.WAITING;
    }

    /**
     * Voz nova entrando com a reprodução em andamento: recebe os mesmos blocos que a referência tem na fila e
     * começa na mesma amostra. A diferença fica abaixo de um ciclo do mixer do OpenAL (poucos ms).
     */
    private void joinAligned(Voice v, Voice reference) {
        reference.reclaimProcessed();
        long[] seqs = reference.queuedSeqs();
        if (seqs.length == 0) return; // a referência está para dar underrun: todas recomeçam juntas
        for (long seq : seqs) {
            int slot = historySlot(seq);
            if (slot < 0) { // não deveria acontecer (o histórico cobre a fila inteira); entra no próximo início
                v.resetQueue();
                return;
            }
            fillMono(v, history[slot], historyFrames[slot]);
            if (!v.queue(mono, AudioFeed.SAMPLE_RATE, seq)) {
                v.resetQueue();
                return;
            }
        }
        v.setSampleOffset(reference.sampleOffset());
        if (!paused) AL10.alSourcePlay(v.source());
        joins++;
    }

    private int historySlot(long seq) {
        int slot = (int) (seq % HISTORY_CHUNKS);
        return historySeq[slot] == seq ? slot : -1;
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
            framesQueued += got;
            long seq = nextSeq++;
            int slot = (int) (seq % HISTORY_CHUNKS);
            System.arraycopy(stereo, 0, history[slot], 0, got * 2);
            historyFrames[slot] = got;
            historySeq[slot] = seq;
            for (Voice v : voices) {
                fillMono(v, stereo, got);
                if (!v.queue(mono, AudioFeed.SAMPLE_RATE, seq)) return;
            }
        }
    }

    private void fillMono(Voice v, short[] src, int frames) {
        mono.clear();
        switch (v.channel) {
            case LEFT:
                for (int i = 0; i < frames; i++) mono.put(src[2 * i]);
                break;
            case RIGHT:
                for (int i = 0; i < frames; i++) mono.put(src[2 * i + 1]);
                break;
            default: // MIX
                for (int i = 0; i < frames; i++) mono.put((short) ((src[2 * i] + src[2 * i + 1]) >> 1));
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

    /** Libera as fontes. {@code contextAlive}=false: só esquece os ids. */
    void releaseVoices(boolean contextAlive) {
        for (Voice v : voices) v.release(contextAlive);
        voices.clear();
        membershipChanged = true;
        state = State.WAITING;
    }

    /** Fim da reprodução: fontes e feed (rede, thread). */
    void release(boolean contextAlive) {
        releaseVoices(contextAlive);
        feed.close();
    }
}
