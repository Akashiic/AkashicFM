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

    public URI check(String raw) throws PolicyException {
        if (raw == null || raw.isEmpty()) throw new PolicyException("URL vazia");
        if (raw.length() > MAX_URL_LENGTH) throw new PolicyException("URL maior que " + MAX_URL_LENGTH);
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (Exception e) {
            throw new PolicyException("URL inválida");
        }
        String scheme = uri.getScheme() == null ? ""
            : uri.getScheme()
                .toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new PolicyException("só http/https");
        if (uri.getRawUserInfo() != null) throw new PolicyException("credenciais na URL não são permitidas");
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new PolicyException("URL sem host");
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !(allowHighPorts && port >= 1024 && port <= 65535)) {
            throw new PolicyException("porta não permitida: " + port);
        }
        if (allowedHosts.length > 0 && !hostAllowed(host))
            throw new PolicyException("domínio fora da lista permitida: " + host);
        if (host.equals("localhost") || host.endsWith(".localhost")
            || host.endsWith(".local")
            || host.endsWith(".internal")) {
            throw new PolicyException("host interno: " + host);
        }
        return uri;
    }

    /**
     * Resolve o host e recusa se qualquer endereço for interno. O chamador deve conectar no endereço devolvido (evita
     * DNS rebinding).
     */
    public InetAddress resolvePublic(String host) throws PolicyException, UnknownHostException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress a : all) {
            if (isInternal(a)) throw new PolicyException("endereço interno: " + host + " -> " + a.getHostAddress());
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

    public static final class PolicyException extends Exception {

        public PolicyException(String msg) {
            super(msg);
        }
    }
}
