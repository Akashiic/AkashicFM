package com.akashiic.fm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.versioning.ArtifactVersion;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.common.versioning.VersionParser;

/**
 * A dependência do GTNHLib, lida como o FML lê. Uma faixa malformada derrubaria o carregamento, e uma faixa errada
 * recusaria o GTNHLib de um pack suportado. E o FML só usa a anotação se o mcmod.info não pedir o contrário: com
 * {@code useDependencyInformation} ligado, ele usa as listas do mcmod.info e ignora a faixa (o teste com os jars de
 * produção pegou isso: o servidor subiu com um GTNHLib abaixo do mínimo).
 */
class ModDependencyTest {

    private static ArtifactVersion gtnhlibRange() {
        String deps = AkashicFM.class.getAnnotation(Mod.class)
            .dependencies();
        for (String dep : deps.split(";")) {
            String[] parts = dep.split(":", 2);
            if (parts[0].equals("required-after") && parts[1].startsWith("gtnhlib@")) {
                return VersionParser.parseVersionReference(parts[1]);
            }
        }
        return null;
    }

    private static boolean accepts(ArtifactVersion range, String version) {
        return range.containsVersion(new DefaultArtifactVersion("gtnhlib", version));
    }

    @Test
    void gtnhlibTemVersaoMinima() {
        ArtifactVersion range = gtnhlibRange();
        assertNotNull(range, "dependência required-after:gtnhlib@<faixa> ausente");
        assertTrue(accepts(range, AkashicFM.MIN_GTNHLIB));
        assertTrue(accepts(range, "0.5.23")); // GTNH 2.7.4
        assertTrue(accepts(range, "0.7.10")); // GTNH 2.8.4
        assertTrue(accepts(range, "0.11.52")); // GTNH 2.9 e o build
        assertTrue(accepts(range, "1.0.0"));
        assertFalse(accepts(range, "0.5.22"));
        assertFalse(accepts(range, "0.5.15")); // sem o construtor da tela de config
    }

    @Test
    void mcmodInfoNaoSobrepoeAAnotacao() throws IOException {
        // O arquivo do projeto: pelo classpath viria o mcmod.info de qualquer outro jar. Formato 2, com "modList".
        try (Reader in = Files.newBufferedReader(Paths.get("src/main/resources/mcmod.info"), StandardCharsets.UTF_8)) {
            JsonObject mod = new JsonParser().parse(in)
                .getAsJsonObject()
                .getAsJsonArray("modList")
                .get(0)
                .getAsJsonObject();
            assertFalse(
                mod.has("useDependencyInformation") && mod.get("useDependencyInformation")
                    .getAsBoolean(),
                "com useDependencyInformation o FML ignora a faixa do @Mod");
        }
    }
}
