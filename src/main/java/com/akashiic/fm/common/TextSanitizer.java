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
            if (c == '§') {
                // Código de formatação inteiro sai (como no vanilla): "§kX" vira "X", não "kX".
                if (i + 1 < raw.length() && isFormatCode(raw.charAt(i + 1))) i++;
                continue;
            }
            if (Character.isISOControl(c) || isInvisibleFormat(c)) continue;
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

    /** Letras válidas depois do '§' (cores 0-9a-f, estilos k-o, reset r). */
    static boolean isFormatCode(char c) {
        char l = Character.toLowerCase(c);
        return (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f') || (l >= 'k' && l <= 'o') || l == 'r';
    }

    /**
     * Caracteres de formatação invisíveis (categoria Cf: zero-width, inversão de direção U+202E etc.), que
     * deixariam texto enganoso na tela ou URLs que parecem iguais e não são. Surrogates não entram aqui.
     */
    static boolean isInvisibleFormat(char c) {
        return Character.getType(c) == Character.FORMAT;
    }

    /**
     * URL: sem espaços, sem controle nem formatação invisível, tamanho limitado. Validação de verdade fica na
     * UrlPolicy.
     */
    public static String cleanUrl(String raw, int maxLength) {
        if (raw == null) return "";
        String s = raw.trim();
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxLength));
        for (int i = 0; i < s.length() && sb.length() < maxLength; i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || isInvisibleFormat(c)) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
