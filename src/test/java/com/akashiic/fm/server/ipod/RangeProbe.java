package com.akashiic.fm.server.ipod;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import com.akashiic.fm.audio.http.IcyHttpClient;
import com.akashiic.fm.audio.http.ResumableInputStream;
import com.akashiic.fm.audio.http.UrlPolicy;

/**
 * Sonda manual da retomada por Range contra um CDN de verdade (precisa de internet; não roda nos testes): baixa o
 * começo de uma URL direto e de novo derrubando a conexão no meio duas vezes, e compara byte a byte.
 * <p>
 * Uso: java ... com.akashiic.fm.server.ipod.RangeProbe &lt;url&gt; &lt;bytes&gt;
 */
public final class RangeProbe {

    public static void main(String[] args) throws Exception {
        String url = args[0];
        int total = Integer.parseInt(args[1]);
        IcyHttpClient client = IcyHttpClient.fromSystemProperties(UrlPolicy.resolvedMedia());

        byte[] straight;
        try (IcyHttpClient.Response r = client.open(url)) {
            straight = read(r.body, total);
            System.out.printf(
                "direto: HTTP %d, %s bytes, accept-ranges=%s, %d lidos%n",
                r.statusCode,
                r.header("content-length"),
                r.header("accept-ranges"),
                straight.length);
        }

        AtomicReference<IcyHttpClient.Response> current = new AtomicReference<>();
        IcyHttpClient.Response first = client.open(url);
        current.set(first);
        long length = Long.parseLong(
            first.header("content-length")
                .trim());
        ResumableInputStream in = new ResumableInputStream(first.body, length, offset -> {
            try {
                IcyHttpClient.Response r = client.open(url, offset);
                System.out.printf(
                    "  retomada em %d: HTTP %d, content-range=%s%n",
                    offset,
                    r.statusCode,
                    r.header("content-range"));
                current.set(r);
                return r.body;
            } catch (UrlPolicy.PolicyException e) {
                throw new java.io.IOException(e.getMessage());
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int[] cuts = { total / 3, 2 * total / 3 };
        int cut = 0;
        while (out.size() < total) {
            if (cut < cuts.length && out.size() >= cuts[cut]) {
                current.get()
                    .close(); // a conexão cai (como um CDN fechando um socket parado na pausa)
                cut++;
            }
            int n = in.read(buf, 0, Math.min(buf.length, total - out.size()));
            if (n < 0) break;
            out.write(buf, 0, n);
        }
        in.close();
        byte[] resumed = out.toByteArray();
        System.out.printf(
            "com quedas: %d lidos, %d retomadas, iguais=%s%n",
            resumed.length,
            in.resumes(),
            Arrays.equals(straight, resumed));
    }

    private static byte[] read(InputStream in, int total) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (out.size() < total) {
            int n = in.read(buf, 0, Math.min(buf.length, total - out.size()));
            if (n < 0) break;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
