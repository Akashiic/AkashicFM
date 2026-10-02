package com.akashiic.fm.server.relay;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.audio.relay.FrameRing;
import com.akashiic.fm.audio.stream.StreamPump;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * Uma estação do relay: o servidor baixa a URL uma vez ({@link StreamPump}, com a política do servidor e o
 * DNS na thread da estação), codifica em Opus de 20 ms e guarda no {@link FrameRing}, de onde o tick manda
 * para cada ouvinte. Uma estação serve todas as rádios com a mesma URL.
 */
final class Station {

    /** Quanto o PTS pode ficar à frente do relógio (o burst inicial dos servidores vira folga). */
    static final long MAX_AHEAD_MS = 2000;
    /** Atraso da entrada a partir do qual o PTS volta para o relógio (lacuna para os clientes). */
    static final long REBASE_LAG_MS = 500;
    /** Capacidade do ring: folga à frente + latência máxima + margem. */
    static final int RING_FRAMES = 15 * 1000 / FrameRing.FRAME_MS;
    private static final int FRAME_SAMPLES = 960;

    final int id;
    final String url;
    final FrameRing ring = new FrameRing(RING_FRAMES, ServerClock::nowMs);
    private final StreamPump pump;
    private final OpusEncoder encoder;
    private final short[] frame = new short[FRAME_SAMPLES * 2];
    private int frameFill;
    private final byte[] packet = new byte[1275];
    /** Último momento (ms do servidor) em que algum ouvinte quis esta estação. Só a thread principal. */
    long lastWantedMs;

    Station(int id, String url, UrlPolicy policy, int bitrateKbps) {
        this.id = id;
        this.url = url;
        try {
            encoder = new OpusEncoder(StreamPump.SAMPLE_RATE, 2, OpusApplication.OPUS_APPLICATION_AUDIO);
            encoder.setBitrate(bitrateKbps * 1000);
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
        } catch (OpusException e) {
            throw new IllegalStateException(e);
        }
        this.pump = new StreamPump(url, policy, new StreamPump.Sink() {

            @Override
            public boolean write(short[] stereo48k, int frames) throws InterruptedException {
                return encode(stereo48k, frames);
            }

            @Override
            public void end() {
                ring.close();
            }
        }, "AkashicFM-Relay-" + id);
    }

    Station start() {
        pump.start();
        return this;
    }

    /** Thread da estação: junta blocos de 20 ms, codifica e guarda (bloqueia se o PTS adiantou demais). */
    private boolean encode(short[] pcm, int frames) throws InterruptedException {
        int p = 0, total = frames * 2;
        while (p < total) {
            int take = Math.min(total - p, frame.length - frameFill);
            System.arraycopy(pcm, p, frame, frameFill, take);
            frameFill += take;
            p += take;
            if (frameFill == frame.length) {
                frameFill = 0;
                int n;
                try {
                    n = encoder.encode(frame, 0, FRAME_SAMPLES, packet, 0, packet.length);
                } catch (OpusException e) {
                    continue; // frame perdido: o cliente preenche a lacuna
                }
                if (n > 0 && !ring.add(packet, n, MAX_AHEAD_MS, REBASE_LAG_MS, pump::isClosed)) return false;
            }
        }
        return true;
    }

    StreamPump.Status status() {
        return pump.status();
    }

    String detail() {
        return pump.detail();
    }

    String streamTitle() {
        return pump.streamTitle();
    }

    /** Terminou de vez (erro ou fim de arquivo): uma nova tentativa precisa de outra estação. */
    boolean failed() {
        StreamPump.Status s = pump.status();
        return s == StreamPump.Status.ERROR || s == StreamPump.Status.ENDED;
    }

    void close() {
        pump.close();
        ring.close();
    }
}
