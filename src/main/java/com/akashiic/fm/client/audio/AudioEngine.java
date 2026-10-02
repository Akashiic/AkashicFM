package com.akashiic.fm.client.audio;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;

import org.lwjgl.openal.AL;

import com.akashiic.fm.AkashicFM;

import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Dona de todas as reproduções de rádio e de todos os objetos OpenAL do mod no cliente.
 * <p>
 * Regras de thread: o OpenAL só é chamado na thread principal, em {@link #frame()}, ou dentro de
 * {@link #onContextDestroying()} (na thread que está destruindo o contexto, antes de ele morrer). As duas
 * coisas passam pelo mesmo lock. Cada recriação do contexto AL incrementa {@link #generation}: ids de uma
 * geração velha nunca são usados de novo (num contexto novo eles podem coincidir com fontes do Minecraft).
 * <p>
 * Watchdog: uma reprodução que o controlador não "toca" por 2 s é encerrada. Se o controlador parar de rodar
 * (mundo fechado, desconexão, troca de dimensão), o som morre sozinho; som órfão não existe.
 */
public final class AudioEngine {

    public static final AudioEngine INSTANCE = new AudioEngine();

    private static final long WATCHDOG_NANOS = 2_000_000_000L;
    private static final double MAX_DT = 0.25;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Playback> playbacks = new LinkedHashMap<>();
    private int generation;
    private volatile boolean contextReady;
    /** O mixin de ciclo de vida já disparou pelo menos uma vez: o fallback por reflection fica desligado. */
    private volatile boolean hookSeen;
    private long lastFrameNanos;
    private boolean frameFailureLogged;

    // Fallback por reflection (só se o mixin não aplicou neste ambiente).
    private boolean fallbackBroken;
    private Field fieldSndManager, fieldSndSystem, fieldLoaded;
    private Object lastSoundSystem;
    private Object lastAlContext;
    private boolean alContextUnavailable;

    private AudioEngine() {}

    // ---- Ganchos do ciclo de vida do contexto AL (mixin em LibraryLWJGLOpenAL; qualquer thread) ----

    /** Antes de o contexto AL ser destruído: apaga todas as fontes/buffers do mod enquanto ainda é válido. */
    public void onContextDestroying() {
        lock.lock();
        try {
            hookSeen = true;
            boolean alive = AL.isCreated();
            for (Playback p : playbacks.values()) p.releaseVoices(alive);
            contextReady = false;
            generation++;
        } finally {
            lock.unlock();
        }
    }

    /** Contexto AL novo pronto. */
    public void onContextCreated() {
        int gen;
        lock.lock();
        try {
            hookSeen = true;
            gen = ++generation;
            contextReady = true;
        } finally {
            lock.unlock();
        }
        AkashicFM.LOG.info("AkashicFM: contexto OpenAL pronto (geração {})", gen);
    }

    // ---- API da thread principal ----

    /**
     * Pega a reprodução da chave (criando com {@code feedFactory} se não existe), aplica as fontes desejadas
     * deste tick e renova o watchdog. Tudo sob o lock: o gancho de destruição do contexto pode estar mexendo
     * nas vozes em outra thread.
     */
    public void touch(String key, Supplier<AudioFeed> feedFactory, List<EmitterSpec> emitters) {
        lock.lock();
        try {
            Playback p = playbacks.get(key);
            if (p == null) {
                p = new Playback(key, feedFactory.get());
                playbacks.put(key, p);
            }
            p.lastTouchedNanos = System.nanoTime();
            p.setEmitters(emitters);
        } finally {
            lock.unlock();
        }
    }

    public boolean has(String key) {
        lock.lock();
        try {
            return playbacks.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    /** Encerra todas as reproduções cuja chave não está em {@code keep}. */
    public void retainOnly(Collection<String> keep) {
        Set<String> k = keep instanceof Set ? (Set<String>) keep : new HashSet<>(keep);
        lock.lock();
        try {
            Iterator<Map.Entry<String, Playback>> it = playbacks.entrySet()
                .iterator();
            while (it.hasNext()) {
                Map.Entry<String, Playback> e = it.next();
                if (!k.contains(e.getKey())) {
                    e.getValue()
                        .release(alUsable());
                    it.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void stopAll() {
        lock.lock();
        try {
            boolean alive = alUsable();
            for (Playback p : playbacks.values()) p.release(alive);
            playbacks.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Estado de uma reprodução para a GUI (null se não existe). */
    public PlaybackInfo info(String key) {
        lock.lock();
        try {
            Playback p = playbacks.get(key);
            if (p == null) return null;
            return new PlaybackInfo(
                p.state() == Playback.State.PLAYING,
                p.isDone(),
                p.feed.status(),
                p.feed.statusDetail(),
                p.voiceCount(),
                p.framesQueued,
                p.underruns,
                p.starts,
                p.joins,
                p.resyncs,
                p.syncErrorMs,
                p.pitch());
        } finally {
            lock.unlock();
        }
    }

    /** Fontes AL do mod em AL_PLAYING, somando todas as reproduções (diagnóstico; thread principal). */
    public int playingSources() {
        lock.lock();
        try {
            if (!alUsable()) return 0;
            int n = 0;
            for (Playback p : playbacks.values()) n += p.playingVoices();
            return n;
        } finally {
            lock.unlock();
        }
    }

    /** Fontes AL do mod vivas agora (diagnóstico de vazamento). */
    public static int liveSources() {
        return Voice.LIVE_SOURCES.get();
    }

    /** Buffers AL do mod vivos agora (diagnóstico de vazamento). */
    public static int liveBuffers() {
        return Voice.LIVE_BUFFERS.get();
    }

    /** Geração atual do contexto AL (muda a cada recriação). */
    public int generation() {
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    /** Vozes de todas as reproduções (diagnóstico). */
    public int totalVoices() {
        lock.lock();
        try {
            int n = 0;
            for (Playback p : playbacks.values()) n += p.voiceCount();
            return n;
        } finally {
            lock.unlock();
        }
    }

    public int activeCount() {
        lock.lock();
        try {
            return playbacks.size();
        } finally {
            lock.unlock();
        }
    }

    /** Um frame de áudio (RenderTickEvent). Nunca bloqueia o render: se o lock está ocupado, pula o frame. */
    public void frame() {
        if (!lock.tryLock()) return;
        try {
            long now = System.nanoTime();
            double dt = lastFrameNanos == 0 ? 0.016 : Math.min(MAX_DT, (now - lastFrameNanos) / 1e9);
            lastFrameNanos = now;
            pollFallback();
            boolean alive = alUsable();

            List<String> expired = new ArrayList<>();
            for (Playback p : playbacks.values()) {
                if (now - p.lastTouchedNanos > WATCHDOG_NANOS) expired.add(p.key);
            }
            for (String key : expired) playbacks.remove(key)
                .release(alive);
            if (!alive) return;

            boolean paused = Minecraft.getMinecraft()
                .isGamePaused();
            Iterator<Playback> it = playbacks.values()
                .iterator();
            while (it.hasNext()) {
                Playback p = it.next();
                try {
                    p.update(generation, dt, paused, now);
                } catch (Throwable t) {
                    // Um erro de áudio nunca pode derrubar o jogo: encerra só esta reprodução.
                    if (!frameFailureLogged) {
                        frameFailureLogged = true;
                        AkashicFM.LOG.error("Falha na reprodução de rádio {}; encerrando-a", p.key, t);
                    }
                    try {
                        p.release(true);
                    } catch (Throwable ignored) {
                        p.release(false);
                    }
                    it.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean alUsable() {
        return contextReady && AL.isCreated();
    }

    /**
     * Sem o mixin, detecta recriação do sound system pela identidade do SoundSystem do Minecraft. Roda na
     * thread principal, a mesma que descarrega o sound system, então nunca vê um contexto pela metade.
     */
    private void pollFallback() {
        if (hookSeen || fallbackBroken) return;
        try {
            if (fieldSndManager == null) {
                fieldSndManager = ReflectionHelper.findField(SoundHandler.class, "sndManager", "field_147694_f");
                fieldSndSystem = ReflectionHelper.findField(SoundManager.class, "sndSystem", "field_148620_e");
                fieldLoaded = ReflectionHelper.findField(SoundManager.class, "loaded", "field_148617_f");
            }
            SoundHandler handler = Minecraft.getMinecraft()
                .getSoundHandler();
            Object manager = handler == null ? null : fieldSndManager.get(handler);
            boolean loaded = manager != null && fieldLoaded.getBoolean(manager);
            Object system = manager == null ? null : fieldSndSystem.get(manager);
            Object context = currentAlContext();
            if (system != lastSoundSystem || context != lastAlContext) {
                // Recriação do sound system ou do contexto: ids antigos não valem mais. Neste frame não toca;
                // no próximo, com tudo estável, volta.
                lastSoundSystem = system;
                lastAlContext = context;
                generation++;
                contextReady = false;
                return;
            }
            contextReady = loaded && system != null && AL.isCreated();
        } catch (Throwable t) {
            fallbackBroken = true;
            contextReady = AL.isCreated();
            AkashicFM.LOG.warn("AkashicFM: detecção do sound system por reflection indisponível", t);
        }
    }

    /** Objeto do contexto AL atual (identidade muda a cada recriação), ou null. */
    private Object currentAlContext() {
        if (alContextUnavailable) return null;
        try {
            return AL.isCreated() ? AL.getContext() : null;
        } catch (Throwable t) { // implementação de AL sem getContext (ex.: camada de compatibilidade)
            alContextUnavailable = true;
            return null;
        }
    }

    /** Fotografia do estado de uma reprodução, para a GUI. */
    public static final class PlaybackInfo {

        public final boolean playing;
        public final boolean done;
        public final AudioFeed.Status feedStatus;
        public final String detail;
        public final int voices;
        public final long framesQueued;
        public final int underruns;
        /** Vezes que todas as vozes (re)começaram juntas depois do prebuffer. */
        public final int starts;
        /** Vozes que entraram alinhadas com a reprodução em andamento. */
        public final int joins;
        /** Ressincronizações (feeds com relógio). */
        public final int resyncs;
        /** Erro de sincronia suavizado, ms (positivo = adiantado); NaN sem relógio. */
        public final double syncErrorMs;
        public final float pitch;

        PlaybackInfo(boolean playing, boolean done, AudioFeed.Status feedStatus, String detail, int voices,
            long framesQueued, int underruns, int starts, int joins, int resyncs, double syncErrorMs, float pitch) {
            this.playing = playing;
            this.done = done;
            this.feedStatus = feedStatus;
            this.detail = detail;
            this.voices = voices;
            this.framesQueued = framesQueued;
            this.underruns = underruns;
            this.starts = starts;
            this.joins = joins;
            this.resyncs = resyncs;
            this.syncErrorMs = syncErrorMs;
            this.pitch = pitch;
        }
    }
}
