package com.akashiic.fm.common;

import com.akashiic.fm.audio.http.UrlPolicy;

/**
 * Limites rígidos do estado de uma rádio. Valem mesmo com config mal preenchido ou NBT adulterado:
 * nada fora destas faixas é aceito, guardado ou sincronizado.
 */
public final class RadioLimits {

    private RadioLimits() {}

    public static final int MAX_URL_LENGTH = UrlPolicy.MAX_URL_LENGTH;
    public static final int MAX_STATIONS = 16;
    public static final int MAX_SCREEN_TEXT = 32;
    public static final int MAX_OWNER_NAME = 16;
    public static final int MAX_TITLE_LENGTH = 128;
    public static final int MAX_STATUS_LENGTH = 96;

    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 100;
    public static final int VOLUME_DEFAULT = 60;

    public static final int RANGE_MIN = 4;
    public static final int RANGE_HARD_MAX = 128;
    public static final int RANGE_DEFAULT = 24;

    public static final int MAX_SPEAKERS_HARD = 64;
    public static final int SCREEN_COLOR_DEFAULT = 0x40FF60;

    public static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
