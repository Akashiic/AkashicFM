package com.akashiic.fm.server.ipod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.akashiic.fm.common.IPodTrack;

/**
 * Acha, entre os resultados de uma busca no SoundCloud, a mesma música de um vídeo do YouTube ou de uma faixa do
 * Spotify. Lógica pura, calibrada com buscas de verdade (os casos estão nos testes). Um candidato só passa se:
 * <ul>
 * <li>a duração bate: |Δ| ≤ max(6 s, 4%) para o Spotify (a duração é a do áudio) e max(10 s, 6%) para o YouTube. Um
 * clipe ("Official Video") tem abertura e final a mais, então ali o áudio pode ser até 25% mais curto (nunca mais
 * longo). Prévias de 30 s são recusadas;</li>
 * <li>não é outra versão: "remix", "cover", "ao vivo"... que o original não tem, no título ou no link
 * ("...-live-lounge"),
 * também dentro de palavras ("RodrydjFlowRMX");</li>
 * <li>tem as palavras do título (75%, arredondado para cima: todas, em títulos de até 3 palavras);</li>
 * <li>tem o artista, quando ele é conhecido de verdade (Spotify, ou "Artista - Título" no YouTube); com só o nome do
 * canal (pode ser uma gravadora), o artista só é exigido em títulos curtos (até 3 palavras, os genéricos como
 * "Intro").</li>
 * </ul>
 * Os que passam são ordenados por título, artista (com bônus para o upload do próprio artista), proximidade da duração
 * e posição na busca.
 */
final class MirrorMatcher {

    /** Palavras que marcam outra versão da música. */
    static final Set<String> VERSIONS = new HashSet<>(
        Arrays.asList(
            "remix",
            "rmx",
            "cover",
            "karaoke",
            "instrumental",
            "nightcore",
            "sped",
            "slowed",
            "reverb",
            "8d",
            "bassboosted",
            "boosted",
            "live",
            "vivo",
            "acoustic",
            "acustico",
            "mashup",
            "bootleg",
            "flip",
            "vip",
            "loop",
            "lofi",
            "tiktok",
            "chipmunk",
            "reverse",
            "piano",
            "orchestral",
            "extended",
            "edm",
            // acelerada ou desacelerada
            "fast",
            "faster",
            "speed",
            "speedup",
            "spedup",
            "daycore",
            "pitched",
            // efeitos ("Concert Hall", "underwater", "in the bathroom")
            "concert",
            "underwater",
            "muffled",
            "bathroom"));
    /** Marcas que valem também dentro de uma palavra (nomes de remixadores colados: "FlowRMX", "TechRemix"). */
    static final List<String> VERSION_PARTS = Arrays
        .asList("remix", "rmx", "nightcore", "mashup", "bootleg", "karaoke");

    /** Quanto o áudio pode ser mais curto que um clipe. */
    static final double MUSIC_VIDEO_SHORTER = 0.25;

    /**
     * Uma leitura de quem é o título e quem é o artista ("A - B" pode ser "Artista - Música" ou "Música - Artista").
     */
    static final class Reading {

        final List<String> titleTokens;
        /** Cada nome de artista ("Peso Pluma", "Anitta") como palavras; basta um aparecer inteiro. */
        final List<List<String>> artistNames;
        /** Os nomes sem espaços (casam com "daftpunk", o nome de usuário de muitos uploads). */
        final List<String> artistCompact;

        Reading(List<String> titleTokens, List<List<String>> artistNames, List<String> artistCompact) {
            this.titleTokens = titleTokens;
            this.artistNames = artistNames;
            this.artistCompact = artistCompact;
        }

        boolean artistKnown() {
            return !artistNames.isEmpty();
        }
    }

    /** Busca e critério para uma faixa. */
    static final class Wanted {

        final String query;
        /** Segunda busca, se a primeira não render nada que toque (null se não há). */
        final String fallbackQuery;
        final List<Reading> readings;
        /** O artista é confiável (não o nome de um canal qualquer): precisa aparecer no candidato. */
        final boolean artistRequired;
        final Set<String> versions;
        final int durationSec;
        final int toleranceSec;
        /** Quanto o candidato pode ser mais curto (clipe: maior que a tolerância). */
        final int shorterSec;

        Wanted(String query, String fallbackQuery, List<Reading> readings, boolean artistRequired, Set<String> versions,
            int durationSec, int toleranceSec, int shorterSec) {
            this.query = query;
            this.fallbackQuery = fallbackQuery;
            this.readings = readings;
            this.artistRequired = artistRequired;
            this.versions = versions;
            this.durationSec = durationSec;
            this.toleranceSec = toleranceSec;
            this.shorterSec = shorterSec;
        }
    }

    private MirrorMatcher() {}

    static int tolerance(int durationSec, IPodTrack.Source source) {
        if (source == IPodTrack.Source.SPOTIFY) return Math.max(6, (int) Math.round(durationSec * 0.04));
        return Math.max(10, (int) Math.round(durationSec * 0.06));
    }

    /**
     * Vídeo do YouTube: "Artista - Título (Official Video)" (nas duas ordens), ou o título com o canal como artista.
     */
    static Wanted fromYouTube(IPodTrack t) {
        String cleaned = TitleCleaner.cleanVideoTitle(t.title);
        String channel = TitleCleaner.cleanChannel(t.artist);
        String[] split = TitleCleaner.splitArtistTitle(cleaned);
        List<Reading> readings = new ArrayList<>();
        String query, fallback;
        boolean required;
        if (split != null) {
            readings.add(reading(split[1], split[0], channel));
            // Na leitura invertida o canal fica de fora: ele é o "título" dela e casaria sozinho como artista.
            readings.add(reading(split[0], split[1], ""));
            query = split[0] + " " + split[1];
            fallback = split[1];
            required = true;
        } else {
            readings.add(reading(cleaned, channel, ""));
            query = channel.isEmpty() ? cleaned : channel + " " + cleaned;
            fallback = channel.isEmpty() ? null : cleaned;
            required = false;
        }
        int tol = t.durationSec > 0 ? tolerance(t.durationSec, IPodTrack.Source.YOUTUBE) : 0;
        int shorter = TitleCleaner.isMusicVideo(t.title)
            ? Math.max(tol, (int) Math.round(t.durationSec * MUSIC_VIDEO_SHORTER))
            : tol;
        return wanted(query, fallback, readings, required, t.title, t.durationSec, tol, shorter);
    }

    /** Faixa do Spotify: o artista é a lista "A, B" (o primeiro vai na busca). */
    static Wanted fromSpotify(IPodTrack t) {
        String title = TitleCleaner.cleanSpotifyName(t.title);
        String artists = t.artist;
        int comma = artists.indexOf(", ");
        String first = comma > 0 ? artists.substring(0, comma) : artists;
        String query = first.isEmpty() ? title : first + " " + title;
        int tol = t.durationSec > 0 ? tolerance(t.durationSec, IPodTrack.Source.SPOTIFY) : 0;
        List<Reading> readings = new ArrayList<>();
        readings.add(reading(title, artists, ""));
        return wanted(query, first.isEmpty() ? null : title, readings, true, t.title, t.durationSec, tol, tol);
    }

    /** Separa uma lista de artistas: "A, B & C", "A x B", "A / B", "A e B". */
    private static final java.util.regex.Pattern ARTIST_SEPARATOR = java.util.regex.Pattern
        .compile("(?iu)\\s*(?:,|&|/|\\+|\\s(?:x|e|y|and|feat\\.?|ft\\.?|with)\\s)\\s*");

    private static Reading reading(String title, String artists, String extraArtist) {
        List<List<String>> names = new ArrayList<>();
        List<String> compact = new ArrayList<>();
        for (String list : new String[] { artists, extraArtist }) {
            for (String name : ARTIST_SEPARATOR.split(list)) {
                List<String> words = TitleCleaner.tokens(name);
                if (words.isEmpty() || names.contains(words)) continue;
                names.add(words);
                String c = compact(name);
                if (c.length() >= 3 && !compact.contains(c)) compact.add(c);
            }
        }
        return new Reading(TitleCleaner.tokens(title), names, compact);
    }

    private static Wanted wanted(String query, String fallback, List<Reading> readings, boolean required,
        String original, int durationSec, int tolerance, int shorter) {
        Set<String> versions = new HashSet<>();
        for (String w : TitleCleaner.tokens(original)) {
            if (VERSIONS.contains(w)) versions.add(w);
            for (String part : VERSION_PARTS) if (w.contains(part)) versions.add(part);
        }
        if (fallback != null && fallback.equals(query)) fallback = null;
        return new Wanted(query.trim(), fallback, readings, required, versions, durationSec, tolerance, shorter);
    }

    /** Os candidatos aceitáveis, do melhor para o pior. */
    static List<IPodTrack> rank(Wanted w, List<IPodTrack> candidates) {
        List<Scored> ok = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            IPodTrack c = candidates.get(i);
            double s = score(w, c, i);
            if (!Double.isNaN(s)) ok.add(new Scored(c, s, i));
        }
        Collections.sort(ok, (a, b) -> a.score != b.score ? Double.compare(b.score, a.score) : a.index - b.index);
        List<IPodTrack> out = new ArrayList<>(ok.size());
        for (Scored s : ok) out.add(s.track);
        return out;
    }

    /** Nota do candidato, ou NaN se recusado. */
    static double score(Wanted w, IPodTrack c, int index) {
        if (c == null || !c.valid() || c.source != IPodTrack.Source.SOUNDCLOUD) return Double.NaN;
        String text = c.title + " " + c.artist;
        Set<String> words = new HashSet<>(TitleCleaner.tokens(text));
        String compact = compact(text);

        if (otherVersion(w, words) || otherVersion(w, TitleCleaner.slugTokens(c.link))) return Double.NaN;

        int cd = c.durationSec;
        if (cd > 0 && cd <= 31 && (w.durationSec == 0 || w.durationSec > 45)) return Double.NaN; // prévia
        double dur = 0;
        if (w.durationSec > 0 && cd > 0) {
            int delta = cd - w.durationSec;
            if (delta > w.toleranceSec || -delta > w.shorterSec) return Double.NaN;
            int abs = Math.abs(delta);
            // Dentro da tolerância normal: de 1 (igual) a 0. Só pela folga do clipe: de -0.25 a -0.75, atrás de todos
            // os de dentro, mas ainda o mais perto na frente.
            dur = abs <= w.toleranceSec ? 1.0 - abs / (double) Math.max(1, w.toleranceSec)
                : -0.25 - 0.5 * (abs - w.toleranceSec) / (double) Math.max(1, w.shorterSec - w.toleranceSec);
        } else if (w.durationSec > 0) {
            dur = -0.5; // candidato sem duração: aceito, mas atrás de quem tem
        }

        Set<String> uploader = new HashSet<>(TitleCleaner.tokens(c.artist));
        String uploaderCompact = compact(c.artist);
        double best = Double.NaN;
        for (Reading r : w.readings) {
            double s = readingScore(w, r, words, compact, uploader, uploaderCompact);
            if (!Double.isNaN(s) && (Double.isNaN(best) || s > best)) best = s;
        }
        return Double.isNaN(best) ? best : best + dur - 0.02 * index;
    }

    /**
     * Título e artista de uma leitura no candidato, ou NaN se não bate. Quem publicou ser o próprio artista (o upload
     * oficial) vale um bônus.
     */
    private static double readingScore(Wanted w, Reading r, Set<String> words, String compact, Set<String> uploader,
        String uploaderCompact) {
        int n = r.titleTokens.size();
        if (n == 0) return Double.NaN; // título só de ruído: nada para comparar
        int hits = 0;
        for (String t : r.titleTokens) if (has(words, compact, t)) hits++;
        if (hits < (int) Math.ceil(0.75 * n)) return Double.NaN;

        int found = 0;
        for (int i = 0; i < r.artistNames.size(); i++) {
            boolean all = true;
            for (String t : r.artistNames.get(i)) if (!has(words, compact, t)) all = false;
            if (all) found++;
        }
        for (String c : r.artistCompact) if (found == 0 && compact.contains(c)) found = 1;
        if (r.artistKnown() && found == 0 && (w.artistRequired || n <= 3)) return Double.NaN;
        double artist = !r.artistKnown() ? 0 : found == 0 ? 0 : Math.max(0.5, found / (double) r.artistNames.size());

        int extra = 0;
        for (String word : words) {
            boolean known = r.titleTokens.contains(word);
            for (List<String> name : r.artistNames) if (name.contains(word)) known = true;
            if (!known) extra++;
        }
        boolean official = false;
        for (List<String> name : r.artistNames) if (!name.isEmpty() && uploader.containsAll(name)) official = true;
        for (String c : r.artistCompact) if (c.equals(uploaderCompact)) official = true;
        return 2 * hits / (double) n + artist + (official ? 0.5 : 0) - 0.03 * extra;
    }

    /** Alguma marca de versão que o original não tem. */
    private static boolean otherVersion(Wanted w, Iterable<String> words) {
        for (String word : words) {
            if (VERSIONS.contains(word) && !w.versions.contains(word)) return true;
            for (String part : VERSION_PARTS) if (word.contains(part) && !w.versions.contains(part)) return true;
        }
        return false;
    }

    /** A palavra está no candidato. Escritas sem espaço entre palavras (CJK) valem como trecho. */
    private static boolean has(Set<String> words, String compact, String token) {
        if (words.contains(token)) return true;
        for (int i = 0; i < token.length(); i++) if (token.charAt(i) > 0x2E7F) return compact.contains(token);
        return false;
    }

    static String compact(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : TitleCleaner.tokens(s)) sb.append(w);
        return sb.toString();
    }

    private static final class Scored {

        final IPodTrack track;
        final double score;
        final int index;

        Scored(IPodTrack track, double score, int index) {
            this.track = track;
            this.score = score;
            this.index = index;
        }
    }
}
