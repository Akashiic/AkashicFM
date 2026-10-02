package com.akashiic.fm.common;

/** Quem pode controlar uma rádio além do dono e dos ops. */
public enum RadioAccess {

    /** Só o dono e os ops controlam. Os outros veem a GUI em modo leitura. */
    PRIVATE,
    /** Qualquer jogador pode tocar, parar, trocar a URL e o volume. Configurações de dono continuam só do dono. */
    PUBLIC;

    public static RadioAccess byOrdinal(int ordinal) {
        RadioAccess[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PRIVATE;
    }
}
