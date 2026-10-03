package com.akashiic.fm.common;

/**
 * Uma faixa da fila do iPod: o link original (o que o jogador colou, ou o item de uma playlist), com título, artista e
 * duração para a tela e para achar o espelho. O áudio só é resolvido na hora de tocar (as URLs dos CDNs expiram em
 * minutos). Imutável; o texto vem de fora (yt-dlp, Spotify, NBT) e é sempre limpo aqui.
 */
public final class IPodTrack {

    /** De onde veio o link. Só o SoundCloud toca direto; os outros tocam pelo espelho no SoundCloud. */
    public enum Source {

        SOUNDCLOUD,
        YOUTUBE,
        SPOTIFY;

        public static Source byOrdinal(int i) {
            Source[] all = values();
            return i >= 0 && i < all.length ? all[i] : null;
        }
    }

    /** Links canônicos são curtos (o do SoundCloud é o mais longo); mais que isto não entra na fila. */
    public static final int MAX_LINK = 256;
    public static final int MAX_TITLE = 96;
    public static final int MAX_ARTIST = 64;
    /** Duração desconhecida é 0; acima disto também vira desconhecida. */
    public static final int MAX_DURATION_SEC = 24 * 3600;

    public final Source source;
    public final String link;
    public final String title;
    public final String artist;
    /** Segundos; 0 = desconhecida. */
    public final int durationSec;

    public IPodTrack(Source source, String link, String title, String artist, int durationSec) {
        if (source == null) throw new IllegalArgumentException("sem origem");
        this.source = source;
        String l = TextSanitizer.cleanUrl(link, MAX_LINK + 1);
        this.link = l.length() > MAX_LINK ? "" : l;
        this.title = TextSanitizer.clean(title, MAX_TITLE);
        this.artist = TextSanitizer.clean(artist, MAX_ARTIST);
        this.durationSec = durationSec > 0 && durationSec <= MAX_DURATION_SEC ? durationSec : 0;
    }

    /** Válida para entrar na fila: tem link. */
    public boolean valid() {
        return !link.isEmpty();
    }

    /** "Artista - Título", ou só o título quando ele já traz o artista (ou o link, sem título). */
    public String display() {
        String t = title.isEmpty() ? link : title;
        return artist.isEmpty() || fold(t).contains(fold(artist)) ? t : artist + " - " + t;
    }

    /** Minúsculas e sem acento: "Arcángel" já contém "Arcangel". */
    private static String fold(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
    }

    @Override
    public String toString() {
        return source + " " + display() + " (" + durationSec + " s) " + link;
    }
}
