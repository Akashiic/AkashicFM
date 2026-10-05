package com.akashiic.fm.common;

/** Como a rádio escolhe o que tocar. */
public enum TuneMode {

    /** A URL escrita na própria rádio. */
    URL,
    /** O transmissor mais forte na frequência sintonizada (a URL é a dele). */
    FREQUENCY,
    /**
     * O bloco do iPod: toca a fila dele pela estação do relay com a chave do bloco (escrita pelo servidor). Só o
     * bloco usa; nunca vem de um pedido do cliente nem do item ({@link #tunable}).
     */
    IPOD;

    public static TuneMode byOrdinal(int ordinal) {
        TuneMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : URL;
    }

    /** Um modo que o jogador (ou um item) pode escolher: URL ou frequência; o resto vira URL. */
    public static TuneMode tunable(int ordinal) {
        TuneMode m = byOrdinal(ordinal);
        return m == FREQUENCY ? FREQUENCY : URL;
    }
}
