package com.akashiic.fm.client.audio;

/**
 * Origem do PCM de uma reprodução: 48 kHz, estéreo, 16-bit, intercalado. A thread principal consome;
 * quem produz (decoder em worker) fica do outro lado.
 */
public interface AudioFeed {

    int SAMPLE_RATE = 48000;

    enum Status {
        CONNECTING,
        RECONNECTING,
        PLAYING,
        ENDED,
        ERROR
    }

    /** Frames disponíveis para leitura agora. */
    int available();

    /** Lê até {@code frames} frames para {@code dst}. Nunca bloqueia. */
    int read(short[] dst, int frames);

    /** Não vai chegar mais nada (fim do arquivo ou erro definitivo) e o que havia já foi lido. */
    boolean isFinished();

    /** Não vai chegar mais nada, mas ainda pode haver PCM guardado para ler (último pedaço parcial). */
    boolean producerDone();

    Status status();

    /** Detalhe legível do status (ex.: mensagem de erro), ou vazio. */
    String statusDetail();

    /** Para tudo e libera rede/threads. Idempotente e seguro de chamar de qualquer thread. */
    void close();

    // ---- Feeds com relógio (relay): o PCM tem PTS no tempo do servidor e a reprodução sincroniza por ele ----

    default boolean timed() {
        return false;
    }

    /** PTS (ms, relógio do servidor) do próximo frame a ser lido, ou NaN. */
    default double ptsAtReadPosition() {
        return Double.NaN;
    }

    /** Frames até a próxima descontinuidade do PTS. */
    default int framesUntilDiscontinuity() {
        return Integer.MAX_VALUE;
    }

    /** Descarta até {@code frames} frames sem ler. Devolve quantos descartou. */
    default int skip(int frames) {
        return 0;
    }

    /** Atraso fixo entre o PTS e a reprodução, igual para todos os clientes (ms). */
    default int latencyMs() {
        return 0;
    }
}
