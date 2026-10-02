package com.akashiic.fm.server;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import com.akashiic.fm.AkashicFM;

/**
 * Log de auditoria em {@code logs/akashicfm-audit.log}: quem mudou URL, frequência, quem foi bloqueado, o que um admin
 * parou. Uma linha por evento, com horário em UTC; também vai para o log do servidor. Texto vindo de jogador é
 * limpo (sem quebra de linha nem caractere de controle: ninguém forja uma linha falsa no log). Gira em 10 MB
 * (guarda um anterior). Só a thread principal escreve.
 */
public final class AuditLog {

    static final String FILE = "akashicfm-audit.log";
    static final long ROTATE_BYTES = 10L * 1024 * 1024;

    private static Writer writer;
    private static boolean failed;

    private AuditLog() {}

    /** {@code actor} pode ser null (console, sistema). */
    public static void log(String actor, UUID actorId, String action, String details) {
        String line = format(System.currentTimeMillis(), actor, actorId, action, details);
        AkashicFM.LOG.info("[audit] {}", line.substring(line.indexOf(' ') + 1));
        write(line);
    }

    /** "2026-10-02T17:00:00Z Fulano (uuid) ação: detalhes". */
    static String format(long ms, String actor, UUID actorId, String action, String details) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder b = new StringBuilder(iso.format(new Date(ms))).append(' ');
        b.append(actor == null || actor.isEmpty() ? "console" : clean(actor));
        if (actorId != null) b.append(" (")
            .append(actorId)
            .append(')');
        b.append(' ')
            .append(clean(action));
        if (details != null && !details.isEmpty()) b.append(": ")
            .append(clean(details));
        return b.toString();
    }

    /** Uma linha só: controle (inclusive quebras) vira espaço; corta em 512. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), 512));
        for (int i = 0; i < s.length() && b.length() < 512; i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) || c == 0x2028 || c == 0x2029 ? ' ' : c);
        }
        return b.toString();
    }

    private static void write(String line) {
        if (failed) return;
        try {
            if (writer == null) writer = open();
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            failed = true; // sem arquivo, segue só no log do servidor
            AkashicFM.LOG.warn("Log de auditoria indisponível ({}); continua só no log do servidor", e.toString());
        }
    }

    private static Writer open() throws IOException {
        MinecraftServer server = MinecraftServer.getServer();
        File dir = server != null ? server.getFile("logs") : new File("logs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("não criou " + dir);
        File f = new File(dir, FILE);
        if (f.length() > ROTATE_BYTES) {
            File old = new File(dir, FILE + ".1");
            if (old.exists() && !old.delete()) throw new IOException("não apagou " + old);
            if (!f.renameTo(old)) throw new IOException("não girou " + f);
        }
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
    }

    /** Servidor parando. */
    public static void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
        }
        writer = null;
        failed = false;
    }
}
