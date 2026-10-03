package com.akashiic.fm.server.ipod;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Limpa títulos para achar a mesma música no SoundCloud: tira o que é do vídeo e não da música ("(Official Video)",
 * "[Lyrics]", "HD", hashtags, "ft. ..."), mas mantém o que muda a versão ("Remix", "Live", "Acoustic"), que o
 * {@link MirrorMatcher} usa para recusar a versão errada. Funções puras.
 */
final class TitleCleaner {

    /** Palavras que, dentro de parênteses ou colchetes, marcam o trecho como coisa do vídeo. */
    private static final Set<String> VIDEO_WORDS = new HashSet<>(
        Arrays.asList(
            "official",
            "oficial",
            "video",
            "videoclip",
            "videoclipe",
            "clip",
            "clipe",
            "audio",
            "lyric",
            "lyrics",
            "letra",
            "legendado",
            "legendada",
            "traducao",
            "visualizer",
            "visualiser",
            "hd",
            "hq",
            "4k",
            "1080p",
            "720p",
            "mv",
            "m/v",
            "explicit",
            "uncensored",
            "premiere",
            "teaser"));

    /** Frases de vídeo fora dos parênteses. */
    private static final Pattern VIDEO_PHRASE = Pattern.compile(
        "(?iu)\\b(?:(?:official|oficial)\\s+(?:music\\s+|lyric\\s+|lyrics\\s+)?(?:video|audio|mv|clip|clipe|visualizer)"
            + "|(?:music|lyric|lyrics)\\s+video|video\\s+(?:oficial|official|clipe)|clipe\\s+oficial|audio\\s+oficial"
            + "|m/v|mv|hd|hq|4k)\\b");
    /**
     * Clipe (vídeo com abertura, final ou cenas a mais que o áudio), não áudio nem letra: a música pode ser bem mais
     * curta que o vídeo. "Lyric video" e "visualizer" ficam de fora (têm a duração do áudio).
     */
    private static final Pattern MUSIC_VIDEO = Pattern.compile(
        "(?iu)\\b(?:(?:official|oficial)\\s+(?:music\\s+)?(?:video|clip|clipe|mv)|music\\s+video|video\\s+(?:oficial|official|clipe)"
            + "|videoclipe?|clipe\\s+oficial|performance\\s+video|m/v|mv)\\b");
    private static final Pattern BRACKETS = Pattern.compile("[(\\[【「『]([^)\\]】」』]*)[)\\]】」』]");
    private static final Pattern FEAT = Pattern.compile("(?iu)\\s+(?:ft\\.?|feat\\.?|featuring)\\s+[^\\-–—|(\\[]*");
    private static final Pattern HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]+");
    private static final Pattern DASH = Pattern.compile("\\s+[-–—]\\s+");
    private static final Pattern SPOTIFY_SUFFIX = Pattern.compile(
        "(?iu)\\s+-\\s+(?!.*\\s-\\s)(?:.*\\bremaster(?:ed)?\\b.*|from\\s+.*|\\d{4}\\s+(?:mix|version|remaster).*|mono|stereo"
            + "|radio\\s+edit|single\\s+version|album\\s+version|edit|single)$");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /** Palavras que não ajudam a comparar (artigos, "feat", ruído de vídeo que sobrou). */
    static final Set<String> STOP = new HashSet<>(
        Arrays.asList(
            "the",
            "a",
            "an",
            "and",
            "of",
            "e",
            "o",
            "os",
            "as",
            "de",
            "da",
            "do",
            "el",
            "la",
            "los",
            "y",
            "feat",
            "ft",
            "featuring",
            "official",
            "oficial",
            "video",
            "audio",
            "lyrics",
            "lyric",
            "hd",
            "hq",
            "mv",
            "original",
            "remaster",
            "remastered",
            "version",
            "versao",
            "prod",
            "by"));

    private TitleCleaner() {}

    /** Título de vídeo do YouTube sem o ruído de vídeo (as versões ficam). */
    static String cleanVideoTitle(String title) {
        if (title == null) return "";
        String s = title;
        s = HASHTAG.matcher(s)
            .replaceAll(" ");
        StringBuffer sb = new StringBuffer();
        java.util.regex.Matcher m = BRACKETS.matcher(s);
        while (m.find()) {
            String inner = m.group(1);
            boolean drop = isVideoNoise(inner) || isFeat(inner);
            m.appendReplacement(sb, drop ? " " : java.util.regex.Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        s = sb.toString();
        int pipe = s.indexOf('|');
        if (pipe > 0) s = s.substring(0, pipe); // "Música | Canal" ou "| Ao vivo no programa X"
        s = FEAT.matcher(s)
            .replaceAll(" ");
        s = VIDEO_PHRASE.matcher(s)
            .replaceAll(" ");
        return tidy(s);
    }

    /** Nome de faixa do Spotify sem os sufixos de relançamento ("- Remastered 2011", "- From \"Filme\""). */
    static String cleanSpotifyName(String name) {
        if (name == null) return "";
        String s = SPOTIFY_SUFFIX.matcher(name.trim())
            .replaceAll("");
        StringBuffer sb = new StringBuffer();
        java.util.regex.Matcher m = BRACKETS.matcher(s);
        while (m.find())
            m.appendReplacement(sb, isFeat(m.group(1)) ? " " : java.util.regex.Matcher.quoteReplacement(m.group()));
        m.appendTail(sb);
        return tidy(
            FEAT.matcher(sb.toString())
                .replaceAll(" "));
    }

    /** O título é de um clipe (veja {@link #MUSIC_VIDEO}). */
    static boolean isMusicVideo(String title) {
        return title != null && MUSIC_VIDEO.matcher(title)
            .find();
    }

    /** Palavras do fim do caminho do link ("the-cure-in-the-live-lounge"): versões que o título não diz. */
    static List<String> slugTokens(String link) {
        if (link == null) return new ArrayList<>();
        String path = link;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        return tokens(slash >= 0 ? path.substring(slash + 1) : path);
    }

    /** Nome do canal como artista: sem "VEVO", " - Topic", "Official". */
    static String cleanChannel(String channel) {
        if (channel == null) return "";
        String s = channel.trim();
        if (s.endsWith(" - Topic")) s = s.substring(0, s.length() - 8);
        s = s.replaceAll("(?i)vevo$", "")
            .replaceAll("(?iu)\\b(?:official|oficial)\\b", "");
        return tidy(s);
    }

    /** Divide "Artista - Título" (o primeiro traço com espaços). Null se não há traço. */
    static String[] splitArtistTitle(String cleaned) {
        java.util.regex.Matcher m = DASH.matcher(cleaned);
        if (!m.find()) return null;
        String left = cleaned.substring(0, m.start())
            .trim(),
            right = cleaned.substring(m.end())
                .trim();
        if (left.isEmpty() || right.isEmpty()) return null;
        return new String[] { left, right };
    }

    private static boolean isVideoNoise(String inner) {
        for (String w : inner.toLowerCase(Locale.ROOT)
            .split("[\\s,.:;!_]+")) {
            if (VIDEO_WORDS.contains(fold(w))) return true;
        }
        return false;
    }

    private static boolean isFeat(String inner) {
        String l = inner.trim()
            .toLowerCase(Locale.ROOT);
        return l.startsWith("ft ") || l.startsWith("ft.") || l.startsWith("feat") || l.startsWith("with ");
    }

    private static String tidy(String s) {
        String t = s.replaceAll("\\s+", " ")
            .trim();
        // Traços ou separadores que sobraram nas pontas ("Música -", "- Música").
        t = t.replaceAll("^[\\s\\-–—:|,]+", "")
            .replaceAll("[\\s\\-–—:|,]+$", "");
        // Aspas saem; o apóstrofo dentro de uma palavra ("Don't") fica.
        return t.replaceAll("[\"“”]", "")
            .replaceAll("(?<!\\p{L})['‘’]|['‘’](?!\\p{L})", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** Minúsculas e sem acento (para comparar "Canção" com "cancao"). */
    static String fold(String s) {
        return MARKS.matcher(Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
            .replaceAll("");
    }

    /** Palavras para comparar: sem acento, sem pontuação e sem as de {@link #STOP}. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : NOT_WORD.split(fold(s))) {
            if (!w.isEmpty() && !STOP.contains(w) && !out.contains(w)) out.add(w);
        }
        return out;
    }
}
