package com.akashiic.fm.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;

/**
 * Hora de chegada dos pings/pongs de sincronia na camada de rede.
 * <p>
 * No 1.7.10 os pacotes de mod vão para a fila do vanilla e os handlers só rodam no tick seguinte da thread
 * principal (0 a 50 ms depois). Carimbar o relógio no handler enviesaria o offset em até ±25 ms. Um mixin em
 * {@code NetworkManager.channelRead0} chama {@link #onArrival} na thread do netty, assim que o pacote chega:
 * aqui só se lê o discriminador e o t0 (sem consumir o buffer) e se anota a hora. O handler depois busca.
 */
public final class ClockStamps {

    static final byte PING_DISCRIMINATOR = 5;
    static final byte PONG_DISCRIMINATOR = 6;
    private static final int MAX_ENTRIES = 4096;

    private static final class Key {

        final int connection;
        final long t0;

        Key(Object connection, long t0) {
            this.connection = System.identityHashCode(connection);
            this.t0 = t0;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).connection == connection && ((Key) o).t0 == t0;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(t0) * 31 + connection;
        }
    }

    private static final Map<Key, Long> ARRIVALS = new ConcurrentHashMap<>();

    private ClockStamps() {}

    /** Thread do netty (via mixin). Não pode lançar nem mexer no estado do pacote. */
    public static void onArrival(Object connection, FMLProxyPacket packet) {
        try {
            if (!FmNetwork.CHANNEL_NAME.equals(packet.channel())) return;
            ByteBuf b = packet.payload();
            int i = b.readerIndex();
            if (b.readableBytes() < 9) return;
            byte d = b.getByte(i);
            if (d != PING_DISCRIMINATOR && d != PONG_DISCRIMINATOR) return;
            if (ARRIVALS.size() > MAX_ENTRIES) ARRIVALS.clear(); // só se algo não for consumido (ex.: conexão caiu)
            ARRIVALS.put(new Key(connection, b.getLong(i + 1)), System.nanoTime() / 1000);
        } catch (RuntimeException ignored) {
            // diagnóstico de relógio nunca pode atrapalhar a rede
        }
    }

    /** Hora de chegada (µs) do ping/pong com {@code t0} nesta conexão, ou {@code fallback} se não houver. */
    public static long take(Object connection, long t0, long fallback) {
        if (connection == null) return fallback;
        Long v = ARRIVALS.remove(new Key(connection, t0));
        return v == null ? fallback : v;
    }
}
