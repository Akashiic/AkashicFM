package com.akashiic.fm.client.audio;

import com.akashiic.fm.audio.dsp.PcmRing;
import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.audio.stream.StreamPump;

/**
 * Modo direto: este cliente baixa e decodifica o stream sozinho ({@link StreamPump}) e entrega PCM 48 kHz
 * estéreo num {@link PcmRing}. {@link #close()} derruba o socket, então a thread sai mesmo presa num read().
 */
public final class DirectFeed implements AudioFeed {

    private static final int RING_SECONDS = 8;
    /** Política do cliente: sem allowlist (o servidor já aplicou a dele), mas nunca endereço interno. */
    private static final UrlPolicy CLIENT_POLICY = new UrlPolicy(new String[0], true);

    private final PcmRing ring = new PcmRing(SAMPLE_RATE * RING_SECONDS);
    private final StreamPump pump;

    public DirectFeed(String url, String threadName) {
        this.pump = new StreamPump(url, CLIENT_POLICY, new StreamPump.Sink() {

            @Override
            public boolean write(short[] stereo48k, int frames) throws InterruptedException {
                return ring.write(stereo48k, frames, pump()::isClosed);
            }

            @Override
            public void end() {
                ring.close();
            }
        }, threadName);
    }

    private StreamPump pump() {
        return pump;
    }

    public DirectFeed start() {
        pump.start();
        return this;
    }

    @Override
    public int available() {
        return ring.available();
    }

    @Override
    public int read(short[] dst, int frames) {
        return ring.read(dst, frames);
    }

    @Override
    public boolean isFinished() {
        return ring.isFinished();
    }

    @Override
    public boolean producerDone() {
        return ring.isClosed();
    }

    @Override
    public Status status() {
        return Status.valueOf(
            pump.status()
                .name());
    }

    @Override
    public String statusDetail() {
        return pump.detail();
    }

    @Override
    public void close() {
        pump.close();
        ring.close();
    }
}
