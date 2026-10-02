package com.akashiic.fm.common;

/** Como a rádio reage a sinal de redstone. */
public enum RedstoneMode {

    /** Ignora redstone. */
    IGNORE,
    /** Toca enquanto estiver energizada e para quando o sinal some. */
    WHILE_POWERED,
    /** Cada pulso (borda de subida) alterna entre tocar e parar. */
    TOGGLE_ON_PULSE;

    public static RedstoneMode byOrdinal(int ordinal) {
        RedstoneMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IGNORE;
    }

    public RedstoneMode next() {
        RedstoneMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
