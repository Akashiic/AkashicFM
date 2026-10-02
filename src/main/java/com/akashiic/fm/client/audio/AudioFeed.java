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
}
