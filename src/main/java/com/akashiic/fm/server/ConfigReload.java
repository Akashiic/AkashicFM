package com.akashiic.fm.server;

import java.util.ArrayList;
import java.util.List;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.dev.DevE2E;
import com.akashiic.fm.dev.E2EServer;
import com.gtnewhorizon.gtnhlib.config.Config;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

/**
 * {@code /fm reload}: relê do disco as categorias do config que valem no servidor e reaplica o que depende delas. O
 * GTNHLib recarrega o arquivo e reaplica só os campos marcados com {@code @Config.Reloadable(FmConfig.RELOAD)} (sem a
 * marca, a chamada não faz nada: o teste {@code ConfigReloadTest} confere que nenhum campo do servidor ficou sem). As
 * do cliente ficam de fora (cada jogador tem as suas, pela tela de config).
 */
public final class ConfigReload {

    static final Class<?>[] SERVER_CONFIG = { FmConfig.Relay.class, FmConfig.Direct.class, FmConfig.Policy.class,
        FmConfig.Limits.class, FmConfig.Protection.class, FmConfig.Transmitter.class, FmConfig.Portable.class };

    private ConfigReload() {}

    /** Devolve as categorias que falharam (vazio = tudo certo). */
    public static List<String> reloadServerConfig() {
        List<String> failed = new ArrayList<>();
        for (Class<?> cls : SERVER_CONFIG) {
            Config c = cls.getAnnotation(Config.class);
            String category = c == null ? cls.getSimpleName() : c.category();
            try {
                if (ConfigurationManager.getConfig(cls) == null) throw new IllegalStateException("não registrada");
                ConfigurationManager.reloadConfig(cls, FmConfig.RELOAD);
            } catch (Exception e) {
                failed.add(category);
                AkashicFM.LOG.warn("Recarregar a categoria {} do config falhou: {}", category, e.toString());
            }
        }
        // O teste E2E força alguns valores na memória (transporte, alcance curto): o arquivo não os desfaz.
        if (DevE2E.enabled()) E2EServer.applyOverrides();
        // O relay desligado no config é encerrado pelo próprio RelayService no próximo tick; ligado de novo, volta.
        ServerPolicy.setRelayAvailable(FmConfig.Relay.enabled);
        return failed;
    }
}
