package com.akashiic.fm.common;

/** Qual parte do sinal estéreo uma caixa de som reproduz. A ordem é gravada no NBT: só acrescente no fim. */
public enum SpeakerChannel {

    /** Mistura L+R numa fonte só (mono). */
    MIX,
    /** Só o canal esquerdo. */
    LEFT,
    /** Só o canal direito. */
    RIGHT,
    /** Duas fontes afastadas na largura do bloco: L à esquerda e R à direita de quem olha a frente. */
    STEREO;

    public static SpeakerChannel byOrdinal(int ordinal) {
        SpeakerChannel[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MIX;
    }

    public SpeakerChannel next() {
        SpeakerChannel[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
