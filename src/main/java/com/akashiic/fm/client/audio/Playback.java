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

import com.akashiic.fm.audio.dsp.SpectrumAnalyzer;
import com.akashiic.fm.client.relay.ClockSync;

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
    /** O espectro só é calculado se alguém (tela da rádio, cone da caixa) pediu nos últimos 2 s. */
    private static final long VISUAL_REQUEST_NANOS = 2_000_000_000L;
    /** Barras sobem rápido e descem devagar, como num VU. */
    private static final double VISUAL_ATTACK_S = 0.04, VISUAL_RELEASE_S = 0.3;
    /** Sincronia (feeds com relógio): erro a partir do qual a reprodução pula/espera de uma vez. */
    static final double RESYNC_ERROR_MS = 250;
    /** Correção máxima de velocidade (±0,2%: 3,5 cents, inaudível), ou seja, até 2 ms de erro por segundo. */
    static final double MAX_PITCH_DELTA = 0.002;
    /** Erro (ms) abaixo do qual não corrige (o offset do OpenAL é quantizado no ciclo do mixer). */
    static final double DEADBAND_MS = 2;

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
    int resyncs;
    /** Erro de sincronia suavizado (ms; positivo = tocando adiantado), NaN se não sincroniza. */
    double syncErrorMs = Double.NaN;
    private boolean errInit;
    private float pitch = 1f;

    private final short[] stereo = new short[CHUNK_FRAMES * 2];
    private final ShortBuffer mono = BufferUtils.createShortBuffer(CHUNK_FRAMES);
    private IntBuffer sourceVector = BufferUtils.createIntBuffer(4);

    // Histórico circular dos últimos blocos (estéreo intercalado), indexado pela sequência.
    private final short[][] history = new short[HISTORY_CHUNKS][CHUNK_FRAMES * 2];
    private final int[] historyFrames = new int[HISTORY_CHUNKS];
    private final long[] historySeq = new long[HISTORY_CHUNKS];
    /** PTS (ms, relógio do servidor) do primeiro frame de cada bloco do histórico (feeds com relógio). */
    private final double[] historyPts = new double[HISTORY_CHUNKS];
    /** Espectro e nível de cada bloco do histórico (válido só se calculado: alguém estava olhando). */
    private final float[][] historyBands = new float[HISTORY_CHUNKS][SpectrumAnalyzer.BANDS];
    private final float[] historyLevel = new float[HISTORY_CHUNKS];
    private final boolean[] historyAnalyzed = new boolean[HISTORY_CHUNKS];
    private long nextSeq;
    private SpectrumAnalyzer analyzer;
    private boolean visualRequested;
    private long visualRequestNanos;
    /** O que a tela mostra agora: espectro e nível do bloco que soa, suavizados. */
    private final float[] displayBands = new float[SpectrumAnalyzer.BANDS];
    private float displayLevel;

    Playback(String key, AudioFeed feed) {
        this.key = key;
        this.feed = feed;
        Arrays.fill(historySeq, -1);
        Arrays.fill(historyPts, Double.NaN);
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
            v.setTargets(desired.get(idx));
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
    void update(int generation, double dtSeconds, boolean gamePaused, long now, Efx efx) {
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
                if (feed.timed() ? readyToStartTimed() : readyToStart()) {
                    fill();
                    AL10.alSourcePlay(vector());
                    state = State.PLAYING;
                    starts++;
                    errInit = false;
                    setPitch(1f);
                }
            } else {
                fill();
                if (anyStopped()) {
                    // Underrun (o feed não acompanhou): para tudo e recomeça junto depois do prebuffer.
                    for (Voice v : voices) v.resetQueue();
                    state = State.WAITING;
                    underruns++;
                } else if (feed.timed()) {
                    correctDrift();
                }
            }
        }
        for (Voice v : voices) v.applyParams(dtSeconds, efx);
        updateVisuals(dtSeconds, now);
    }

    /** Espectro mostrado: o do bloco que está soando agora (não o que acabou de entrar na fila, 340 ms adiante). */
    private void updateVisuals(double dt, long now) {
        if (visualRequested && now - visualRequestNanos > VISUAL_REQUEST_NANOS) visualRequested = false;
        int slot = -1;
        if (state == State.PLAYING && !paused && !voices.isEmpty()) {
            long head = voices.get(0)
                .headSeq();
            if (head >= 0) slot = historySlot(head);
            if (slot >= 0 && !historyAnalyzed[slot]) slot = -1;
        }
        double up = 1 - Math.exp(-dt / VISUAL_ATTACK_S), down = 1 - Math.exp(-dt / VISUAL_RELEASE_S);
        for (int b = 0; b < displayBands.length; b++) {
            float target = slot >= 0 ? historyBands[slot][b] : 0f;
            displayBands[b] += (target - displayBands[b]) * (target > displayBands[b] ? up : down);
        }
        float targetLevel = slot >= 0 ? historyLevel[slot] : 0f;
        displayLevel += (targetLevel - displayLevel) * (targetLevel > displayLevel ? up : down);
    }

    /** Pedido da tela/cone (renova o cálculo do espectro) e cópia do que mostrar. Devolve o nível (0..1). */
    float visuals(float[] bandsOut, long now) {
        visualRequested = true;
        visualRequestNanos = now;
        if (bandsOut != null)
            System.arraycopy(displayBands, 0, bandsOut, 0, Math.min(bandsOut.length, displayBands.length));
        return displayLevel;
    }

    private boolean readyToStart() {
        int need = PREBUFFER_CHUNKS * CHUNK_FRAMES;
        return feed.available() >= need || (feed.producerDone() && feed.available() > 0);
    }

    /**
     * Feed com relógio: começa quando o próximo frame é o devido agora ({@code relógio do servidor − latência}),
     * igual em todos os clientes. Dado atrasado é pulado com precisão de amostra; dado do futuro espera.
     */
    private boolean readyToStartTimed() {
        if (!ClockSync.ready()) return false;
        double due = ClockSync.serverNowMs() - feed.latencyMs();
        for (int guard = 0; guard < 8; guard++) {
            double p = feed.ptsAtReadPosition();
            if (Double.isNaN(p)) return false;
            if (p >= due - 0.5) break;
            int frames = (int) Math.ceil((due - p) * AudioFeed.SAMPLE_RATE / 1000.0);
            frames = Math.min(frames, Math.max(1, feed.framesUntilDiscontinuity()));
            if (feed.skip(frames) == 0) return false;
        }
        double p = feed.ptsAtReadPosition();
        if (Double.isNaN(p) || p > due + 1.0) return false;
        return feed.available() >= PREBUFFER_CHUNKS * CHUNK_FRAMES;
    }

    /**
     * Mede a posição real tocada (PTS do bloco na frente da fila + offset do OpenAL) contra a devida e corrige
     * a velocidade de todas as vozes juntas; erro grande demais ressincroniza.
     */
    private void correctDrift() {
        Voice ref = voices.get(0);
        long[] seqs = ref.queuedSeqs();
        if (seqs.length == 0) return;
        int slot = historySlot(seqs[0]);
        if (slot < 0 || Double.isNaN(historyPts[slot])) return;
        double playing = historyPts[slot] + ref.sampleOffset() * 1000.0 / AudioFeed.SAMPLE_RATE;
        double err = playing - (ClockSync.serverNowMs() - feed.latencyMs());
        syncErrorMs = errInit ? syncErrorMs + 0.1 * (err - syncErrorMs) : err;
        errInit = true;
        if (Math.abs(err) > RESYNC_ERROR_MS && Math.abs(syncErrorMs) > RESYNC_ERROR_MS / 2) {
            for (Voice v : voices) v.resetQueue();
            state = State.WAITING;
            resyncs++;
            setPitch(1f);
            return;
        }
        double target = Math.abs(syncErrorMs) < DEADBAND_MS ? 1.0
            : 1.0 - Math.max(-MAX_PITCH_DELTA, Math.min(MAX_PITCH_DELTA, syncErrorMs / 10_000.0));
        if (Math.abs(target - pitch) > 1e-5) setPitch((float) target);
    }

    private void setPitch(float p) {
        pitch = p;
        for (Voice v : voices) v.setPitch(p);
    }

    float pitch() {
        return pitch;
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
                v.setTargets(desired.get(idx));
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
            v.setPitch(pitch);
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
        boolean timed = feed.timed();
        while (maxQueued() < TARGET_CHUNKS) {
            int avail = feed.available();
            int frames;
            if (avail >= CHUNK_FRAMES) frames = CHUNK_FRAMES;
            else if (feed.producerDone() && avail > 0) frames = avail; // último pedaço do arquivo
            else return;
            double chunkPts = Double.NaN;
            if (timed) {
                // Um bloco nunca atravessa uma descontinuidade: o PTS de cada amostra fica exato.
                int until = feed.framesUntilDiscontinuity();
                if (until > 0 && until < frames) frames = until;
                chunkPts = feed.ptsAtReadPosition();
            }
            int got = feed.read(stereo, frames);
            if (got <= 0) return;
            framesQueued += got;
            long seq = nextSeq++;
            int slot = (int) (seq % HISTORY_CHUNKS);
            System.arraycopy(stereo, 0, history[slot], 0, got * 2);
            historyFrames[slot] = got;
            historySeq[slot] = seq;
            historyPts[slot] = chunkPts;
            historyAnalyzed[slot] = visualRequested;
            if (visualRequested) {
                if (analyzer == null) analyzer = new SpectrumAnalyzer(AudioFeed.SAMPLE_RATE);
                historyLevel[slot] = analyzer.analyze(stereo, got, historyBands[slot]);
            }
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

    /** Desliga das fontes os filtros e envios de {@code efx} (a engine vai soltar esses objetos). */
    void detachEfx(Efx efx) {
        for (Voice v : voices) v.detachEfx(efx);
    }

    /** Oclusão suavizada de cada voz (diagnóstico). */
    /** Vozes presas a quem ouve e, delas, as sem filtro/reverb (diagnóstico do portátil e do fone). */
    int relativeVoices() {
        int n = 0;
        for (Voice v : voices) if (v.relative) n++;
        return n;
    }

    int dryVoices() {
        int n = 0;
        for (Voice v : voices) if (v.dry) n++;
        return n;
    }

    float[] occlusions() {
        float[] out = new float[voices.size()];
        for (int i = 0; i < out.length; i++) out[i] = voices.get(i)
            .occlusion();
        return out;
    }

    /** AL_GAIN aplicado em cada voz (diagnóstico). */
    float[] appliedGains() {
        float[] out = new float[voices.size()];
        for (int i = 0; i < out.length; i++) out[i] = voices.get(i)
            .appliedGain();
        return out;
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
