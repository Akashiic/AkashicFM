package com.akashiic.fm.common;

/** Como a rádio escolhe o que tocar. */
public enum TuneMode {

    /** A URL escrita na própria rádio. */
    URL,
    /** O transmissor mais forte na frequência sintonizada (a URL é a dele). */
    FREQUENCY;

    public static TuneMode byOrdinal(int ordinal) {
        TuneMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : URL;
    }
}
