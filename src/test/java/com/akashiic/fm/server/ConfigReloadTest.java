package com.akashiic.fm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.FmConfig;
import com.gtnewhorizon.gtnhlib.config.Config;

/**
 * O GTNHLib só recarrega campos com {@code @Config.Reloadable}: sem a marca, o {@code /fm reload} não muda o campo e
 * não avisa. Este teste pega o campo novo do servidor que esquecer dela.
 */
class ConfigReloadTest {

    /** Não podem mudar ao vivo (documentado no comentário do campo). */
    private static final Set<String> RESTART_ONLY = new HashSet<>(Arrays.asList("Relay.latencyTargetMs"));

    @Test
    void todoCampoDoServidorEhRecarregavel() {
        int checked = 0;
        for (Class<?> cls : ConfigReload.SERVER_CONFIG) {
            assertNotNull(cls.getAnnotation(Config.class), cls + " sem @Config");
            for (Field f : cls.getDeclaredFields()) {
                int m = f.getModifiers();
                if (!Modifier.isPublic(m) || !Modifier.isStatic(m) || Modifier.isFinal(m)) continue;
                String name = cls.getSimpleName() + "." + f.getName();
                Config.Reloadable r = f.getAnnotation(Config.Reloadable.class);
                if (RESTART_ONLY.contains(name)) {
                    assertNull(r, name + " não pode ser recarregável");
                } else {
                    assertNotNull(r, name + " sem @Config.Reloadable: o /fm reload o ignoraria");
                    assertEquals(FmConfig.RELOAD, r.value(), name);
                }
                checked++;
            }
        }
        assertTrue(checked >= 28, "campos conferidos: " + checked);
    }

    @Test
    void cobreTodasAsCategoriasDoServidor() {
        Set<Class<?>> listed = new HashSet<>(Arrays.asList(ConfigReload.SERVER_CONFIG));
        for (Class<?> cls : FmConfig.class.getDeclaredClasses()) {
            if (cls.getAnnotation(Config.class) == null) continue;
            // Cliente: cada jogador tem o seu. Receitas: só valem ao reiniciar o jogo.
            boolean serverSide = cls != FmConfig.Client.class && cls != FmConfig.Recipes.class;
            assertEquals(serverSide, listed.contains(cls), cls.getSimpleName());
        }
    }
}
