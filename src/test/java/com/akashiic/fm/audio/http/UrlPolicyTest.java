package com.akashiic.fm.audio.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Só usa IPs literais e hosts recusados antes do DNS: nada aqui depende de rede. */
class UrlPolicyTest {

    private final UrlPolicy policy = new UrlPolicy(new String[0], true);

    private void checkAndResolve(UrlPolicy p, String url) throws Exception {
        URI uri = p.check(url);
        p.resolvePublic(uri.getHost());
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "http://127.0.0.1/stream", "http://localhost:8000/", "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/", "http://192.168.1.1/admin", "http://172.16.3.4/", "http://100.64.1.1/", "http://[::1]/",
            "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/", "http://2130706433/", "http://0.0.0.0/",
            "http://255.255.255.255/", "http://198.18.0.1/", "file:///etc/passwd", "ftp://example.com/a.mp3",
            "http://user:pass@example.com/", "http://example.com:22/", "http://router.local/", "http://db.internal/",
            "javascript:alert(1)", "" })
    void recusaEnderecosInternosEEsquemasInvalidos(String url) {
        assertThrows(Exception.class, () -> checkAndResolve(policy, url));
    }

    @Test
    void aceitaIpPublicoNasPortasPermitidas() {
        assertDoesNotThrow(() -> checkAndResolve(policy, "http://1.1.1.1/stream"));
        assertDoesNotThrow(() -> checkAndResolve(policy, "https://8.8.8.8:443/live"));
        assertDoesNotThrow(() -> checkAndResolve(policy, "http://1.1.1.1:8000/radio.mp3"));
    }

    @Test
    void portasAltasSoComPermissao() {
        UrlPolicy soPadrao = new UrlPolicy(new String[0], false);
        assertThrows(UrlPolicy.PolicyException.class, () -> soPadrao.check("http://1.1.1.1:8000/radio.mp3"));
        assertDoesNotThrow(() -> soPadrao.check("http://1.1.1.1:80/radio.mp3"));
    }

    @Test
    void urlGrandeDemaisERecusada() {
        StringBuilder sb = new StringBuilder("http://1.1.1.1/");
        while (sb.length() <= UrlPolicy.MAX_URL_LENGTH) sb.append('a');
        assertThrows(UrlPolicy.PolicyException.class, () -> policy.check(sb.toString()));
    }

    @Test
    void allowlistAceitaSubdominioERecusaParecido() {
        UrlPolicy allow = new UrlPolicy(new String[] { "radioparadise.com" }, true);
        assertDoesNotThrow(() -> allow.check("https://stream.radioparadise.com/mp3-128"));
        assertDoesNotThrow(() -> allow.check("https://radioparadise.com/mp3-128"));
        assertThrows(UrlPolicy.PolicyException.class, () -> allow.check("https://evil-radioparadise.com/x"));
        assertThrows(UrlPolicy.PolicyException.class, () -> allow.check("https://radioparadise.com.evil.net/x"));
    }
}
