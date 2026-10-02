package com.akashiic.fm.common;

/**
 * Frequências da faixa de FM, de 87,5 a 108,0 MHz em passos de 0,1, guardadas em décimos de MHz (875..1080):
 * inteiros exatos, sem erro de ponto flutuante na comparação. Funções puras, testadas em FrequencyTest.
 */
public final class Frequency {

    public static final int MIN = 875, MAX = 1080, DEFAULT = 1000;

    private Frequency() {}

    public static int clamp(int tenths) {
        return tenths < MIN ? MIN : (tenths > MAX ? MAX : tenths);
    }

    /** {@code 987} → {@code "98.7"}. */
    public static String format(int tenths) {
        int f = clamp(tenths);
        return (f / 10) + "." + (f % 10);
    }

    /**
     * Lê "98.7", "98,7" ou "98" (MHz). Devolve os décimos, ou -1 se não é um número da faixa com no máximo uma
     * casa decimal.
     */
    public static int parse(String s) {
        if (s == null) return -1;
        String t = s.trim()
            .replace(',', '.');
        if (t.isEmpty() || t.length() > 6) return -1;
        int dot = t.indexOf('.');
        String whole = dot < 0 ? t : t.substring(0, dot);
        String frac = dot < 0 ? "0" : t.substring(dot + 1);
        if (whole.isEmpty() || frac.length() != 1 || !digits(whole) || !digits(frac)) return -1;
        int tenths = Integer.parseInt(whole) * 10 + (frac.charAt(0) - '0');
        return tenths >= MIN && tenths <= MAX ? tenths : -1;
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }
}
