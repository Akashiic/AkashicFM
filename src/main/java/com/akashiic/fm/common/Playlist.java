package com.akashiic.fm.common;

import java.util.List;

/**
 * Playlist das favoritas: quando a estação de uma rádio com playlist termina (arquivo) ou falha, ela passa para a
 * próxima favorita, em loop. Funções puras, testadas em PlaylistTest; quem aplica é o servidor.
 */
public final class Playlist {

    /** O que fazer com a rádio neste ciclo. */
    public enum Decision {
        /** Nada: tocando, ainda soando o fim, ou cedo demais desde a última troca. */
        WAIT,
        /** Ir para a próxima favorita. */
        ADVANCE,
        /** Parar: todas as favoritas falharam em seguida. */
        STOP
    }

    /** Intervalo mínimo entre duas trocas da mesma rádio (uma lista de URLs quebradas não vira um loop rápido). */
    public static final long MIN_INTERVAL_MS = 5000;

    private Playlist() {}

    /**
     * A favorita depois de {@code current} (em loop); a primeira se {@code current} não está na lista; null se vazia.
     */
    public static String next(List<String> stations, String current) {
        if (stations == null || stations.isEmpty()) return null;
        int i = current == null ? -1 : stations.indexOf(current);
        return stations.get((i + 1) % stations.size());
    }

    /**
     * @param ended        a estação terminou o arquivo
     * @param failed       a estação falhou de vez
     * @param endHeardAtMs quando o último áudio do arquivo termina de soar nos clientes (PTS final + latência)
     * @param nowMs        agora (relógio do servidor)
     * @param lastChangeMs última troca desta rádio pela playlist (ou 0)
     * @param failures     falhas seguidas até agora
     * @param stationCount favoritas na lista
     */
    public static Decision decide(boolean ended, boolean failed, long endHeardAtMs, long nowMs, long lastChangeMs,
        int failures, int stationCount) {
        if (stationCount <= 0 || (!ended && !failed)) return Decision.WAIT;
        if (failed && failures + 1 >= stationCount) return Decision.STOP;
        if (nowMs - lastChangeMs < MIN_INTERVAL_MS) return Decision.WAIT;
        // Fim de arquivo: espera o que já foi mandado acabar de tocar (senão corta os últimos segundos da faixa).
        if (ended && nowMs < endHeardAtMs) return Decision.WAIT;
        return Decision.ADVANCE;
    }
}
