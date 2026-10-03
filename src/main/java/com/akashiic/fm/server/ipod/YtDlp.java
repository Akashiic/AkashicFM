package com.akashiic.fm.server.ipod;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Chamadas ao yt-dlp. Só resolve (metadados, buscas e a URL direta do áudio): quem baixa o áudio é o relay, pelo
 * próprio cliente HTTP do mod. Segurança:
 * <ul>
 * <li>lista de argumentos, sem shell; {@code --no-config} (nenhum arquivo de config de fora muda o comportamento) e
 * sempre {@code --} antes da URL ou da busca (um texto começando com "-" não vira opção);</li>
 * <li>só os extratores do SoundCloud e do YouTube ({@code --use-extractors}): nunca o genérico, que baixaria
 * qualquer URL (SSRF). O link do jogador ainda passa por {@link #classify} antes;</li>
 * <li>tempo máximo: o processo (e os filhos dele: o executável do yt-dlp abre um) é morto.</li>
 * </ul>
 * Bloqueia: só nas threads do iPod.
 */
public final class YtDlp {

    /** O que o jogador colou. */
    public enum Kind {
        SOUNDCLOUD,
        YOUTUBE,
        SPOTIFY,
        /** Texto livre: vira uma busca no SoundCloud. */
        SEARCH,
        INVALID
    }

    static final String EXTRACTORS = "soundcloud.*,youtube.*";
    static final int MAX_SEARCH_CHARS = 100;

    /** Resultado de uma execução. */
    public static final class Result {

        public final int exit;
        public final String out;
        /** Fim do stderr (a linha "ERROR: ..." explica a falha). */
        public final String err;
        public final boolean timedOut;

        Result(int exit, String out, String err, boolean timedOut) {
            this.exit = exit;
            this.out = out;
            this.err = err;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return exit == 0 && !timedOut;
        }

        /** A última linha "ERROR:" do yt-dlp, sem o prefixo, ou um resumo. */
        public String error() {
            if (timedOut) return "timeout";
            String last = "";
            for (String line : err.split("\r?\n")) if (line.startsWith("ERROR:")) last = line.substring(6)
                .trim();
            return last.isEmpty() ? "exit " + exit : last;
        }

        /** O yt-dlp recusou por DRM (faixa oficial de gravadora): nunca contornado, pula para outra. */
        public boolean drm() {
            return err.contains("DRM protected");
        }
    }

    private YtDlp() {}

    // ---- Links ----

    /** Classifica o que o jogador colou. Só https/http, sem credenciais nem porta, e só os hosts conhecidos. */
    public static Kind classify(String input) {
        if (input == null) return Kind.INVALID;
        String s = input.trim();
        if (s.isEmpty()) return Kind.INVALID;
        if (s.startsWith("spotify:"))
            return s.matches("spotify:(track|album|playlist):[A-Za-z0-9]{22}") ? Kind.SPOTIFY : Kind.INVALID;
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return s.length() <= MAX_SEARCH_CHARS && !s.contains("://") ? Kind.SEARCH : Kind.INVALID;
        }
        String host;
        try {
            URI u = new URI(s);
            if (u.getRawUserInfo() != null || u.getPort() != -1) return Kind.INVALID;
            host = u.getHost() == null ? ""
                : u.getHost()
                    .toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return Kind.INVALID;
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (is(host, "soundcloud.com")) return Kind.SOUNDCLOUD;
        if (is(host, "youtube.com") || host.equals("youtu.be")) return Kind.YOUTUBE;
        if (host.equals("open.spotify.com")) return Kind.SPOTIFY;
        return Kind.INVALID;
    }

    private static boolean is(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    // ---- Comandos ----

    /** Argumentos comuns a toda chamada. */
    static List<String> base(File exe, File cacheDir, File deno, boolean systemCerts) {
        List<String> c = new ArrayList<>();
        c.add(exe.getPath());
        c.addAll(Arrays.asList("--no-config", "--no-warnings", "--no-progress", "--socket-timeout", "15"));
        c.add("--cache-dir");
        c.add(cacheDir.getPath());
        if (deno != null) {
            c.add("--js-runtimes");
            c.add("deno:" + deno.getPath());
        }
        // Com uma CA própria no ambiente (proxy corporativo, testes), o yt-dlp usa as do sistema em vez das embutidas.
        if (systemCerts) {
            c.add("--compat-options");
            c.add("no-certifi");
        }
        c.add("--use-extractors");
        c.add(EXTRACTORS);
        return c;
    }

    /**
     * Metadados (playlist sem resolver cada item). {@code --ignore-no-formats-error}: num IP de datacenter o YouTube
     * não entrega nenhum formato de vídeos de gravadora ("Video unavailable"), mas título e duração sim, e é só disso
     * que o espelho precisa.
     */
    static List<String> infoArgs(String target, int maxItems) {
        return Arrays.asList(
            "-J",
            "--flat-playlist",
            "--ignore-no-formats-error",
            "--playlist-end",
            String.valueOf(maxItems),
            "--",
            target);
    }

    /**
     * A mídia no formato pedido: o JSON completo da faixa, com a URL direta do formato escolhido em {@code url} (e os
     * metadados que o modo rápido das playlists não traz, como o título dos itens de um set do SoundCloud).
     */
    static List<String> mediaArgs(String target, String format) {
        return Arrays.asList("-j", "-f", format, "--no-playlist", "--", target);
    }

    /** Busca no SoundCloud (só metadados): {@code scsearchN:texto}. */
    static String soundcloudSearch(String query, int results) {
        return "scsearch" + results + ":" + query;
    }

    /** Formato do SoundCloud: o MP3 progressivo (um arquivo comum, que o relay já toca e retoma por Range). */
    static final String SOUNDCLOUD_FORMAT = "bestaudio[protocol=http][acodec=mp3]/bestaudio[protocol=https][acodec=mp3]";
    /** Formato do YouTube direto: Opus em WebM, por HTTP. */
    static final String YOUTUBE_FORMAT = "bestaudio[acodec=opus][protocol=https]/bestaudio[acodec=opus][protocol=http]";

    // ---- Execução ----

    /**
     * Roda e espera. {@code tmpDir}: onde o executável do yt-dlp (PyInstaller) se descompacta a cada execução; na pasta
     * do servidor em vez do /tmp, que num container do Pterodactyl é um tmpfs pequeno e conta como memória.
     */
    public static Result run(List<String> command, File tmpDir, long timeoutMs, int maxOutBytes) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment()
            .put("PYTHONIOENCODING", "utf-8");
        if (tmpDir != null) {
            if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("não deu para criar " + tmpDir);
            for (String v : new String[] { "TMPDIR", "TEMP", "TMP" }) pb.environment()
                .put(v, tmpDir.getAbsolutePath());
        }
        Process p = pb.start();
        p.getOutputStream()
            .close();
        Capture out = new Capture(p.getInputStream(), maxOutBytes, false);
        Capture err = new Capture(p.getErrorStream(), 8192, true);
        out.start();
        err.start();
        boolean done;
        try {
            done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            kill(p);
            Thread.currentThread()
                .interrupt();
            throw new IOException("interrompido");
        }
        if (!done) kill(p);
        try {
            out.join(2000);
            err.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        return new Result(done ? p.exitValue() : -1, out.text(), err.text(), !done);
    }

    /** Termina com SIGTERM (o executável repassa ao filho); se não sair, força, filhos inclusive (Java 9+). */
    static void kill(Process p) {
        killDescendants(p, false);
        p.destroy();
        try {
            if (p.waitFor(2, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        killDescendants(p, true);
        p.destroyForcibly();
    }

    /**
     * {@code ProcessHandle} só existe do Java 9 em diante: por reflection, para compilar e rodar no Java 8. Os métodos
     * vêm das interfaces públicas ({@code ProcessHandle}, {@code Stream}): os das classes internas que as implementam
     * são recusados pelo sistema de módulos.
     */
    private static void killDescendants(Process p, boolean force) {
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object handle = Process.class.getMethod("toHandle")
                .invoke(p);
            Object stream = handleType.getMethod("descendants")
                .invoke(handle);
            Object[] all = ((java.util.stream.Stream<?>) stream).toArray();
            Method kill = handleType.getMethod(force ? "destroyForcibly" : "destroy");
            for (Object h : all) kill.invoke(h);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Java 8: o SIGTERM no pai basta (o executável do yt-dlp o repassa ao filho)
        }
    }

    /** Lê um stream do processo numa thread (sem isto, um pipe cheio trava o processo). */
    private static final class Capture extends Thread {

        private final InputStream in;
        private final int max;
        private final boolean keepTail;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Capture(InputStream in, int max, boolean keepTail) {
            super("AkashicFM-YtDlp-IO");
            setDaemon(true);
            this.in = in;
            this.max = max;
            this.keepTail = keepTail;
        }

        @Override
        public void run() {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = in.read(b)) != -1) {
                    synchronized (buf) {
                        if (buf.size() + n <= max) buf.write(b, 0, n);
                        else if (keepTail) { // stderr: guarda o fim (onde fica o "ERROR:")
                            byte[] all = buf.toByteArray();
                            buf.reset();
                            int keep = Math.max(0, max - n);
                            buf.write(all, Math.max(0, all.length - keep), Math.min(all.length, keep));
                            buf.write(b, 0, Math.min(n, max));
                        }
                    }
                }
            } catch (IOException ignored) {} finally {
                try {
                    in.close();
                } catch (IOException ignored) {}
            }
        }

        String text() {
            synchronized (buf) {
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
