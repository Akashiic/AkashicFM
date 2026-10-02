package com.akashiic.fm.audio.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** IP literal interno é recusado já no check (sem DNS), não só na hora de conectar. */
    @ParameterizedTest
    @ValueSource(
        strings = { "http://127.0.0.1/stream", "http://2130706433/", "http://10.1.2.3:8000/", "http://192.168.0.1/",
            "http://169.254.169.254/latest/", "http://[::1]/", "http://[fd00::1]/", "http://[fe80::1]/",
            "http://[::ffff:10.0.0.1]/", "http://0.0.0.0/", "http://localhost./", "http://LOCALHOST/",
            "http://x.localhost./", "http://255.255.255.255/" })
    void checkRecusaLiteralInternoSemDns(String url) {
        UrlPolicy.PolicyException e = assertThrows(UrlPolicy.PolicyException.class, () -> policy.check(url));
        assertEquals("internal", e.code);
        assertEquals("akashicfm.policy.internal", e.translationKey());
    }

    /** Número único que não cabe em IPv4: o URI do Java aceita como host, a política recusa sem ir ao DNS. */
    @ParameterizedTest
    @ValueSource(strings = { "http://99999999999/", "http://4294967296/" })
    void hostSoDeDigitosQueNaoEIpv4ERecusado(String url) {
        UrlPolicy.PolicyException e = assertThrows(UrlPolicy.PolicyException.class, () -> policy.check(url));
        assertEquals("malformed", e.code);
    }

    /** Formas numéricas estranhas: o URI do Java já não reconhece o host (rótulo final numérico). */
    @ParameterizedTest
    @ValueSource(
        strings = { "http://127.1/", "http://999.1.1.1/", "http://1..2/", "http://1.2.3.4.5/", "http://256.1.1.1/",
            "http://1.16777216/" })
    void formasNumericasEstranhasSaoRecusadas(String url) {
        assertThrows(UrlPolicy.PolicyException.class, () -> policy.check(url));
    }

    @Test
    void codigosDosMotivos() {
        assertEquals("scheme", assertThrows(UrlPolicy.PolicyException.class, () -> policy.check("ftp://a.com/")).code);
        assertEquals(
            "credentials",
            assertThrows(UrlPolicy.PolicyException.class, () -> policy.check("http://u:p@a.com/")).code);
        UrlPolicy.PolicyException port = assertThrows(
            UrlPolicy.PolicyException.class,
            () -> policy.check("http://a.com:22/"));
        assertEquals("port", port.code);
        assertEquals("22", port.detail);
        assertEquals("empty", assertThrows(UrlPolicy.PolicyException.class, () -> policy.check("")).code);
    }

    @Test
    void formasIpv4IguaisAsDoJava() throws Exception {
        assertArrayEquals(new byte[] { 127, 0, 0, 1 }, UrlPolicy.parseIpv4("127.0.0.1"));
        assertArrayEquals(new byte[] { 127, 0, 0, 1 }, UrlPolicy.parseIpv4("127.1"));
        assertArrayEquals(new byte[] { 127, 0, 0, 1 }, UrlPolicy.parseIpv4("127.0.1"));
        assertArrayEquals(new byte[] { 127, 0, 0, 1 }, UrlPolicy.parseIpv4("2130706433"));
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, UrlPolicy.parseIpv4("1.2.3.4"));
        assertNull(UrlPolicy.parseIpv4("1.2.3.256"));
        assertNull(UrlPolicy.parseIpv4("4294967296"));
        // Confere contra o próprio Java (literal: sem DNS).
        for (String s : new String[] { "127.1", "10.65535", "1.2.3", "3232235777", "8.8.8.8" }) {
            assertArrayEquals(
                java.net.InetAddress.getByName(s)
                    .getAddress(),
                UrlPolicy.parseIpv4(s),
                s);
        }
    }

    @Test
    void nomesEIpsPublicosPassamNoCheck() {
        assertDoesNotThrow(() -> policy.check("http://1.1.1.1/stream"));
        assertDoesNotThrow(() -> policy.check("https://stream.example.com:8443/live.mp3"));
        assertDoesNotThrow(() -> policy.check("http://[2606:4700:4700::1111]/x"));
        assertDoesNotThrow(() -> policy.check("http://example.com./x"));
    }
}
