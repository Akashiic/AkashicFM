package com.akashiic.fm.server.ipod;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.Tags;

/**
 * Os binários que o iPod usa, baixados e mantidos pelo próprio mod (no Pterodactyl não há shell para instalar nada):
 * <ul>
 * <li><b>yt-dlp</b>, o executável oficial da plataforma, do release mais novo no GitHub;</li>
 * <li><b>Deno</b>, só com {@code ipod.youtubeDirect} (o yt-dlp precisa de um runtime JS para o YouTube).</li>
 * </ul>
 * Cada download é conferido contra o SHA-256 publicado no próprio release (um arquivo que não bate é apagado) e
 * marcado como executável; uma vez por dia, compara o binário local com o publicado e baixa de novo se mudou. Tudo
 * numa thread própria; a thread do jogo só lê o estado.
 */
public final class ToolManager {

    public enum State {
        /** Ainda não pedido (iPod desligado). */
        IDLE,
        INSTALLING,
        READY,
        FAILED
    }

    static final String YTDLP_RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    static final String DENO_RELEASE = "https://github.com/denoland/deno/releases/latest/download/";
    static final long CHECK_INTERVAL_MS = 24L * 3600 * 1000;
    /** Depois de uma falha (rede fora, GitHub fora do ar), tenta de novo neste intervalo. */
    static final long RETRY_FAILED_MS = 10L * 60 * 1000;
    static final long MAX_DOWNLOAD_BYTES = 250L * 1024 * 1024;
    /** Pastas temporárias de execuções mortas à força: nenhuma resolução dura tanto. */
    static final long STALE_TMP_MS = 6L * 3600 * 1000;

    /** Os nomes dos arquivos do release para este sistema. */
    static final class Platform {

        final String ytDlpAsset;
        /** Zip do Deno, ou null se não há build oficial (musl). */
        final String denoAsset;
        final boolean windows;

        Platform(String ytDlpAsset, String denoAsset, boolean windows) {
            this.ytDlpAsset = ytDlpAsset;
            this.denoAsset = denoAsset;
            this.windows = windows;
        }
    }

    private final File dir;
    private volatile State state = State.IDLE;
    private volatile String detail = "";
    private volatile File ytDlp;
    private volatile File deno;
    private volatile long lastCheckMs;
    private String lastConfig = "";
    private Thread worker;

    public ToolManager(File dir) {
        this.dir = dir;
    }

    public State state() {
        return state;
    }

    /** Motivo da falha (ou o que está fazendo), para o log e para a tela do iPod. */
    public String detail() {
        return detail;
    }

    /** O yt-dlp pronto para usar, ou null. */
    public File ytDlp() {
        return state == State.READY ? ytDlp : null;
    }

    /** O Deno pronto, ou null (não pedido ou sem build para o sistema). */
    public File deno() {
        return state == State.READY ? deno : null;
    }

    public File dir() {
        return dir;
    }

    /** Onde o yt-dlp se descompacta a cada execução (fora do /tmp do container). */
    public File tmpDir() {
        return new File(dir, "tmp");
    }

    /**
     * Garante as ferramentas (thread do jogo: só dispara o trabalho). {@code manualPath} não vazio usa um yt-dlp já
     * instalado; senão, com {@code autoInstall}, baixa. Repetir a chamada é barato; a cada dia confere atualização.
     */
    public synchronized void ensure(String manualPath, boolean autoInstall, boolean wantDeno) {
        if (worker != null && worker.isAlive()) return;
        String config = (manualPath == null ? "" : manualPath.trim()) + "|" + autoInstall + "|" + wantDeno;
        long since = System.currentTimeMillis() - lastCheckMs;
        boolean changed = !config.equals(lastConfig);
        if (!changed && state == State.READY && since < CHECK_INTERVAL_MS) return;
        if (!changed && state == State.FAILED && since < RETRY_FAILED_MS) return; // não tenta a cada tick
        lastConfig = config;
        if (state != State.READY || changed) state = State.INSTALLING;
        worker = new Thread(() -> install(manualPath, autoInstall, wantDeno), "AkashicFM-Tools");
        worker.setDaemon(true);
        worker.start();
    }

    private void install(String manualPath, boolean autoInstall, boolean wantDeno) {
        lastCheckMs = System.currentTimeMillis();
        cleanStale(tmpDir(), STALE_TMP_MS, lastCheckMs);
        try {
            Platform p = detect(System.getProperty("os.name"), System.getProperty("os.arch"), isMusl());
            String manual = manualPath == null ? "" : manualPath.trim();
            File y;
            if (!manual.isEmpty()) {
                y = new File(manual);
                if (!y.isFile()) throw new IOException("ytDlpPath não existe: " + manual);
                if (!y.canExecute() && !y.setExecutable(true)) throw new IOException("ytDlpPath não é executável");
            } else if (autoInstall) {
                y = installYtDlp(p);
            } else {
                throw new IOException("sem yt-dlp: ligue ipod.autoInstallTools ou defina ipod.ytDlpPath");
            }
            File d = null;
            if (wantDeno) {
                if (p.denoAsset == null) {
                    AkashicFM.LOG.warn("iPod: sem Deno oficial para este sistema; o YouTube vai só pelo espelho");
                } else if (autoInstall) {
                    d = installDeno(p);
                }
            }
            ytDlp = y;
            deno = d;
            detail = "";
            state = State.READY;
            AkashicFM.LOG.info("iPod: yt-dlp pronto ({}){}", y, d == null ? "" : ", Deno pronto");
        } catch (IOException | RuntimeException e) {
            detail = String.valueOf(e.getMessage());
            // Com uma versão já funcionando e o mesmo config, uma checagem diária que falhou (rede) não derruba
            // o iPod: continua com o binário que tem.
            if (state != State.READY || ytDlp == null) state = State.FAILED;
            AkashicFM.LOG.warn("iPod: ferramentas indisponíveis: {}", detail);
        }
    }

    private File installYtDlp(Platform p) throws IOException {
        File target = new File(dir, p.ytDlpAsset);
        String sums = new String(download(YTDLP_RELEASE + "SHA2-256SUMS", 1 << 20), StandardCharsets.UTF_8);
        String expected = expectedHash(sums, p.ytDlpAsset);
        if (expected == null) throw new IOException("o release do yt-dlp não lista " + p.ytDlpAsset);
        if (!target.isFile() || !expected.equalsIgnoreCase(sha256(target))) {
            detail = "baixando yt-dlp";
            AkashicFM.LOG.info("iPod: baixando o yt-dlp ({})", p.ytDlpAsset);
            fetchVerified(YTDLP_RELEASE + p.ytDlpAsset, expected, target);
        }
        if (!target.canExecute() && !target.setExecutable(true))
            throw new IOException("não deu para marcar o yt-dlp como executável");
        return target;
    }

    private File installDeno(Platform p) throws IOException {
        File target = new File(dir, p.windows ? "deno.exe" : "deno");
        File zip = new File(dir, p.denoAsset);
        String sum = new String(download(DENO_RELEASE + p.denoAsset + ".sha256sum", 4096), StandardCharsets.UTF_8);
        String expected = expectedHash(sum, p.denoAsset);
        if (expected == null) throw new IOException("checksum do Deno ilegível");
        if (!target.isFile() || !zip.isFile() || !expected.equalsIgnoreCase(sha256(zip))) {
            detail = "baixando Deno";
            AkashicFM.LOG.info("iPod: baixando o Deno ({})", p.denoAsset);
            fetchVerified(DENO_RELEASE + p.denoAsset, expected, zip);
            extractSingle(zip, target.getName(), target);
        }
        if (!target.canExecute() && !target.setExecutable(true))
            throw new IOException("não deu para marcar o Deno como executável");
        return target;
    }

    /** Baixa para um temporário, confere o hash e só então troca o arquivo. */
    private void fetchVerified(String url, String expectedSha256, File target) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("não deu para criar " + dir);
        File tmp = new File(dir, target.getName() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            stream(url, out, MAX_DOWNLOAD_BYTES);
        }
        String got = sha256(tmp);
        if (!expectedSha256.equalsIgnoreCase(got)) {
            Files.deleteIfExists(tmp.toPath());
            throw new IOException("SHA-256 não confere para " + target.getName());
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void extractSingle(File zip, String name, File target) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || !new File(e.getName()).getName()
                    .equals(name)) continue;
                File tmp = new File(target.getParentFile(), name + ".part");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_DOWNLOAD_BYTES) throw new IOException("arquivo grande demais no zip");
                        out.write(buf, 0, n);
                    }
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        throw new IOException(name + " não está no zip");
    }

    // ---- Funções puras (testadas) ----

    static Platform detect(String osName, String osArch, boolean musl) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("win")) return new Platform("yt-dlp.exe", "deno-x86_64-pc-windows-msvc.zip", true);
        if (os.contains("mac") || os.contains("darwin")) {
            return new Platform(
                "yt-dlp_macos",
                arm ? "deno-aarch64-apple-darwin.zip" : "deno-x86_64-apple-darwin.zip",
                false);
        }
        if (musl) return new Platform(arm ? "yt-dlp_musllinux_aarch64" : "yt-dlp_musllinux", null, false);
        return new Platform(
            arm ? "yt-dlp_linux_aarch64" : "yt-dlp_linux",
            arm ? "deno-aarch64-unknown-linux-gnu.zip" : "deno-x86_64-unknown-linux-gnu.zip",
            false);
    }

    /** O hash da linha de {@code asset} num arquivo de checksums ("hash nome" ou "hash *nome"), ou null. */
    static String expectedHash(String sums, String asset) {
        if (sums == null) return null;
        for (String line : sums.split("\r?\n")) {
            String t = line.trim();
            int sp = t.indexOf(' ');
            if (sp != 64) continue;
            String name = t.substring(sp)
                .trim();
            if (name.startsWith("*")) name = name.substring(1);
            if (name.equals(asset) && t.substring(0, 64)
                .matches("[0-9a-fA-F]{64}"))
                return t.substring(0, 64)
                    .toLowerCase(Locale.ROOT);
        }
        return null;
    }

    static String sha256(File f) throws IOException {
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Apaga as pastas {@code _MEI*} antigas: o executável do yt-dlp (PyInstaller) se descompacta numa delas a cada
     * execução e só a apaga ao sair normalmente; um processo morto por timeout a deixa para trás. Não segue links.
     */
    static int cleanStale(File tmp, long olderThanMs, long nowMs) {
        File[] entries = tmp.listFiles();
        if (entries == null) return 0;
        int removed = 0;
        for (File e : entries) {
            if (!e.getName()
                .startsWith("_MEI") || nowMs - e.lastModified() < olderThanMs) continue;
            try {
                deleteTree(e.toPath());
                removed++;
            } catch (IOException ex) {
                AkashicFM.LOG.debug("iPod: não deu para apagar {}: {}", e, ex.getMessage());
            }
        }
        return removed;
    }

    private static void deleteTree(java.nio.file.Path root) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {

            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path f,
                java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(f);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path d, IOException exc)
                throws IOException {
                if (exc != null) throw exc;
                Files.delete(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /** Alpine e afins: a libc é musl (o yt-dlp tem build própria; o Deno não). */
    static boolean isMusl() {
        File lib = new File("/lib");
        String[] names = lib.list();
        if (names == null) return false;
        for (String n : names) if (n.startsWith("ld-musl-")) return true;
        return false;
    }

    // ---- HTTP (só hosts fixos do GitHub; segue os redirects para o armazenamento dele) ----

    private static byte[] download(String url, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stream(url, out, maxBytes);
        return out.toByteArray();
    }

    private static void stream(String url, OutputStream out, long maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "AkashicFM/" + Tags.VERSION);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " em " + url);
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new IOException("download grande demais: " + url);
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
