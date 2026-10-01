package com.akashiic.fm.tools;

import java.io.BufferedInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.akashiic.fm.audio.decode.PcmSource;
import com.akashiic.fm.audio.decode.StreamFormat;
import com.akashiic.fm.audio.dsp.Resampler48k;
import com.akashiic.fm.audio.http.IcyHttpClient;
import com.akashiic.fm.audio.http.UrlPolicy;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * Sonda manual do relay contra rádios reais (precisa de internet; não roda nos testes).
 * Uso: java ... com.akashiic.fm.tools.RelayProbe <segundos> <url> [url...]
 * URL -> IcyHttpClient -> detecção de formato -> decoder -> 48 kHz estéreo -> Opus 20 ms.
 * Cada URL roda numa thread; o custo de CPU é medido por thread (a espera de rede não conta).
 */
public final class RelayProbe {

    public static void main(String[] args) throws Exception {
        final int seconds = Integer.parseInt(args[0]);
        final UrlPolicy policy = new UrlPolicy(new String[0], true);
        final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        List<Thread> threads = new ArrayList<Thread>();
        final List<String> report = java.util.Collections.synchronizedList(new ArrayList<String>());

        for (int i = 1; i < args.length; i++) {
            final String url = args[i];
            Thread t = new Thread(new Runnable() {

                @Override
                public void run() {
                    report.add(runOne(url, seconds, policy, mx));
                }
            }, "relay-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        for (String line : report) System.out.println(line);
    }

    static String runOne(String url, int seconds, UrlPolicy policy, ThreadMXBean mx) {
        StringBuilder sb = new StringBuilder("== ").append(url)
            .append('\n');
        long wall0 = System.nanoTime();
        final AtomicReference<IcyHttpClient.Response> resp = new AtomicReference<IcyHttpClient.Response>();
        try {
            IcyHttpClient client = IcyHttpClient.fromSystemProperties(policy);
            IcyHttpClient.Response r = client.open(url);
            resp.set(r);
            long connectMs = (System.nanoTime() - wall0) / 1_000_000;
            BufferedInputStream body = new BufferedInputStream(r.body, 64 * 1024);
            StreamFormat fmt = StreamFormat.detect(r.header("content-type"), body);
            sb.append(
                String
                    .format("   status=%s  final=%s%n", r.statusLine, r.finalUrl.equals(url) ? "(mesma)" : r.finalUrl));
            sb.append(
                String.format(
                    "   content-type=%s  icy-name=%s  icy-metaint=%s  formato=%s%n",
                    r.header("content-type"),
                    r.header("icy-name"),
                    r.header("icy-metaint"),
                    fmt));
            if (fmt == StreamFormat.UNSUPPORTED) {
                r.close();
                return sb.append("   RESULTADO: formato não suportado\n")
                    .toString();
            }

            long cpu0 = mx.getCurrentThreadCpuTime();
            PcmSource pcm = StreamFormat.open(fmt, body);
            long firstPcmMs = (System.nanoTime() - wall0) / 1_000_000;
            Resampler48k rs = new Resampler48k(pcm.sampleRate(), pcm.channels());
            OpusEncoder enc = new OpusEncoder(48000, 2, OpusApplication.OPUS_APPLICATION_AUDIO);
            enc.setBitrate(64000);
            enc.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);

            short[] in = new short[16384];
            short[] out48 = new short[rs.maxOut(in.length) + 2 * 960];
            short[] frame = new short[960 * 2];
            int frameFill = 0;
            byte[] packet = new byte[1275];
            long inFrames = 0, opusBytes = 0, opusPackets = 0;
            int inRate = pcm.sampleRate(), inCh = pcm.channels();
            long deadline = System.nanoTime() + (seconds + 20) * 1_000_000_000L;

            while (inFrames < (long) seconds * inRate && System.nanoTime() < deadline) {
                int n = pcm.read(in, 0, in.length);
                if (n == -1) break;
                if (pcm.sampleRate() != inRate || pcm.channels() != inCh) { // formato mudou no meio (stream encadeado)
                    inRate = pcm.sampleRate();
                    inCh = pcm.channels();
                    rs = new Resampler48k(inRate, inCh);
                    out48 = new short[rs.maxOut(in.length) + 2 * 960];
                }
                inFrames += n / inCh;
                int m = rs.process(in, n, out48);
                int p = 0;
                while (p < m) {
                    int take = Math.min(m - p, frame.length - frameFill);
                    System.arraycopy(out48, p, frame, frameFill, take);
                    frameFill += take;
                    p += take;
                    if (frameFill == frame.length) {
                        opusBytes += enc.encode(frame, 0, 960, packet, 0, packet.length);
                        opusPackets++;
                        frameFill = 0;
                    }
                }
            }
            long cpuMs = (mx.getCurrentThreadCpuTime() - cpu0) / 1_000_000;
            double audioSec = inFrames / (double) inRate;
            double wallSec = (System.nanoTime() - wall0) / 1e9;
            String title = r.metadata == null ? "(sem icy-metaint)" : r.metadata.streamTitle();
            sb.append(
                String.format(
                    "   codec=%s  entrada=%d Hz/%dch  conexão=%d ms  primeiro PCM=%d ms%n",
                    pcm.codec(),
                    inRate,
                    inCh,
                    connectMs,
                    firstPcmMs));
            sb.append(
                String.format(
                    "   áudio=%.1f s em %.1f s de relógio  CPU(decode+resample+opus)=%d ms -> %.2f%% de 1 núcleo%n",
                    audioSec,
                    wallSec,
                    cpuMs,
                    audioSec > 0 ? cpuMs / (audioSec * 10.0) : 0));
            sb.append(
                String.format(
                    "   opus: %d pacotes, %.0f bytes/s  |  StreamTitle: %s%n",
                    opusPackets,
                    audioSec > 0 ? opusBytes / audioSec : 0,
                    title));
            pcm.close();
            sb.append(audioSec >= seconds * 0.9 ? "   RESULTADO: OK\n" : "   RESULTADO: incompleto\n");
        } catch (Throwable t) {
            sb.append("   RESULTADO: FALHOU — ")
                .append(t)
                .append('\n');
            if (t.getCause() != null) sb.append("   causa: ")
                .append(t.getCause())
                .append('\n');
        } finally {
            IcyHttpClient.Response r = resp.get();
            if (r != null) try {
                r.close();
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }
}
