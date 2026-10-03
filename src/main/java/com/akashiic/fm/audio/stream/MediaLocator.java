package com.akashiic.fm.audio.stream;

import java.io.IOException;

/**
 * De onde o {@link StreamPump} baixa. Para as rádios, uma URL fixa; para o iPod, a URL assinada que o yt-dlp resolveu,
 * que expira: quando o servidor responde 403/404/410 numa retomada, {@link #refresh()} resolve de novo. Chamado só na
 * thread do pump (pode bloquear).
 */
public interface MediaLocator {

    /** A URL a abrir agora. */
    String url() throws IOException;

    /** A URL atual foi recusada (expirou): uma nova, ou a mesma se não há como renovar. */
    String refresh() throws IOException;

    static MediaLocator fixed(String url) {
        return new MediaLocator() {

            @Override
            public String url() {
                return url;
            }

            @Override
            public String refresh() {
                return url;
            }
        };
    }
}
