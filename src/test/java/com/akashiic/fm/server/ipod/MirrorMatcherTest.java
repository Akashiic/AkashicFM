package com.akashiic.fm.server.ipod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashiic.fm.common.IPodTrack;

class MirrorMatcherTest {

    // ---- Limpeza de títulos ----

    @Test
    void tituloDeVideoPerdeORuidoMasNaoAVersao() {
        assertEquals(
            "Daft Punk - Get Lucky",
            TitleCleaner.cleanVideoTitle("Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers"));
        assertEquals(
            "BELLAKEO - Peso Pluma, Anitta",
            TitleCleaner.cleanVideoTitle("BELLAKEO (Video Oficial) - Peso Pluma, Anitta"));
        assertEquals("Arcángel - FN8", TitleCleaner.cleanVideoTitle("Arcángel - FN8 ( Video Lyric )"));
        assertEquals(
            "Becky G - POR EL CONTRARIO",
            TitleCleaner.cleanVideoTitle(
                "Becky G - POR EL CONTRARIO (Performance Video) ft. Ángela Aguilar, Leonardo Aguilar"));
        assertEquals(
            "Rick Astley - Never Gonna Give You Up",
            TitleCleaner.cleanVideoTitle("Rick Astley - Never Gonna Give You Up (Official Music Video) [4K Remaster]"));
        assertEquals(
            "Queen – Bohemian Rhapsody (Live Aid 1985)",
            TitleCleaner.cleanVideoTitle("Queen – Bohemian Rhapsody (Live Aid 1985)"));
        assertEquals("BTS (방탄소년단) Dynamite", TitleCleaner.cleanVideoTitle("BTS (방탄소년단) 'Dynamite' Official MV"));
        assertEquals("Song Name", TitleCleaner.cleanVideoTitle("Song Name | Live at Wembley | HD"));
        assertEquals(
            "Daft Punk - Get Lucky",
            TitleCleaner.cleanVideoTitle("Daft Punk - Get Lucky #nightcore #bassboosted"));
        assertEquals(
            "Queen - Don't Stop Me Now",
            TitleCleaner.cleanVideoTitle("Queen - Don't Stop Me Now (Official Video)"));
        assertEquals("Anitta - Envolver", TitleCleaner.cleanVideoTitle("Anitta - Envolver [Official Music Video]"));
        assertEquals("", TitleCleaner.cleanVideoTitle(null));
    }

    @Test
    void nomeDoSpotifyPerdeOsSufixosDeRelancamento() {
        assertEquals(
            "Get Lucky",
            TitleCleaner.cleanSpotifyName("Get Lucky (feat. Pharrell Williams & Nile Rodgers) - Radio Edit"));
        assertEquals("Here Comes The Sun", TitleCleaner.cleanSpotifyName("Here Comes The Sun - Remastered 2009"));
        assertEquals("Bohemian Rhapsody", TitleCleaner.cleanSpotifyName("Bohemian Rhapsody - 2011 Remaster"));
        assertEquals("Shallow", TitleCleaner.cleanSpotifyName("Shallow - From \"A Star Is Born\" Soundtrack"));
        assertEquals("Hallelujah - Live", TitleCleaner.cleanSpotifyName("Hallelujah - Live"));
        assertEquals("Song - Part 2", TitleCleaner.cleanSpotifyName("Song - Part 2 - 2011 Remaster"));
    }

    @Test
    void canalViraArtista() {
        assertEquals("DaftPunk", TitleCleaner.cleanChannel("DaftPunkVEVO"));
        assertEquals("The Weeknd", TitleCleaner.cleanChannel("The Weeknd - Topic"));
        assertEquals("Peso Pluma", TitleCleaner.cleanChannel("Peso Pluma Official"));
    }

    @Test
    void separaArtistaETitulo() {
        assertArrayEquals(
            new String[] { "Daft Punk", "Get Lucky" },
            TitleCleaner.splitArtistTitle("Daft Punk - Get Lucky"));
        assertArrayEquals(new String[] { "Arcángel", "FN8 - x" }, TitleCleaner.splitArtistTitle("Arcángel – FN8 - x"));
        assertNull(TitleCleaner.splitArtistTitle("Get-Lucky"));
        assertNull(TitleCleaner.splitArtistTitle("- Get Lucky"));
    }

    @Test
    void palavrasSemAcentoESemParada() {
        assertEquals(Arrays.asList("cancao", "mar", "x"), TitleCleaner.tokens("Canção do Mar (feat. X)"));
        assertEquals(Arrays.asList("夜に駆ける", "yoasobi"), TitleCleaner.tokens("「夜に駆ける」 YOASOBI"));
    }

    // ---- Escolha do espelho (busca de verdade gravada: "Daft Punk Get Lucky") ----

    private static List<IPodTrack> search() throws Exception {
        return YtDlpJson.listing(YtDlpJsonTest.fixture("sc-search.json"), 8).tracks;
    }

    private static IPodTrack spotify(String title, String artists, int sec) {
        return new IPodTrack(IPodTrack.Source.SPOTIFY, "https://open.spotify.com/track/x", title, artists, sec);
    }

    private static IPodTrack youtube(String title, String channel, int sec) {
        return new IPodTrack(IPodTrack.Source.YOUTUBE, "https://www.youtube.com/watch?v=x", title, channel, sec);
    }

    private static IPodTrack sc(String title, String uploader, int sec) {
        return new IPodTrack(
            IPodTrack.Source.SOUNDCLOUD,
            "https://soundcloud.com/u/" + Math.abs(title.hashCode()),
            title,
            uploader,
            sec);
    }

    @Test
    void spotifyAchaAVersaoCertaEDescartaPreviasEVersoes() throws Exception {
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(
            spotify(
                "Get Lucky (feat. Pharrell Williams & Nile Rodgers) - Radio Edit",
                "Daft Punk, Pharrell Williams, Nile Rodgers",
                248));
        assertEquals("Daft Punk Get Lucky", w.query);
        assertEquals("Get Lucky", w.fallbackQuery);
        List<IPodTrack> ranked = MirrorMatcher.rank(w, search());
        // As oficiais são prévias de 30 s, o "nightcore" é outra versão e a de 222 s está longe demais (Δ 26 > 10).
        assertEquals(1, ranked.size());
        assertEquals("https://soundcloud.com/eldjkb/get-lucky-original-version", ranked.get(0).link);
    }

    @Test
    void youtubeTemToleranciaMaiorMasNaoInfinita() throws Exception {
        IPodTrack album = youtube(
            "Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers",
            "DaftPunkVEVO",
            369);
        assertTrue(
            MirrorMatcher.rank(MirrorMatcher.fromYouTube(album), search())
                .isEmpty()); // versão do álbum (6:09): nenhuma bate

        IPodTrack radio = youtube(
            "Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers",
            "DaftPunkVEVO",
            250);
        MirrorMatcher.Wanted w = MirrorMatcher.fromYouTube(radio);
        assertEquals("Daft Punk Get Lucky", w.query);
        List<IPodTrack> ranked = MirrorMatcher.rank(w, search());
        // A de 222 s está fora (Δ 28 > 15) e ainda é "cover" e "mashup": outra versão.
        assertEquals(1, ranked.size());
        assertEquals("https://soundcloud.com/eldjkb/get-lucky-original-version", ranked.get(0).link);
    }

    @Test
    void versaoPedidaPodeTocarMasNaoOutraAlemDela() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromYouTube(youtube("Daft Punk - Get Lucky (Nightcore)", "Fã", 108));
        List<IPodTrack> ranked = MirrorMatcher.rank(
            w,
            Arrays.asList(
                sc("Daft Punk - Get Lucky #nightcore #bassboosted", "999333", 108), // nightcore pedido; bassboosted não
                sc("Get Lucky (Nightcore)", "Daft Punk fan", 107)));
        assertEquals(1, ranked.size());
        assertEquals("Get Lucky (Nightcore)", ranked.get(0).title);
    }

    @Test
    void tituloCurtoExigeOArtista() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(spotify("Intro", "The xx", 128));
        List<IPodTrack> ranked = MirrorMatcher
            .rank(w, Arrays.asList(sc("Intro", "Some DJ", 127), sc("Intro", "The xx", 129)));
        assertEquals(1, ranked.size());
        assertEquals("The xx", ranked.get(0).artist);
    }

    @Test
    void spotifyExigeOArtistaETresQuartosDoTitulo() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(spotify("Never Gonna Give You Up", "Rick Astley", 213));
        List<IPodTrack> ranked = MirrorMatcher.rank(
            w,
            Arrays.asList(
                sc("never gonna give you up", "uploader123", 214),
                sc("Never Gonna Let You Go", "Rick Astley", 213),
                sc("Give You Up", "Rick Astley", 213),
                sc("Rick Astley - Never Gonna Give You Up", "fan", 212)));
        assertEquals(1, ranked.size());
        assertEquals("fan", ranked.get(0).artist);
    }

    @Test
    void uploadDoProprioArtistaVemNaFrente() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(spotify("Flickermood", "Forss", 214));
        List<IPodTrack> ranked = MirrorMatcher
            .rank(w, Arrays.asList(sc("Forss - Flickermood", "fan", 214), sc("Flickermood", "Forss", 212)));
        assertEquals("Forss", ranked.get(0).artist);
    }

    @Test
    void semDuracaoAceitoMasDepois() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(spotify("Flickermood", "Forss", 214));
        List<IPodTrack> ranked = MirrorMatcher
            .rank(w, Arrays.asList(sc("Flickermood", "Forss", 0), sc("Flickermood", "Forss", 213)));
        assertEquals(2, ranked.size());
        assertEquals(213, ranked.get(0).durationSec);
    }

    @Test
    void outrasOrigensNuncaSaoCandidatas() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(spotify("Flickermood", "Forss", 214));
        assertTrue(
            MirrorMatcher.rank(w, Arrays.asList(youtube("Forss - Flickermood", "Forss", 214)))
                .isEmpty());
    }

    @Test
    void escritaSemEspacos() {
        MirrorMatcher.Wanted w = MirrorMatcher
            .fromYouTube(youtube("YOASOBI「夜に駆ける」 Official Music Video", "Ayase / YOASOBI", 261));
        List<IPodTrack> ranked = MirrorMatcher
            .rank(w, Arrays.asList(sc("夜に駆ける", "YOASOBI", 262), sc("群青", "YOASOBI", 261)));
        assertEquals(1, ranked.size());
        assertEquals("夜に駆ける", ranked.get(0).title);
    }

    @Test
    void canalDoYouTubeSemTracoVaiNaBuscaComSegundaTentativa() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromYouTube(youtube("Blinding Lights", "The Weeknd - Topic", 200));
        assertEquals("The Weeknd Blinding Lights", w.query);
        assertEquals("Blinding Lights", w.fallbackQuery);
        assertEquals(10, MirrorMatcher.tolerance(100, IPodTrack.Source.YOUTUBE));
        assertEquals(18, MirrorMatcher.tolerance(300, IPodTrack.Source.YOUTUBE));
        assertEquals(6, MirrorMatcher.tolerance(100, IPodTrack.Source.SPOTIFY));
        assertEquals(12, MirrorMatcher.tolerance(300, IPodTrack.Source.SPOTIFY));
    }

    // ---- Casos reais da sonda (buscas de verdade no SoundCloud) ----

    @Test
    void clipeAceitaAudioMaisCurtoMasNuncaMaisLongo() {
        // O clipe do Rick Astley tem 213 s; um upload de 193 s (o único sem DRM) é aceito, atrás de um mais perto.
        MirrorMatcher.Wanted w = MirrorMatcher.fromYouTube(
            youtube("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", "Rick Astley", 213));
        List<IPodTrack> ranked = MirrorMatcher.rank(
            w,
            Arrays.asList(
                sc("Rick Astley - Never Gonna Give You Up", "colin-clark-24", 193),
                sc("Never Gonna Give You Up", "Rick Astley", 212),
                sc("Rick Astley - Never Gonna Give You Up (long)", "fan", 240)));
        assertEquals(2, ranked.size());
        assertEquals(212, ranked.get(0).durationSec);
        assertEquals(193, ranked.get(1).durationSec);
        // Áudio oficial (não clipe): sem a folga.
        MirrorMatcher.Wanted audio = MirrorMatcher
            .fromYouTube(youtube("Rick Astley - Never Gonna Give You Up (Official Audio)", "Rick Astley", 213));
        assertTrue(
            MirrorMatcher.rank(audio, Arrays.asList(sc("Rick Astley - Never Gonna Give You Up", "colin-clark-24", 193)))
                .isEmpty());
    }

    @Test
    void outraMusicaComAsMesmasPalavrasNaoPassa() {
        // "Rainy, rainy days" (arranjo vocal de outro artista) tinha as palavras do título e duração parecida.
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(spotify("Never Gonna Give You Up", "Rick Astley", 214));
        assertTrue(
            MirrorMatcher
                .rank(
                    w,
                    Arrays.asList(
                        sc(
                            "Never gonna give you up - 【VOCAL】 Rainy, rainy days【SOUND HOLIC】",
                            "never gonna give you up",
                            216)))
                .isEmpty());
    }

    @Test
    void ordemMusicaArtistaDoYouTube() {
        // "BELLAKEO (Video Oficial) - Peso Pluma, Anitta": o clipe tem 235 s e o áudio oficial (do Peso Pluma), 197 s.
        MirrorMatcher.Wanted w = MirrorMatcher
            .fromYouTube(youtube("BELLAKEO (Video Oficial) - Peso Pluma, Anitta", "Peso Pluma", 235));
        List<IPodTrack> ranked = MirrorMatcher.rank(
            w,
            Arrays.asList(
                new IPodTrack(
                    IPodTrack.Source.SOUNDCLOUD,
                    "https://soundcloud.com/pesopluma/peso-pluma-anitta-bellakeo",
                    "BELLAKEO",
                    "Peso Pluma",
                    197),
                new IPodTrack(
                    IPodTrack.Source.SOUNDCLOUD,
                    "https://soundcloud.com/music-urbans-lg/peso-pluma-anitta-bellakeo",
                    "Peso Pluma, Anitta - BELLAKEO",
                    "Music Urbans",
                    185),
                new IPodTrack(
                    IPodTrack.Source.SOUNDCLOUD,
                    "https://soundcloud.com/laser-004/bellakeo-peso-pluma-anittalaser-remix",
                    "BELLAKEO - Peso Pluma, Anitta(Laser Remix)",
                    "Laser",
                    150),
                new IPodTrack(
                    IPodTrack.Source.SOUNDCLOUD,
                    "https://soundcloud.com/jeskanln/anitta-peso-pluma-bellakeo-piseiro-jeska-remix",
                    "Anitta & Peso Pluma - Bellakeo (Piseiro) | Jeska Remix",
                    "Jeska",
                    200),
                new IPodTrack(
                    IPodTrack.Source.SOUNDCLOUD,
                    "https://soundcloud.com/rubecorohernan/bellakeo-peso-pluma-anitta-extended-100bpm",
                    "Bellakeo - Peso Pluma, Anitta (Extended) 100bpm",
                    "Ruben Coronado Dj 8.0",
                    230),
                new IPodTrack(
                    IPodTrack.Source.SOUNDCLOUD,
                    "https://soundcloud.com/luis-garcia-102/peso-plumaanitta-bellakeo-ney-bass-sabor-mix-2024",
                    "PESO PLUMA,ANITTA - BELLAKEO (NEY BASS SABOR MIX 2024)  FREE DOWNLOAD",
                    "DJ NEYBASS",
                    262)));
        assertEquals(2, ranked.size());
        assertEquals("https://soundcloud.com/pesopluma/peso-pluma-anitta-bellakeo", ranked.get(0).link);
    }

    @Test
    void ordemInvertidaNaoAfrouxaOArtista() {
        // Na leitura "Música - Artista" de "Daft Punk - Get Lucky", o artista seria "Get Lucky": tem que aparecer
        // inteiro.
        MirrorMatcher.Wanted w = MirrorMatcher
            .fromYouTube(youtube("Daft Punk - Get Lucky (Official Audio)", "DaftPunkVEVO", 249));
        assertTrue(
            MirrorMatcher.rank(w, Arrays.asList(sc("Daft Punk - Lucky Star", "someone", 249)))
                .isEmpty());
    }

    @Test
    void remixColadoNoNomeEVersaoSoNoLink() {
        MirrorMatcher.Wanted becky = MirrorMatcher.fromYouTube(
            youtube(
                "Becky G - POR EL CONTRARIO (Performance Video) ft. Ángela Aguilar, Leonardo Aguilar",
                "Becky G",
                235));
        assertTrue(
            MirrorMatcher
                .rank(
                    becky,
                    Arrays.asList(sc("Becky G- Por El Contrario (Vers. CumbiaBase_RodrydjFlowRMX)", "Rodry", 221)))
                .isEmpty());
        MirrorMatcher.Wanted cure = MirrorMatcher.fromSpotify(spotify("the cure", "Olivia Rodrigo", 297));
        IPodTrack live = new IPodTrack(
            IPodTrack.Source.SOUNDCLOUD,
            "https://soundcloud.com/olivia-rodrigo-415821690/the-cure-in-the-live-lounge",
            "Olivia Rodrigo - the cure",
            "olivia rodrigo",
            302);
        assertTrue(
            MirrorMatcher.rank(cure, Arrays.asList(live))
                .isEmpty());
    }

    @Test
    void spotifyNaoAceitaVersaoQuinzeSegundosMaisCurta() {
        // "Patient Zero" tem 226 s no Spotify; o upload de 211 s é outra edição.
        MirrorMatcher.Wanted w = MirrorMatcher.fromSpotify(spotify("Patient Zero", "Taylor Swift", 226));
        assertTrue(
            MirrorMatcher.rank(w, Arrays.asList(sc("Taylor Swift- Patient Zero", "aka", 211)))
                .isEmpty());
        assertEquals(
            1,
            MirrorMatcher.rank(w, Arrays.asList(sc("Taylor Swift - Patient Zero", "aka", 224)))
                .size());
    }

    @Test
    void tituloSoDeRuidoNaoCasaComNada() {
        MirrorMatcher.Wanted w = MirrorMatcher.fromYouTube(youtube("(Official Video) HD", "", 200));
        assertTrue(
            MirrorMatcher.rank(w, Arrays.asList(sc("Qualquer coisa", "x", 200)))
                .isEmpty());
    }

    @Test
    void versoesAceleradasEComEfeito() {
        MirrorMatcher.Wanted type = MirrorMatcher.fromYouTube(
            youtube("Real Boston Richey - The Type (Official Music Video) ft. YTB Fatt", "Real Boston Richey", 212));
        assertTrue(
            MirrorMatcher
                .rank(
                    type,
                    Arrays.asList(sc("Real Boston Richey x YTB Fatt - The Type (FAST)", "DJ Fetti Fee Fonks", 179)))
                .isEmpty());
        MirrorMatcher.Wanted diabla = MirrorMatcher.fromYouTube(youtube("La Diabla", "Xavi Oficial", 173));
        assertEquals("Xavi La Diabla", diabla.query);
        assertTrue(
            MirrorMatcher.rank(diabla, Arrays.asList(sc("Xavi - La Diabla (Concert Hall)", "Concert Hall Man", 172)))
                .isEmpty());
        assertEquals(
            1,
            MirrorMatcher.rank(diabla, Arrays.asList(sc("Xavi - La Diabla", "xavi", 173)))
                .size());
        // A palavra no nome da música não é versão: "Fast Car" pode tocar.
        MirrorMatcher.Wanted car = MirrorMatcher.fromSpotify(spotify("Fast Car", "Tracy Chapman", 296));
        assertEquals(
            1,
            MirrorMatcher.rank(car, Arrays.asList(sc("Tracy Chapman - Fast Car", "fan", 296)))
                .size());
    }
}
