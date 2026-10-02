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
import com.akashiic.fm.audio.spatial.RoomModel;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.dev.DevE2E;

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
 * <p>
 * EFX (oclusão com low-pass e reverb da sala): os objetos ({@link Efx}) são criados quando há algo tocando e
 * soltos quando nada toca, quando o config desliga oclusão e reverb, ou junto com o contexto. Sem EFX no
 * dispositivo (ou se falhar), a oclusão fica só no ganho.
 */
public final class AudioEngine {

    public static final AudioEngine INSTANCE = new AudioEngine();

    private static final long WATCHDOG_NANOS = 2_000_000_000L;
    /**
     * Sem nada tocando, o EFX espera isto antes de ser solto: a cauda do reverb (até 4 s) termina de soar e quem
     * volta ao alcance logo reaproveita os objetos.
     */
    private static final long EFX_IDLE_RELEASE_NANOS = 4_000_000_000L;
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

    private Efx efx;
    private int efxGeneration;
    /** Geração em que a criação do EFX já foi tentada: sem EFX no dispositivo, não tenta de novo a cada frame. */
    private int efxTriedGeneration = Integer.MIN_VALUE;
    /** Geração em que um erro de OpenAL apareceu com EFX ativo: nela o EFX fica desligado (melhor só ganho). */
    private int efxFailedGeneration = Integer.MIN_VALUE;
    private RoomModel.Params room = RoomModel.DRY;
    private boolean reverbEnabled;
    private boolean efxIdle;
    private long efxIdleSinceNanos;

    private AudioEngine() {}

    // ---- Ganchos do ciclo de vida do contexto AL (mixin em LibraryLWJGLOpenAL; qualquer thread) ----

    /** Antes de o contexto AL ser destruído: apaga todas as fontes/buffers do mod enquanto ainda é válido. */
    public void onContextDestroying() {
        lock.lock();
        try {
            hookSeen = true;
            boolean alive = AL.isCreated();
            for (Playback p : playbacks.values()) p.releaseVoices(alive);
            // Depois das fontes: o OpenAL Soft recusa apagar um slot auxiliar ainda ligado a uma fonte.
            releaseEfx(alive);
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

    /** Sala do ouvinte para o reverb (controlador, a cada tick). */
    public void setRoom(RoomModel.Params params, boolean enabled) {
        lock.lock();
        try {
            room = params == null ? RoomModel.DRY : params;
            reverbEnabled = enabled;
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
                p.pitch(),
                p.occlusions(),
                p.appliedGains());
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

    /** Objetos EFX do mod vivos agora (diagnóstico de vazamento). */
    public static int liveEfxObjects() {
        return Efx.LIVE_OBJECTS.get();
    }

    /** Estado do EFX, lido do OpenAL (diagnóstico; thread principal). Null se não há EFX agora. */
    public EfxInfo efxInfo() {
        lock.lock();
        try {
            if (efx == null || !alUsable()) return null;
            RoomModel.Params pushed = efx.pushed();
            return new EfxInfo(
                efx.hasReverb(),
                efx.sendIndex,
                efx.maxSends,
                efx.effectLoaded(),
                efx.slotGainFromAl(),
                efx.decayFromAl(),
                pushed == null ? "-" : pushed.toString(),
                room.toString());
        } finally {
            lock.unlock();
        }
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
            manageEfx(now);

            boolean paused = Minecraft.getMinecraft()
                .isGamePaused();
            Iterator<Playback> it = playbacks.values()
                .iterator();
            while (it.hasNext()) {
                Playback p = it.next();
                try {
                    p.update(generation, dt, paused, now, efx);
                } catch (Throwable t) {
                    // Um erro de áudio nunca pode derrubar o jogo: encerra só esta reprodução (o controlador a
                    // recria no próximo tick). Com EFX ativo, desconfia dele: o próximo frame segue sem EFX.
                    if (efx != null) efxFailedGeneration = generation;
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

    /** Cria, atualiza ou solta o EFX conforme o que toca e o config. Thread principal, lock tomado, AL válido. */
    private void manageEfx(long now) {
        if (efx != null && efxGeneration != generation) {
            // Contexto trocado sem passar pelo gancho (fallback): os ids morreram com o contexto antigo.
            efx.release(false);
            efx = null;
        }
        boolean failed = efxFailedGeneration == generation;
        boolean allowed = (FmConfig.Client.enableOcclusion || FmConfig.Client.enableReverb) && !failed
            && !DevE2E.forceNoEfx();
        if (!allowed) {
            if (efx != null) {
                if (failed) AkashicFM.LOG.warn("AkashicFM: EFX desligado depois de um erro; oclusão só pelo ganho");
                detachAndReleaseEfx();
            }
            efxTriedGeneration = Integer.MIN_VALUE;
            efxIdle = false;
            return;
        }
        if (playbacks.isEmpty()) {
            // Nada tocando: o reverb fica como está (a cauda decai sozinha) e o EFX é solto depois de um tempo.
            if (efx == null) {
                efxTriedGeneration = Integer.MIN_VALUE;
            } else if (!efxIdle) {
                efxIdle = true;
                efxIdleSinceNanos = now;
            } else if (now - efxIdleSinceNanos > EFX_IDLE_RELEASE_NANOS) {
                efx.release(true); // sem vozes: nenhuma fonte ligada ao slot
                efx = null;
                efxIdle = false;
                efxTriedGeneration = Integer.MIN_VALUE;
            }
            return;
        }
        efxIdle = false;
        if (efx == null && efxTriedGeneration != generation) {
            efxTriedGeneration = generation;
            efx = Efx.create();
            efxGeneration = generation;
            if (efx == null) AkashicFM.LOG.info("AkashicFM: sem EFX neste dispositivo; oclusão só pelo ganho");
            else AkashicFM.LOG.info(
                "AkashicFM: EFX pronto (low-pass{}, saída auxiliar {} de {})",
                efx.hasReverb() ? " + reverb" : "",
                efx.sendIndex,
                efx.maxSends);
        }
        if (efx == null) return;
        try {
            efx.room(room, reverbEnabled && FmConfig.Client.enableReverb);
        } catch (Throwable t) {
            efxFailedGeneration = generation;
            AkashicFM.LOG.warn("AkashicFM: falha ao aplicar o reverb; EFX desligado", t);
            detachAndReleaseEfx();
        }
    }

    /** Desliga filtros e envios de todas as fontes vivas e solta os objetos EFX. */
    private void detachAndReleaseEfx() {
        Efx e = efx;
        efx = null;
        try {
            for (Playback p : playbacks.values()) p.detachEfx(e);
            e.release(true);
        } catch (Throwable t) {
            // Sem conseguir desligar das fontes, o slot pode estar em uso: as fontes vão junto (recriadas depois).
            for (Playback p : playbacks.values()) p.releaseVoices(true);
            e.release(true);
        }
    }

    /** Solta o EFX junto com o contexto ({@code alive}=false: só esquece os ids). */
    private void releaseEfx(boolean alive) {
        if (efx != null) {
            efx.release(alive);
            efx = null;
        }
        efxTriedGeneration = Integer.MIN_VALUE;
        efxIdle = false;
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
        /** Oclusão suavizada de cada voz e o AL_GAIN aplicado nela. */
        public final float[] occlusions;
        public final float[] gains;

        PlaybackInfo(boolean playing, boolean done, AudioFeed.Status feedStatus, String detail, int voices,
            long framesQueued, int underruns, int starts, int joins, int resyncs, double syncErrorMs, float pitch,
            float[] occlusions, float[] gains) {
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
            this.occlusions = occlusions;
            this.gains = gains;
        }
    }

    /** Fotografia do EFX lida do OpenAL, para diagnóstico. */
    public static final class EfxInfo {

        public final boolean reverb;
        public final int sendIndex;
        public final int maxSends;
        public final boolean effectLoaded;
        /** AL_EFFECTSLOT_GAIN lido do OpenAL (nível do reverb aplicado). */
        public final float slotGain;
        /** AL_REVERB_DECAY_TIME lido do efeito. */
        public final float decay;
        public final String pushed;
        public final String room;

        EfxInfo(boolean reverb, int sendIndex, int maxSends, boolean effectLoaded, float slotGain, float decay,
            String pushed, String room) {
            this.reverb = reverb;
            this.sendIndex = sendIndex;
            this.maxSends = maxSends;
            this.effectLoaded = effectLoaded;
            this.slotGain = slotGain;
            this.decay = decay;
            this.pushed = pushed;
            this.room = room;
        }
    }
}
