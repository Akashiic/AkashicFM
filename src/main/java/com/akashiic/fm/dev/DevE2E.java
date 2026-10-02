package com.akashiic.fm.dev;

import net.minecraft.launchwrapper.Launch;

import com.akashiic.fm.AkashicFM;

/**
 * Testes de ponta a ponta roteirizados (servidor + clientes reais). Só liga com a variável de ambiente
 * {@code AKASHICFM_E2E} <b>e</b> no ambiente de desenvolvimento (deobfuscado): num jar de produção nada
 * disto roda, nem que alguém defina a variável.
 */
public final class DevE2E {

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

    public static boolean enabled() {
        return SCENARIO != null;
    }

    public static void log(String message, Object... args) {
        AkashicFM.LOG.info("[E2E] " + message, args);
    }
}
