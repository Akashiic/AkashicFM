package com.akashiic.fm.audio.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Política de URL para fontes de áudio. Roda no servidor antes de cada conexão e
 * de novo a cada redirect: só http/https, sem credenciais na URL, portas limitadas e
 * nenhum endereço interno (anti-SSRF). A lista de domínios permitidos vem do config.
 */
public final class UrlPolicy {

    public static final int MAX_URL_LENGTH = 512;

    private final String[] allowedHosts; // vazio = qualquer host público
    private final boolean allowHighPorts;

    /**
     * @param allowedHosts   domínios permitidos (subdomínios incluídos); vazio = qualquer host público
     * @param allowHighPorts aceita portas 1024-65535 além de 80/443. Portas baixas são sempre recusadas.
     */
    public UrlPolicy(String[] allowedHosts, boolean allowHighPorts) {
        this.allowedHosts = allowedHosts == null ? new String[0] : allowedHosts.clone();
        this.allowHighPorts = allowHighPorts;
    }

    /**
     * Regras sintáticas, sem DNS (seguro na thread principal). IP literal é verificado aqui mesmo; nome de host
     * só é verificado de verdade em {@link #resolvePublic}, na hora de conectar.
     */
    public URI check(String raw) throws PolicyException {
        if (raw == null || raw.isEmpty()) throw new PolicyException("empty", "");
        if (raw.length() > MAX_URL_LENGTH) throw new PolicyException("too_long", String.valueOf(MAX_URL_LENGTH));
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (Exception e) {
            throw new PolicyException("malformed", "");
        }
        String scheme = uri.getScheme() == null ? ""
            : uri.getScheme()
                .toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new PolicyException("scheme", "");
        if (uri.getRawUserInfo() != null) throw new PolicyException("credentials", "");
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new PolicyException("no_host", "");
        host = host.toLowerCase(Locale.ROOT);
        // "localhost." é o mesmo host que "localhost": o ponto final não pode driblar as regras abaixo.
        String bare = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !(allowHighPorts && port >= 1024 && port <= 65535)) {
            throw new PolicyException("port", String.valueOf(port));
        }
        if (allowedHosts.length > 0 && !hostAllowed(bare)) throw new PolicyException("not_allowed", bare);
        if (bare.equals("localhost") || bare.endsWith(".localhost")
            || bare.endsWith(".local")
            || bare.endsWith(".internal")) {
            throw new PolicyException("internal", bare);
        }
        InetAddress literal = parseLiteral(bare);
        if (literal != null && isInternal(literal)) throw new PolicyException("internal", bare);
        return uri;
    }

    /**
     * IP literal do host, sem nunca consultar DNS: IPv6 entre colchetes ou IPv4 em qualquer forma que o Java
     * aceita (a, a.b, a.b.c, a.b.c.d, só decimal). Host feito só de dígitos e pontos que não é IPv4 válido é
     * recusado (o Java mandaria para o DNS, e nenhum domínio real é assim). Devolve null para nomes.
     */
    static InetAddress parseLiteral(String host) throws PolicyException {
        try {
            if (host.startsWith("[")) {
                if (!host.endsWith("]") || host.indexOf(':') < 0) throw new PolicyException("malformed", "");
                // Com colchetes o Java só aceita literal IPv6 e recusa o resto sem DNS.
                return InetAddress.getByName(host);
            }
            if (!host.isEmpty() && host.chars()
                .allMatch(c -> (c >= '0' && c <= '9') || c == '.')) {
                byte[] v4 = parseIpv4(host);
                if (v4 == null) throw new PolicyException("malformed", "");
                return InetAddress.getByAddress(v4);
            }
            return null;
        } catch (UnknownHostException e) {
            throw new PolicyException("malformed", "");
        }
    }

    /** Mesmas formas do {@code Inet4Address} do Java: o último campo ocupa os bytes que sobram. */
    static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) return null;
        long[] v = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 10) return null;
            v[i] = Long.parseLong(p);
        }
        long last = v[v.length - 1];
        long lastMax = (1L << (8 * (5 - v.length))) - 1;
        if (last < 0 || last > lastMax) return null;
        long value = last;
        for (int i = 0; i < v.length - 1; i++) {
            if (v[i] > 255) return null;
            value |= v[i] << (8 * (3 - i));
        }
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    /**
     * Resolve o host e recusa se qualquer endereço for interno. O chamador deve conectar no endereço devolvido (evita
     * DNS rebinding).
     */
    public InetAddress resolvePublic(String host) throws PolicyException, UnknownHostException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress a : all) {
            if (isInternal(a)) throw new PolicyException("internal", host + " -> " + a.getHostAddress());
        }
        return all[0];
    }

    private boolean hostAllowed(String host) {
        for (String allowed : allowedHosts) {
            String a = allowed.toLowerCase(Locale.ROOT);
            if (host.equals(a) || host.endsWith("." + a)) return true;
        }
        return false;
    }

    public static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress()
            || a.isLinkLocalAddress()
            || a.isSiteLocalAddress()
            || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            if (b0 == 0) return true; // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // 100.64.0.0/10 (CGNAT)
            if (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0) return true; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true; // 198.18.0.0/15
            if (b0 >= 240) return true; // reservado + broadcast
        } else if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return true; // fc00::/7 (ULA)
            boolean v4mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) v4mapped = false;
            if (v4mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return isInternal(InetAddress.getByAddress(new byte[] { b[12], b[13], b[14], b[15] }));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Recusa da política. {@link #code} é estável e vira a chave de tradução {@code akashicfm.policy.<code>}. */
    public static final class PolicyException extends Exception {

        public final String code;
        /** Host, porta ou limite envolvido (pode ser vazio). */
        public final String detail;

        public PolicyException(String code, String detail) {
            super(detail == null || detail.isEmpty() ? code : code + ": " + detail);
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }

        public String translationKey() {
            return "akashicfm.policy." + code;
        }
    }
}
