package com.akashiic.fm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextSanitizerTest {

    @Test
    void tiraCodigosDeFormatacaoEControle() {
        assertEquals("abc", TextSanitizer.clean("§ka§lb\u0007c", 32));
        assertEquals("ab", TextSanitizer.clean("a\nb", 32));
    }

    @Test
    void tiraFormatacaoInvisivel() {
        // U+202E inverte a direção do texto; U+200B é espaço de largura zero.
        assertEquals("admin", TextSanitizer.clean("ad‮mi​n", 32));
        assertEquals("http://a.com/", TextSanitizer.cleanUrl("http://a​.com/", 512));
    }

    @Test
    void cortaSemPartirParSurrogate() {
        String emoji = "🎵"; // nota musical (2 chars)
        assertEquals("abc", TextSanitizer.clean("abc" + emoji, 4)); // o par não cabe: sai inteiro
        assertEquals("ab" + emoji, TextSanitizer.clean("ab" + emoji + "z", 4));
        assertEquals("ab", TextSanitizer.clean("a\uDC00b", 8)); // surrogate solto
        assertEquals("a", TextSanitizer.clean("a\uD800", 8));
    }

    @Test
    void nuloViraVazio() {
        assertEquals("", TextSanitizer.clean(null, 5));
        assertEquals("", TextSanitizer.cleanUrl(null, 5));
    }

    @Test
    void urlSemEspacos() {
        assertEquals("http://a.com/x", TextSanitizer.cleanUrl("  http://a.com/ x \r\n", 512));
        assertEquals("http", TextSanitizer.cleanUrl("http://a.com", 4));
    }
}
