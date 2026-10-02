package com.akashiic.fm.dev;

import net.minecraft.launchwrapper.Launch;

import com.akashiic.fm.AkashicFM;

/**
 * Testes de ponta a ponta roteirizados (servidor + clientes reais). Só liga com a variável de ambiente
 * {@code AKASHICFM_E2E} <b>e</b> no ambiente de desenvolvimento (deobfuscado): num jar de produção nada
 * disto roda, nem que alguém defina a variável.
 */
public final class DevE2E {

    /** Alcances do transmissor no servidor de teste: o teste de antenas cabe perto da rádio (chunk carregado). */
    public static final int TRANSMITTER_BASE_RANGE = 16, TRANSMITTER_RANGE_PER_ANTENNA = 16;

    /** Cenário pedido (ex.: "main", "main+peer", "peer"), ou null se desligado. */
    public static final String SCENARIO = resolve();

    private DevE2E() {}

    private static String resolve() {
        String s = System.getenv("AKASHICFM_E2E");
        if (s == null || s.isEmpty()) return null;
        Object deobf = Launch.blackboard == null ? null : Launch.blackboard.get("fml.deobfuscatedEnvironment");
        if (!Boolean.TRUE.equals(deobf)) {
            AkashicFM.LOG.warn("AKASHICFM_E2E ignorado: só funciona no ambiente de desenvolvimento");
            return null;
        }
        return s;
    }

    /** Transporte testado: "relay" (padrão) ou "direct" (variável AKASHICFM_E2E_TRANSPORT). */
    public static String transport() {
        String t = System.getenv("AKASHICFM_E2E_TRANSPORT");
        return "direct".equals(t) ? "direct" : "relay";
    }

    /** E2E do caminho sem EFX (variável AKASHICFM_E2E_NO_EFX=1): a oclusão fica só no ganho. */
    public static boolean forceNoEfx() {
        return enabled() && "1".equals(System.getenv("AKASHICFM_E2E_NO_EFX"));
    }

    public static boolean enabled() {
        return SCENARIO != null;
    }

    public static void log(String message, Object... args) {
        AkashicFM.LOG.info("[E2E] " + message, args);
    }
}
