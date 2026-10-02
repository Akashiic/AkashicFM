package com.akashiic.fm.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.SpeakerChannel;
import com.akashiic.fm.common.Transport;

/** As duas traduções têm as mesmas chaves, os mesmos argumentos e cobrem as chaves montadas a partir de enums. */
class LangFilesTest {

    private static final String[] POLICY_CODES = { "empty", "too_long", "malformed", "scheme", "credentials", "no_host",
        "port", "not_allowed", "internal" };

    private static Map<String, String> load(String lang) throws IOException {
        String path = "/assets/akashicfm/lang/" + lang + ".lang";
        InputStream in = LangFilesTest.class.getResourceAsStream(path);
        assertNotNull(in, path);
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                assertTrue(eq > 0, "linha sem '=': " + line);
                String key = line.substring(0, eq);
                assertTrue(out.put(key, line.substring(eq + 1)) == null, "chave repetida: " + key);
            }
        }
        return out;
    }

    private static int placeholders(String s) {
        Matcher m = Pattern.compile("%(\\d+\\$)?s")
            .matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void mesmasChavesEArgumentos() throws IOException {
        Map<String, String> en = load("en_US");
        Map<String, String> pt = load("pt_BR");
        assertEquals(en.keySet(), pt.keySet());
        for (String k : en.keySet()) assertEquals(placeholders(en.get(k)), placeholders(pt.get(k)), k);
    }

    @Test
    void soUsaPercentS() throws IOException {
        // ChatComponentTranslation só entende %s; %d quebraria as mensagens de chat.
        for (String lang : new String[] { "en_US", "pt_BR" }) {
            for (Map.Entry<String, String> e : load(lang).entrySet()) {
                String v = e.getValue()
                    .replace("%%", "")
                    .replaceAll("%(\\d+\\$)?s", "");
                assertTrue(!v.contains("%"), lang + ": " + e.getKey());
            }
        }
    }

    @Test
    void chavesDeEnumsExistem() throws IOException {
        Map<String, String> en = load("en_US");
        for (RadioAccess a : RadioAccess.values()) assertTrue(en.containsKey("akashicfm.gui.access." + a.name()));
        for (RedstoneMode m : RedstoneMode.values()) assertTrue(en.containsKey("akashicfm.gui.redstone." + m.name()));
        for (Transport t : Transport.values()) assertTrue(en.containsKey("akashicfm.gui.transport." + t.name()));
        for (SpeakerChannel c : SpeakerChannel.values()) {
            assertTrue(
                en.containsKey(
                    "akashicfm.channel." + c.name()
                        .toLowerCase(Locale.ROOT)),
                c.name());
        }
        for (String code : POLICY_CODES) assertTrue(en.containsKey("akashicfm.policy." + code), code);
    }
}
