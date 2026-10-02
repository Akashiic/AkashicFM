package com.akashiic.fm.common;

/** Por onde o áudio de uma rádio chega aos jogadores. Decidido pelo servidor quando a rádio começa a tocar. */
public enum Transport {

    /** Nenhum transporte disponível (relay e modo direto desligados no servidor). */
    NONE,
    /** O servidor baixa o stream uma vez e retransmite em Opus para quem está no alcance. */
    RELAY,
    /** Cada cliente baixa o stream sozinho. */
    DIRECT;

    public static Transport byOrdinal(int ordinal) {
        Transport[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
    }
}
