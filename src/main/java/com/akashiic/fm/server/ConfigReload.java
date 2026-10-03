package com.akashiic.fm.server;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.common.config.Configuration;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.dev.DevE2E;
import com.akashiic.fm.dev.E2EServer;
import com.gtnewhorizon.gtnhlib.config.Config;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

/**
 * {@code /fm reload}: relê do disco as categorias do config que valem no servidor e reaplica o que depende delas. As
 * do cliente ficam de fora (cada jogador tem as suas, pela tela de config).
 * <p>
 * Dois caminhos, conforme o GTNHLib instalado:
 * <ul>
 * <li><b>0.9.62 ou mais novo</b> (GTNH 2.9): o {@code reloadConfig} do GTNHLib relê o arquivo e reaplica só os campos
 * marcados com {@code @Config.Reloadable(FmConfig.RELOAD)} (sem a marca, a chamada não faz nada: o teste
 * {@code ConfigReloadTest} confere que nenhum campo do servidor ficou sem);</li>
 * <li><b>mais antigo</b> (GTNH 2.7 e 2.8, sem {@code reloadConfig} nem {@code getConfig}): o {@code Configuration} que
 * o GTNHLib guarda relê o arquivo, e um novo {@code registerConfig} reaplica os campos. Esse caminho não conhece o
 * {@code @Config.Reloadable}, então os campos que só mudam ao reiniciar voltam ao valor de antes.</li>
 * </ul>
 */
public final class ConfigReload {

    static final Class<?>[] SERVER_CONFIG = { FmConfig.Relay.class, FmConfig.Direct.class, FmConfig.Policy.class,
        FmConfig.Limits.class, FmConfig.Protection.class, FmConfig.Transmitter.class, FmConfig.Portable.class,
        FmConfig.OpenComputers.class };

    /** O GTNHLib tem o recarregamento próprio (0.9.62+). */
    static final boolean NATIVE = hasNativeReload();

    private ConfigReload() {}

    private static boolean hasNativeReload() {
        try {
            ConfigurationManager.class.getMethod("reloadConfig", Class.class, String.class);
            ConfigurationManager.class.getMethod("getConfig", Class.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Devolve as categorias que falharam (vazio = tudo certo). */
    public static List<String> reloadServerConfig() {
        List<String> failed = NATIVE ? reloadNative() : reloadLegacy();
        // O teste E2E força alguns valores na memória (transporte, alcance curto): o arquivo não os desfaz.
        if (DevE2E.enabled()) E2EServer.applyOverrides();
        // O relay desligado no config é encerrado pelo próprio RelayService no próximo tick; ligado de novo, volta.
        ServerPolicy.setRelayAvailable(FmConfig.Relay.enabled);
        return failed;
    }

    private static List<String> reloadNative() {
        List<String> failed = new ArrayList<>();
        for (Class<?> cls : SERVER_CONFIG) {
            try {
                if (ConfigurationManager.getConfig(cls) == null) throw new IllegalStateException("não registrada");
                ConfigurationManager.reloadConfig(cls, FmConfig.RELOAD);
            } catch (Exception e) {
                failed.add(category(cls));
                AkashicFM.LOG.warn("Recarregar a categoria {} do config falhou: {}", category(cls), e.toString());
            }
        }
        return failed;
    }

    private static List<String> reloadLegacy() {
        List<String> failed = new ArrayList<>();
        int latency = FmConfig.Relay.latencyTargetMs; // só muda ao reiniciar
        int reread = 0;
        try {
            for (Configuration cfg : cachedConfigs()) {
                cfg.load();
                reread++;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            AkashicFM.LOG
                .warn("Este GTNHLib não permite reler o config ({}); atualize-o para o /fm reload", e.toString());
        }
        if (reread == 0) {
            for (Class<?> cls : SERVER_CONFIG) failed.add(category(cls));
            return failed;
        }
        for (Class<?> cls : SERVER_CONFIG) {
            try {
                ConfigurationManager.registerConfig(cls);
            } catch (Exception e) {
                failed.add(category(cls));
                AkashicFM.LOG.warn("Recarregar a categoria {} do config falhou: {}", category(cls), e.toString());
            }
        }
        FmConfig.Relay.latencyTargetMs = latency;
        return failed;
    }

    /** Os {@code Configuration} do arquivo do mod guardados pelo GTNHLib (campo privado nas versões antigas). */
    private static List<Configuration> cachedConfigs() throws ReflectiveOperationException {
        Field f = ConfigurationManager.class.getDeclaredField("configs");
        f.setAccessible(true);
        List<Configuration> out = new ArrayList<>();
        for (Object o : ((Map<?, ?>) f.get(null)).values()) {
            if (!(o instanceof Configuration)) continue;
            Configuration cfg = (Configuration) o;
            if (cfg.getConfigFile() != null && cfg.getConfigFile()
                .getName()
                .equals(AkashicFM.MODID + ".cfg")) out.add(cfg);
        }
        return out;
    }

    private static String category(Class<?> cls) {
        Config c = cls.getAnnotation(Config.class);
        return c == null ? cls.getSimpleName() : c.category();
    }
}
