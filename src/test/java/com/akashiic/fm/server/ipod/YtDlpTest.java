package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class YtDlpTest {

    @Test
    void classificaOsHostsConhecidos() {
        assertEquals(YtDlp.Kind.SOUNDCLOUD, YtDlp.classify("https://soundcloud.com/forss/flickermood"));
        assertEquals(YtDlp.Kind.SOUNDCLOUD, YtDlp.classify("https://on.soundcloud.com/abc123"));
        assertEquals(YtDlp.Kind.SOUNDCLOUD, YtDlp.classify("https://m.soundcloud.com/forss/sets/soulhack"));
        assertEquals(YtDlp.Kind.SOUNDCLOUD, YtDlp.classify("  HTTPS://SoundCloud.com/forss  "));
        assertEquals(YtDlp.Kind.YOUTUBE, YtDlp.classify("https://www.youtube.com/watch?v=jNQXAC9IVRw"));
        assertEquals(YtDlp.Kind.YOUTUBE, YtDlp.classify("https://music.youtube.com/playlist?list=PL123"));
        assertEquals(YtDlp.Kind.YOUTUBE, YtDlp.classify("https://youtu.be/jNQXAC9IVRw"));
        assertEquals(YtDlp.Kind.YOUTUBE, YtDlp.classify("http://m.youtube.com/watch?v=jNQXAC9IVRw"));
        assertEquals(YtDlp.Kind.SPOTIFY, YtDlp.classify("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"));
        assertEquals(YtDlp.Kind.SPOTIFY, YtDlp.classify("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"));
        assertEquals(YtDlp.Kind.SOUNDCLOUD, YtDlp.classify("https://soundcloud.com./forss"));
    }

    @Test
    void textoLivreViraBusca() {
        assertEquals(YtDlp.Kind.SEARCH, YtDlp.classify("daft punk get lucky"));
        assertEquals(YtDlp.Kind.SEARCH, YtDlp.classify("-o /etc/passwd")); // vai depois do "--": nunca vira opção
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < YtDlp.MAX_SEARCH_CHARS + 1; i++) longText.append('a');
        assertEquals(YtDlp.Kind.INVALID, YtDlp.classify(longText.toString()));
    }

    @Test
    void recusaOutrosHostsCredenciaisPortasEEsquemas() {
        for (String s : new String[] { null, "", "   ", "https://evil.com/soundcloud.com",
            "https://soundcloud.com.evil.com/x", "https://notsoundcloud.com/x", "https://user:pw@soundcloud.com/x",
            "https://soundcloud.com:8443/x", "https://youtube.com@evil.com/x", "file:///etc/passwd",
            "ftp://soundcloud.com/x", "https://127.0.0.1/x", "https://open.spotify.com.evil.com/track/x",
            "https://spotify.com/track/x", "spotify:artist:0gxyHStUsqpMadRV0Di1Qt", "spotify:track:short",
            "javascript://soundcloud.com/x", "https://www.youtube-nocookie.com/embed/x", "https://[::1]/x" }) {
            assertEquals(YtDlp.Kind.INVALID, YtDlp.classify(s), String.valueOf(s));
        }
    }

    @Test
    void argumentosSeguros() {
        List<String> base = YtDlp.base(new File("/srv/tools/yt-dlp_linux"), new File("/srv/tools/cache"), null, false);
        assertEquals("/srv/tools/yt-dlp_linux", base.get(0));
        assertTrue(base.contains("--no-config"));
        int ex = base.indexOf("--use-extractors");
        assertTrue(ex > 0);
        assertEquals("soundcloud.*,youtube.*", base.get(ex + 1)); // nunca o genérico
        assertFalse(base.contains("--js-runtimes"));
        assertFalse(base.contains("--compat-options"));

        List<String> withDeno = YtDlp.base(new File("y"), new File("c"), new File("/srv/tools/deno"), true);
        assertEquals("deno:/srv/tools/deno", withDeno.get(withDeno.indexOf("--js-runtimes") + 1));
        assertEquals("no-certifi", withDeno.get(withDeno.indexOf("--compat-options") + 1));

        List<String> info = YtDlp.infoArgs("-rf /", 50);
        assertEquals(
            Arrays.asList("-J", "--flat-playlist", "--ignore-no-formats-error", "--playlist-end", "50", "--", "-rf /"),
            info);
        List<String> media = YtDlp.mediaArgs("--exec=x", YtDlp.SOUNDCLOUD_FORMAT);
        assertEquals("--", media.get(media.size() - 2));
        assertEquals("--exec=x", media.get(media.size() - 1));
        assertTrue(media.contains("--no-playlist"));
        assertEquals("scsearch8:daft punk", YtDlp.soundcloudSearch("daft punk", 8));
    }

    @Test
    void resultadoExplicaAFalha() {
        YtDlp.Result drm = new YtDlp.Result(
            1,
            "",
            "[soundcloud] x: Downloading\nERROR: [soundcloud] 555: This video is DRM protected\n",
            false);
        assertFalse(drm.ok());
        assertTrue(drm.drm());
        assertEquals("[soundcloud] 555: This video is DRM protected", drm.error());
        assertEquals("exit 2", new YtDlp.Result(2, "", "WARNING: só aviso", false).error());
        assertEquals("timeout", new YtDlp.Result(-1, "", "", true).error());
        assertTrue(new YtDlp.Result(0, "{}", "", false).ok());
        assertFalse(new YtDlp.Result(0, "{}", "", true).ok());
    }

    private static File script(Path dir, String body) throws Exception {
        File f = dir.resolve("fake-yt-dlp.sh")
            .toFile();
        Files.write(f.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(f.setExecutable(true));
        return f;
    }

    private static boolean posix() {
        return new File("/bin/sh").canExecute();
    }

    @Test
    @Timeout(20)
    void rodaComTmpProprioECapturaASaida(@TempDir Path dir) throws Exception {
        assumeTrue(posix());
        File exe = script(dir, "echo \"$TMPDIR|$1|$2\"; echo 'ERROR: falhou' >&2; exit 3");
        File tmp = dir.resolve("tmp")
            .toFile();
        List<String> cmd = new ArrayList<>(Arrays.asList(exe.getPath(), "--", "-x"));
        YtDlp.Result r = YtDlp.run(cmd, tmp, 10_000, 1 << 20);
        assertEquals(3, r.exit);
        assertFalse(r.timedOut);
        assertEquals(tmp.getAbsolutePath() + "|--|-x", r.out.trim());
        assertEquals("falhou", r.error());
        assertTrue(tmp.isDirectory());
    }

    @Test
    @Timeout(20)
    void timeoutMataOProcessoEOsFilhos(@TempDir Path dir) throws Exception {
        assumeTrue(posix());
        File marker = dir.resolve("filho-vivo")
            .toFile();
        // O PyInstaller roda o yt-dlp num processo filho: o filho também tem que morrer.
        File exe = script(dir, "(sleep 3; touch '" + marker.getPath() + "') & sleep 30");
        long t0 = System.nanoTime();
        YtDlp.Result r = YtDlp.run(new ArrayList<>(Arrays.asList(exe.getPath())), null, 500, 1024);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.timedOut);
        assertFalse(r.ok());
        assertTrue(ms < 8000, "demorou " + ms + " ms");
        // No Java 8 não há ProcessHandle: quem repassa o SIGTERM ao filho é o executável do yt-dlp (PyInstaller), não
        // um "sh" de teste. Lá só o pai é conferido.
        boolean handles;
        try {
            Process.class.getMethod("toHandle");
            handles = true;
        } catch (NoSuchMethodException e) {
            handles = false;
        }
        if (!handles) return;
        Thread.sleep(3500);
        assertFalse(marker.exists(), "o processo filho sobreviveu ao timeout");
    }

    @Test
    @Timeout(20)
    void saidaGrandeNaoTravaEFicaNoLimite(@TempDir Path dir) throws Exception {
        assumeTrue(posix());
        File exe = script(
            dir,
            "i=0; while [ $i -lt 2000 ]; do echo 0123456789012345678901234567890123456789; echo erro-$i >&2; i=$((i+1)); done");
        YtDlp.Result r = YtDlp.run(new ArrayList<>(Arrays.asList(exe.getPath())), null, 10_000, 4096);
        assertEquals(0, r.exit);
        assertTrue(r.out.length() <= 4096);
        assertTrue(r.err.contains("erro-1999"), "o fim do stderr fica guardado");
        assertTrue(r.err.length() <= 8192);
    }
}
