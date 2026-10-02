package com.akashiic.fm.audio.spatial;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Overrides acústicos por bloco no config: {@code modid:nome=absorção} ou {@code modid:nome=absorção,amortecimento}.
 * Absorção 0..1; amortecimento 0..1, ou -1 para o bloco não contar como superfície no reverb. Função pura, testada
 * em AcousticOverridesTest.
 */
public final class AcousticOverrides {

    /** Valores de um bloco; {@code damping} é NaN quando a entrada só define a absorção. */
    public static final class Value {

        public final float absorption;
        public final float damping;

        Value(float absorption, float damping) {
            this.absorption = absorption;
            this.damping = damping;
        }

        public boolean hasDamping() {
            return !Float.isNaN(damping);
        }
    }

    private AcousticOverrides() {}

    /** Chaves em minúsculas. Entradas inválidas vão para {@code onInvalid} e ficam de fora. */
    public static Map<String, Value> parse(String[] entries, Consumer<String> onInvalid) {
        Map<String, Value> out = new HashMap<>();
        if (entries == null) return out;
        for (String raw : entries) {
            if (raw == null) continue;
            String e = raw.trim();
            if (e.isEmpty()) continue;
            Value v = parseOne(e);
            if (v == null) onInvalid.accept(raw);
            else out.put(
                e.substring(0, e.lastIndexOf('='))
                    .trim()
                    .toLowerCase(Locale.ROOT),
                v);
        }
        return out;
    }

    private static Value parseOne(String e) {
        int eq = e.lastIndexOf('=');
        if (eq <= 0) return null;
        String name = e.substring(0, eq)
            .trim();
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1 || name.indexOf(' ') >= 0) return null;
        String[] parts = e.substring(eq + 1)
            .split(",", -1);
        if (parts.length > 2) return null;
        try {
            float a = Float.parseFloat(parts[0].trim());
            float d = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : Float.NaN;
            if (!(a >= 0 && a <= 1)) return null;
            if (parts.length > 1 && !(d == -1f || (d >= 0 && d <= 1))) return null;
            return new Value(a, d);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
