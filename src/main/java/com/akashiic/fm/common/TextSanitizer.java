package com.akashiic.fm.common;

/** Limpa texto vindo do jogador ou de NBT antes de guardar, sincronizar ou desenhar. */
public final class TextSanitizer {

    private TextSanitizer() {}

    /**
     * Remove caracteres de controle e o '§' (códigos de formatação do Minecraft, que permitiriam texto
     * invisível ou ofuscado na tela da rádio) e corta no tamanho máximo, sem partir pares surrogate.
     */
    public static String clean(String raw, int maxLength) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && sb.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if (c == '§' || Character.isISOControl(c)) continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))
                    && sb.length() + 2 <= maxLength) {
                    sb.append(c)
                        .append(raw.charAt(++i));
                } else if (i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
                    i++; // par não cabe: descarta os dois
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue; // surrogate solto
            sb.append(c);
        }
        return sb.toString()
            .trim();
    }

    /** URL: sem espaços nas pontas, sem controle, tamanho limitado. Validação de verdade fica na UrlPolicy. */
    public static String cleanUrl(String raw, int maxLength) {
        if (raw == null) return "";
        String s = raw.trim();
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxLength));
        for (int i = 0; i < s.length() && sb.length() < maxLength; i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
