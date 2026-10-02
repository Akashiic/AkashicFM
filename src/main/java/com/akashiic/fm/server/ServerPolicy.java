package com.akashiic.fm.server;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.server.relay.RelayService;

/** Regras do servidor derivadas do config. */
public final class ServerPolicy {

    private ServerPolicy() {}

    /** Ligado pelo serviço de relay quando ele está pronto para receber estações (Fase 3). */
    private static volatile boolean relayAvailable;

    public static void setRelayAvailable(boolean available) {
        relayAvailable = available;
    }

    public static boolean isRelayAvailable() {
        return relayAvailable;
    }

    /** Política de URL do servidor (allowlist e portas do config). Sem DNS: o DNS fica para quem conecta. */
    public static UrlPolicy urlPolicy() {
        return new UrlPolicy(FmConfig.Policy.allowedHosts, FmConfig.Policy.allowHighPorts);
    }

    /** Motivo pelo qual a política atual recusa a URL, ou null se ela passa. Sem DNS. */
    public static UrlPolicy.PolicyException rejection(String url) {
        try {
            urlPolicy().check(url);
            return null;
        } catch (UrlPolicy.PolicyException e) {
            return e;
        }
    }

    /**
     * Transporte para uma rádio tocar {@code url}: o relay se está ligado, disponível e a URL cabe no limite de
     * estações; senão o modo direto, se o admin permite; senão nenhum.
     */
    public static Transport chooseTransport(String url) {
        if (FmConfig.Relay.enabled && relayAvailable && RelayService.canRelay(url)) return Transport.RELAY;
        if (FmConfig.Direct.enabled) return Transport.DIRECT;
        return Transport.NONE;
    }
}
