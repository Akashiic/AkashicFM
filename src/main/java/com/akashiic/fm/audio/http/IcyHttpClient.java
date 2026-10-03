package com.akashiic.fm.audio.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.akashiic.fm.Tags;

/**
 * Cliente HTTP mínimo para streams de rádio (Icecast/Shoutcast).
 * Por que não HttpURLConnection/OkHttp: aceitar "ICY 200 OK" (Shoutcast v1), controlar
 * cada redirect pela UrlPolicy, conectar no IP já validado e cancelar de verdade
 * (close() derruba o socket mesmo com a thread presa em read()).
 */
public final class IcyHttpClient {

    public static final int MAX_REDIRECTS = 3;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final UrlPolicy policy;
    private final String proxyHost; // proxy HTTP CONNECT opcional (null = conexão direta)
    private final int proxyPort;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public IcyHttpClient(UrlPolicy policy, String proxyHost, int proxyPort, int connectTimeoutMs, int readTimeoutMs) {
        this.policy = policy;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Usa o proxy HTTPS da JVM se estiver configurado (https.proxyHost/Port). */
    public static IcyHttpClient fromSystemProperties(UrlPolicy policy) {
        String host = System.getProperty("https.proxyHost");
        int port = Integer.parseInt(System.getProperty("https.proxyPort", "0"));
        return new IcyHttpClient(policy, host == null || host.isEmpty() ? null : host, port, 8000, 10000);
    }

    public Response open(String url) throws IOException, UrlPolicy.PolicyException {
        return open(url, 0);
    }

    /**
     * Abre a partir do byte {@code rangeStart} (> 0: pede {@code Range} e só aceita 206 começando ali; um servidor
     * que devolve o arquivo inteiro com 200 seria um fluxo errado para quem retoma). {@link Response#statusCode} diz
     * 200 ou 206.
     */
    public Response open(String url, long rangeStart) throws IOException, UrlPolicy.PolicyException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = policy.check(current);
            Response r = request(uri, rangeStart);
            int code = r.statusCode;
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = r.header("location");
                r.close();
                if (location == null) throw new IOException("redirect sem Location");
                current = uri.resolve(location.trim())
                    .toString();
                continue;
            }
            boolean ok = rangeStart > 0 ? code == 206 && startsAt(r.header("content-range"), rangeStart) : code == 200;
            if (!ok) {
                r.close();
                throw new HttpStatusException(code, uri);
            }
            r.finalUrl = uri.toString();
            return r;
        }
        throw new IOException("redirects demais");
    }

    /** {@code Content-Range: bytes 1234-5678/9999} começa em {@code start}? */
    static boolean startsAt(String contentRange, long start) {
        if (contentRange == null) return false;
        String v = contentRange.trim()
            .toLowerCase(Locale.ROOT);
        if (!v.startsWith("bytes ")) return false;
        int dash = v.indexOf('-', 6);
        if (dash < 0) return false;
        try {
            return Long.parseLong(
                v.substring(6, dash)
                    .trim())
                == start;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Response request(URI uri, long rangeStart) throws IOException, UrlPolicy.PolicyException {
        boolean tls = uri.getScheme()
            .equalsIgnoreCase("https");
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);

        InetAddress target = policy.resolvePublic(host);
        Socket socket = new Socket();
        boolean ok = false;
        try {
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            if (proxyHost != null) {
                socket.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs);
                tunnel(socket, host, port);
            } else {
                socket.connect(new InetSocketAddress(target, port), connectTimeoutMs);
            }
            if (tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, host, port, true);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS"); // SSLSocket não confere o hostname sem isso
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                socket = ssl;
            }

            String path = uri.getRawPath() == null || uri.getRawPath()
                .isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            String hostHeader = uri.getPort() == -1 ? host : host + ":" + port;
            String req = "GET " + path
                + " HTTP/1.1\r\n"
                + "Host: "
                + hostHeader
                + "\r\n"
                + "User-Agent: AkashicFM/"
                + Tags.VERSION
                + " (Minecraft radio)\r\n"
                + "Accept: */*\r\n"
                + (rangeStart > 0 ? "Range: bytes=" + rangeStart + "-\r\n" : "Icy-MetaData: 1\r\n")
                + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            Response r = new Response(socket);
            String status = readLine(in);
            r.statusLine = status;
            // "HTTP/1.1 200 OK" ou "ICY 200 OK" (Shoutcast v1)
            String[] parts = status.split(" ", 3);
            if (parts.length < 2 || !(parts[0].startsWith("HTTP/") || parts[0].equals("ICY"))) {
                throw new IOException("linha de status inesperada: " + status);
            }
            r.statusCode = Integer.parseInt(parts[1]);
            int total = status.length();
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                total += line.length();
                if (total > MAX_HEADER_BYTES) throw new IOException("cabeçalhos grandes demais");
                int c = line.indexOf(':');
                if (c > 0) r.headers.put(
                    line.substring(0, c)
                        .trim()
                        .toLowerCase(Locale.ROOT),
                    line.substring(c + 1)
                        .trim());
            }

            InputStream body = in;
            if ("chunked".equalsIgnoreCase(r.header("transfer-encoding"))) body = new ChunkedInputStream(body);
            String metaint = r.header("icy-metaint");
            if (metaint != null) {
                IcyMetadataInputStream icy = new IcyMetadataInputStream(body, Integer.parseInt(metaint.trim()));
                r.metadata = icy;
                body = icy;
            }
            r.body = body;
            ok = true;
            return r;
        } finally {
            if (!ok) socket.close();
        }
    }

    private void tunnel(Socket s, String host, int port) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(
            ("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        InputStream in = s.getInputStream();
        String status = readLine(in);
        if (!status.matches("HTTP/1\\.[01] 200.*")) throw new IOException("proxy recusou CONNECT: " + status);
        while (!readLine(in).isEmpty()) { /* descarta cabeçalhos do proxy */ }
    }

    /** Lê uma linha terminada em CRLF (ou LF) byte a byte, sem consumir além dela. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 8192) throw new IOException("linha grande demais");
        }
        if (b == -1 && buf.size() == 0) throw new IOException("conexão fechada antes da resposta");
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** Resposta HTTP com status inesperado (403/404/410: URL assinada expirada, quem resolveu pode renovar). */
    public static final class HttpStatusException extends IOException {

        public final int code;

        HttpStatusException(int code, URI uri) {
            // Sem a query: URLs assinadas e de rádios com token não vão para o log nem para a tela.
            super(
                "HTTP " + code
                    + " em "
                    + uri.getScheme()
                    + "://"
                    + uri.getRawAuthority()
                    + (uri.getRawPath() == null ? "" : uri.getRawPath()));
            this.code = code;
        }
    }

    public static final class Response implements Closeable {

        private final Socket socket;
        public String statusLine;
        public int statusCode;
        public String finalUrl;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public InputStream body;
        public IcyMetadataInputStream metadata; // null se o servidor não manda icy-metaint

        Response(Socket socket) {
            this.socket = socket;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** Seguro de chamar de outra thread: derruba o socket e destrava quem estiver em read(). */
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
