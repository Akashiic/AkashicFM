package com.akashiic.fm.server.ipod;

import java.io.BufferedInputStream;
import java.io.File;

import com.akashiic.fm.audio.decode.PcmSource;
import com.akashiic.fm.audio.decode.StreamFormat;
import com.akashiic.fm.audio.http.IcyHttpClient;
import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.IPodTrack;

/**
 * Sonda manual do iPod contra os serviços de verdade (precisa de internet e de um yt-dlp; não roda nos testes). Mesmo
 * caminho do servidor: {@link ToolManager} com o caminho manual, {@link MediaResolver#create}, e o áudio aberto pelo
 * {@link IcyHttpClient} com a política das URLs resolvidas, decodificando ~2 s.
 * <p>
 * Uso: java ... com.akashiic.fm.server.ipod.IPodProbe &lt;yt-dlp&gt; &lt;pasta&gt; &lt;faixasPorLink&gt; &lt;link ou
 * busca&gt;...
 */
public final class IPodProbe {

    public static void main(String[] args) throws Exception {
        FmConfig.IPod.maxResolves = 2;
        FmConfig.IPod.maxQueue = 50;
        FmConfig.IPod.maxTrackMinutes = 20;
        FmConfig.IPod.spotify = true;
        FmConfig.IPod.youtubeDirect = false;
        ToolManager tools = new ToolManager(new File(args[1]));
        tools.ensure(args[0], false, false);
        while (tools.state() != ToolManager.State.READY) {
            if (tools.state() == ToolManager.State.FAILED) throw new IllegalStateException(tools.detail());
            Thread.sleep(50);
        }
        int perLink = Integer.parseInt(args[2]);
        MediaResolver resolver = MediaResolver.create(tools);
        int ok = 0, fail = 0;
        for (int i = 3; i < args.length; i++) {
            String input = args[i];
            long t0 = System.nanoTime();
            YtDlpJson.Listing l;
            try {
                l = resolver.expand(input, 50);
            } catch (MediaResolver.ResolveException e) {
                System.out.printf("EXPAND  %-60s -> %s (%d ms)%n", input, e.status(), ms(t0));
                fail++;
                continue;
            }
            System.out.printf(
                "EXPAND  %-60s -> %d faixas%s, %d puladas (%d ms)%n",
                input,
                l.tracks.size(),
                l.playlist ? " de \"" + l.title + "\"" : "",
                l.skipped,
                ms(t0));
            for (int k = 0; k < Math.min(perLink, l.tracks.size()); k++) {
                IPodTrack t = l.tracks.get(k);
                long t1 = System.nanoTime();
                try {
                    MediaResolver.Located loc = resolver.locate(t);
                    String audio = probeAudio(loc.mediaUrl);
                    System.out.printf(
                        "  TOCA  %s%n        %s (%d ms) %s%n        url %d chars; %s%n",
                        t,
                        loc.mirror == null ? "direto"
                            : "espelho: " + loc.mirror
                                .display() + " (" + loc.mirror.durationSec + " s) " + loc.mirror.link,
                        ms(t1),
                        loc.track.display(),
                        loc.mediaUrl.length(),
                        audio);
                    long t2 = System.nanoTime();
                    String renewed = resolver.refresh(loc);
                    System.out.printf(
                        "        renovada em %d ms (%s)%n",
                        ms(t2),
                        renewed.equals(loc.mediaUrl) ? "igual" : "nova");
                    ok++;
                } catch (MediaResolver.ResolveException e) {
                    System.out.printf("  FALHA %s%n        %s (%d ms)%n", t, e.status(), ms(t1));
                    fail++;
                }
            }
        }
        System.out.printf("RESUMO ok=%d falhas=%d%n", ok, fail);
    }

    /** Abre como a estação abriria e decodifica ~2 s. */
    private static String probeAudio(String url) {
        try (IcyHttpClient.Response r = IcyHttpClient.fromSystemProperties(UrlPolicy.resolvedMedia())
            .open(url)) {
            BufferedInputStream in = new BufferedInputStream(r.body, 64 * 1024);
            StreamFormat fmt = StreamFormat.detect(r.header("content-type"), in);
            PcmSource pcm = StreamFormat.open(fmt, in);
            short[] buf = new short[8192];
            long samples = 0;
            int peak = 0;
            while (samples < (long) pcm.sampleRate() * pcm.channels() * 2) {
                int n = pcm.read(buf, 0, buf.length);
                if (n < 0) break;
                for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(buf[i]));
                samples += n;
            }
            return String.format(
                "%s %d Hz %d ch, %s bytes, ranges=%s, %.1f s decodificados, pico %d",
                fmt,
                pcm.sampleRate(),
                pcm.channels(),
                r.header("content-length"),
                r.header("accept-ranges"),
                samples / (double) (pcm.sampleRate() * pcm.channels()),
                peak);
        } catch (Exception e) {
            return "ÁUDIO FALHOU: " + e;
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
