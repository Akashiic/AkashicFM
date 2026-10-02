package com.akashiic.fm.client;

import com.akashiic.fm.client.audio.AudioEngine;
import com.akashiic.fm.client.audio.RadioAudioController;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.content.TileRadio;

/**
 * O que a rádio está tocando, para a tela, a GUI, o aviso e o WAILA. No relay o título vem do servidor (estado
 * da rádio); no modo direto o servidor não baixa o stream, então vale o título que o stream anunciou para este
 * cliente (saneado do mesmo jeito). Thread principal do cliente.
 */
public final class NowPlaying {

    private NowPlaying() {}

    /** Título atual, ou vazio. */
    public static String title(TileRadio radio) {
        RadioState s = radio.state;
        if (!s.nowPlaying.isEmpty()) return s.nowPlaying;
        if (!s.playing || s.transport != Transport.DIRECT) return "";
        String local = AudioEngine.INSTANCE.streamTitle(RadioAudioController.keyFor(radio));
        return local.isEmpty() ? "" : TextSanitizer.clean(local, RadioLimits.MAX_TITLE_LENGTH);
    }

    /** Título, ou o host da estação quando não há título. */
    public static String label(TileRadio radio) {
        String t = title(radio);
        return t.isEmpty() ? hostOf(radio.state.url) : t;
    }

    /** Host da URL (sem esquema, usuário, porta, caminho), com IPv6 literal entre colchetes inteiro. */
    public static String hostOf(String url) {
        int start = url.indexOf("://");
        start = start < 0 ? 0 : start + 3;
        int end = start;
        while (end < url.length()) {
            char c = url.charAt(end);
            if (c == '/' || c == '?' || c == '#') break;
            end++;
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            return close > 0 ? hostPort.substring(0, close + 1) : hostPort;
        }
        int colon = hostPort.indexOf(':');
        return colon >= 0 ? hostPort.substring(0, colon) : hostPort;
    }
}
