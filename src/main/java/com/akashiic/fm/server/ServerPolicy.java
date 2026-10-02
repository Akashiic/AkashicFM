package com.akashiic.fm.server;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Transport;

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

    /** Transporte para uma rádio que vai começar a tocar agora. */
    public static Transport chooseTransport() {
        if (FmConfig.Relay.enabled && relayAvailable) return Transport.RELAY;
        if (FmConfig.Direct.enabled) return Transport.DIRECT;
        return Transport.NONE;
    }
}
