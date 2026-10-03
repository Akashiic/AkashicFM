package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolManagerTest {

    @Test
    void binarioCertoParaCadaSistema() {
        ToolManager.Platform linux = ToolManager.detect("Linux", "amd64", false);
        assertEquals("yt-dlp_linux", linux.ytDlpAsset);
        assertEquals("deno-x86_64-unknown-linux-gnu.zip", linux.denoAsset);
        assertFalse(linux.windows);

        ToolManager.Platform arm = ToolManager.detect("Linux", "aarch64", false);
        assertEquals("yt-dlp_linux_aarch64", arm.ytDlpAsset);
        assertEquals("deno-aarch64-unknown-linux-gnu.zip", arm.denoAsset);

        ToolManager.Platform alpine = ToolManager.detect("Linux", "amd64", true);
        assertEquals("yt-dlp_musllinux", alpine.ytDlpAsset);
        assertNull(alpine.denoAsset); // o Deno não tem build para musl
        assertEquals("yt-dlp_musllinux_aarch64", ToolManager.detect("Linux", "arm64", true).ytDlpAsset);

        ToolManager.Platform win = ToolManager.detect("Windows Server 2022", "amd64", false);
        assertEquals("yt-dlp.exe", win.ytDlpAsset);
        assertEquals("deno-x86_64-pc-windows-msvc.zip", win.denoAsset);
        assertTrue(win.windows);

        assertEquals("yt-dlp_macos", ToolManager.detect("Mac OS X", "aarch64", false).ytDlpAsset);
        assertEquals("deno-aarch64-apple-darwin.zip", ToolManager.detect("Mac OS X", "aarch64", false).denoAsset);
        assertEquals("deno-x86_64-apple-darwin.zip", ToolManager.detect("Mac OS X", "x86_64", false).denoAsset);
    }

    @Test
    void hashDoArquivoDeChecksums() {
        // Trecho de verdade do SHA2-256SUMS do yt-dlp e do .sha256sum do Deno.
        String sums = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6  yt-dlp\n"
            + "66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a  yt-dlp.exe\n"
            + "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a  yt-dlp_linux\r\n"
            + "32e72032766bef9199d99d15beb69fd52e46df8f8b06f0d8745db59e04d339e9  yt-dlp_linux.zip\n"
            + "B16E4DAB368A816CD05D477D698A605A6AE87CCEE1C8FFD38FA21D7254141FCC *yt-dlp_linux_aarch64\n";
        assertEquals(
            "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a",
            ToolManager.expectedHash(sums, "yt-dlp_linux"));
        assertEquals(
            "b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc",
            ToolManager.expectedHash(sums, "yt-dlp_linux_aarch64"));
        assertNull(ToolManager.expectedHash(sums, "yt-dlp_macos"));
        assertNull(ToolManager.expectedHash(sums, "yt-dlp_l")); // prefixo não vale
        assertNull(ToolManager.expectedHash("zzzz  yt-dlp_linux\n", "yt-dlp_linux"));
        assertNull(
            ToolManager.expectedHash(
                "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d4639923zz  yt-dlp_linux",
                "yt-dlp_linux"));
        assertNull(ToolManager.expectedHash(null, "yt-dlp_linux"));
        assertEquals(
            "c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490",
            ToolManager.expectedHash(
                "c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490  deno-x86_64-unknown-linux-gnu.zip\n",
                "deno-x86_64-unknown-linux-gnu.zip"));
    }

    @Test
    void sha256DeArquivo(@TempDir Path dir) throws Exception {
        File f = dir.resolve("abc")
            .toFile();
        Files.write(f.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ToolManager.sha256(f));
        assertEquals(
            "",
            ToolManager.sha256(
                dir.resolve("nada")
                    .toFile()));
    }

    @Test
    void limpaSoOsRestosAntigosDoPyInstaller(@TempDir Path dir) throws Exception {
        File tmp = dir.toFile();
        File old = new File(tmp, "_MEIabc123");
        assertTrue(new File(old, "lib").mkdirs());
        Files.write(new File(old, "lib/x.so").toPath(), new byte[] { 1 });
        File fresh = new File(tmp, "_MEIdef456");
        assertTrue(fresh.mkdirs());
        File other = new File(tmp, "outra-coisa");
        assertTrue(other.mkdirs());
        long now = System.currentTimeMillis();
        assertTrue(old.setLastModified(now - ToolManager.STALE_TMP_MS - 1000));
        assertTrue(other.setLastModified(now - ToolManager.STALE_TMP_MS - 1000));

        assertEquals(1, ToolManager.cleanStale(tmp, ToolManager.STALE_TMP_MS, now));
        assertFalse(old.exists());
        assertTrue(fresh.exists()); // pode ser de uma resolução rodando agora
        assertTrue(other.exists()); // não é nosso
        assertEquals(0, ToolManager.cleanStale(new File(tmp, "nao-existe"), 0, now));
    }
}
