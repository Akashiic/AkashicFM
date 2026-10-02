package com.akashiic.fm.common;

import com.akashiic.fm.AkashicFM;
import com.gtnewhorizon.gtnhlib.config.Config;

/** Config do servidor e do cliente (config/akashicfm.cfg). Uma classe por categoria. */
public final class FmConfig {

    private FmConfig() {}

    /**
     * Grupo dos campos que o {@code /fm reload} relê do disco com o servidor rodando (o GTNHLib só recarrega campos
     * marcados com {@code @Config.Reloadable}). Fica de fora o que não pode mudar ao vivo (a latência do relay: os
     * ouvintes já conectados guardam a antiga).
     */
    public static final String RELOAD = "server";

    @Config(modid = AkashicFM.MODID, category = "relay")
    @Config.Comment("O servidor baixa cada estação uma vez e retransmite em Opus, sincronizado, para quem está no alcance.")
    public static final class Relay {

        @Config.Comment("O servidor baixa cada estação uma vez e retransmite em Opus para quem está no alcance. Sincronia real e privacidade para os jogadores.")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public static boolean enabled;

        @Config.Comment("Bitrate do Opus por ouvinte, em kbps. 64 kbps gasta ~8 KB/s de upload do servidor por jogador ouvindo.")
        @Config.DefaultInt(64)
        @Config.RangeInt(min = 24, max = 128)
        @Config.Reloadable(RELOAD)
        public static int opusBitrateKbps;

        @Config.Comment("Máximo de estações diferentes sendo baixadas ao mesmo tempo (cada uma custa ~2% de um núcleo).")
        @Config.DefaultInt(8)
        @Config.RangeInt(min = 1, max = 64)
        @Config.Reloadable(RELOAD)
        public static int maxStations;

        @Config.Comment("Máximo de jogadores recebendo áudio pelo relay ao mesmo tempo, somando todas as estações.")
        @Config.DefaultInt(64)
        @Config.RangeInt(min = 1, max = 1024)
        @Config.Reloadable(RELOAD)
        public static int maxListeners;

        @Config.Comment("Atraso fixo entre o servidor e a reprodução, em ms. Absorve variação de rede para todos tocarem juntos. Vale ao reiniciar o servidor (o /fm reload não muda).")
        @Config.DefaultInt(1500)
        @Config.RangeInt(min = 300, max = 5000)
        public static int latencyTargetMs;
    }

    @Config(modid = AkashicFM.MODID, category = "direct")
    @Config.Comment("Modo direto: cada cliente baixa o stream sozinho (sem custo para o servidor, mas expõe o IP dos jogadores).")
    public static final class Direct {

        @Config.Comment("Permite o modo direto: cada cliente baixa o stream sozinho. Não gasta banda do servidor, mas expõe o IP dos jogadores à URL e a sincronia é aproximada.")
        @Config.DefaultBoolean(false)
        @Config.Reloadable(RELOAD)
        public static boolean enabled;
    }

    @Config(modid = AkashicFM.MODID, category = "policy")
    @Config.Comment("Quais URLs as rádios podem tocar.")
    public static final class Policy {

        @Config.Comment("Domínios permitidos para as URLs (subdomínios incluídos). Vazio = qualquer host público. Endereços internos são sempre recusados.")
        @Config.DefaultStringList({})
        @Config.Reloadable(RELOAD)
        public static String[] allowedHosts;

        @Config.Comment("Aceita qualquer porta acima de 1024 além de 80/443. Portas baixas (exceto 80/443) são sempre recusadas.")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public static boolean allowHighPorts;
    }

    @Config(modid = AkashicFM.MODID, category = "limits")
    @Config.Comment("Limites contra abuso: rádios por jogador e por chunk, caixas, alcance e ações por segundo.")
    public static final class Limits {

        @Config.Comment("Máximo de rádios por jogador.")
        @Config.DefaultInt(16)
        @Config.RangeInt(min = 1, max = 1024)
        @Config.Reloadable(RELOAD)
        public static int maxRadiosPerPlayer;

        @Config.Comment("Máximo de caixas de som ligadas a uma rádio.")
        @Config.DefaultInt(8)
        @Config.RangeInt(min = 0, max = 64)
        @Config.Reloadable(RELOAD)
        public static int maxSpeakersPerRadio;

        @Config.Comment("Distância máxima, em blocos, entre uma caixa de som e a rádio dela.")
        @Config.DefaultInt(32)
        @Config.RangeInt(min = 1, max = 128)
        @Config.Reloadable(RELOAD)
        public static int maxSpeakerDistance;

        @Config.Comment("Máximo de rádios num mesmo chunk.")
        @Config.DefaultInt(4)
        @Config.RangeInt(min = 1, max = 64)
        @Config.Reloadable(RELOAD)
        public static int maxRadiosPerChunk;

        @Config.Comment("Alcance máximo, em blocos, que um jogador pode escolher para a rádio.")
        @Config.DefaultInt(48)
        @Config.RangeInt(min = 4, max = 128)
        @Config.Reloadable(RELOAD)
        public static int maxRange;

        @Config.Comment("Ações por segundo que cada jogador pode mandar às rádios (o excesso é descartado).")
        @Config.DefaultInt(10)
        @Config.RangeInt(min = 1, max = 100)
        @Config.Reloadable(RELOAD)
        public static int actionsPerSecond;
    }

    @Config(modid = AkashicFM.MODID, category = "protection")
    @Config.Comment("Quem pode quebrar rádios e caixas privadas.")
    public static final class Protection {

        @Config.Comment("Impede que outros jogadores (e máquinas) quebrem rádios e caixas privadas. Ops sempre podem.")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public static boolean protectPrivateBlocks;

        @Config.Comment("Ops controlam qualquer rádio, mesmo privada.")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public static boolean opsBypass;
    }

    @Config(modid = AkashicFM.MODID, category = "transmitter")
    @Config.Comment("Transmissores de FM: alcance pelas antenas e energia (EU do IC2/GregTech ou RF).")
    public static final class Transmitter {

        @Config.Comment("Alcance do transmissor sem antenas, em blocos.")
        @Config.DefaultInt(64)
        @Config.RangeInt(min = 8, max = 1024)
        @Config.Reloadable(RELOAD)
        public static int baseRange;

        @Config.Comment("Alcance extra por bloco de antena empilhado em cima do transmissor.")
        @Config.DefaultInt(32)
        @Config.RangeInt(min = 0, max = 512)
        @Config.Reloadable(RELOAD)
        public static int rangePerAntenna;

        @Config.Comment("Máximo de antenas que contam (as de cima disso não somam).")
        @Config.DefaultInt(16)
        @Config.RangeInt(min = 0, max = 64)
        @Config.Reloadable(RELOAD)
        public static int maxAntennas;

        @Config.Comment("Teto do alcance, em blocos, com quantas antenas forem.")
        @Config.DefaultInt(512)
        @Config.RangeInt(min = 8, max = 4096)
        @Config.Reloadable(RELOAD)
        public static int maxRange;

        @Config.Comment("Exige energia para transmitir. Só vale com IC2 (EU, cabos do GregTech) ou um mod de RF instalado; sem eles, nunca exige.")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public static boolean requireEnergy;

        @Config.Comment("Consumo enquanto transmite, em EU por tick.")
        @Config.DefaultInt(8)
        @Config.RangeInt(min = 1, max = 2048)
        @Config.Reloadable(RELOAD)
        public static int euPerTick;

        @Config.Comment("Energia guardada no transmissor, em EU.")
        @Config.DefaultInt(8000)
        @Config.RangeInt(min = 100, max = 1000000)
        @Config.Reloadable(RELOAD)
        public static int energyCapacity;

        @Config.Comment("Entrada máxima de energia, em EU por tick (aceita qualquer tensão: um rádio não explode por isso).")
        @Config.DefaultInt(128)
        @Config.RangeInt(min = 1, max = 100000)
        @Config.Reloadable(RELOAD)
        public static int maxInputPerTick;

        @Config.Comment("Quantos RF valem 1 EU.")
        @Config.DefaultInt(4)
        @Config.RangeInt(min = 1, max = 100)
        @Config.Reloadable(RELOAD)
        public static int rfPerEu;

        @Config.Comment("Máximo de transmissores por jogador.")
        @Config.DefaultInt(4)
        @Config.RangeInt(min = 1, max = 256)
        @Config.Reloadable(RELOAD)
        public static int maxPerPlayer;
    }

    @Config(modid = AkashicFM.MODID, category = "portable")
    @Config.Comment("Rádio portátil (toca do inventário) e fone.")
    public static final class Portable {

        @Config.Comment("Liga o rádio portátil. Desligado, os portáteis ficam mudos.")
        @Config.DefaultBoolean(true)
        @Config.Reloadable(RELOAD)
        public static boolean enabled;

        @Config.Comment("Até onde os outros jogadores ouvem o portátil de alguém (sem fone), em blocos.")
        @Config.DefaultInt(16)
        @Config.RangeInt(min = 4, max = 64)
        @Config.Reloadable(RELOAD)
        public static int range;
    }

    @Config(modid = AkashicFM.MODID, category = "recipes")
    @Config.Comment("Receitas padrão do mod.")
    public static final class Recipes {

        @Config.Comment("Registra as receitas padrão (desligue se o modpack define as próprias).")
        @Config.DefaultBoolean(true)
        @Config.RequiresMcRestart
        public static boolean registerDefaultRecipes;
    }

    @Config(modid = AkashicFM.MODID, category = "client")
    @Config.Comment("Opções deste cliente: volume, quantas rádios tocam ao mesmo tempo e streams diretos.")
    public static final class Client {

        @Config.Comment("Liga o áudio das rádios neste cliente.")
        @Config.DefaultBoolean(true)
        public static boolean enableAudio;

        @Config.Comment("Quantas rádios podem tocar ao mesmo tempo para você (as mais próximas ganham).")
        @Config.DefaultInt(4)
        @Config.RangeInt(min = 1, max = 16)
        public static int maxSimultaneousRadios;

        @Config.Comment("Permite que este cliente baixe streams sozinho quando o servidor usa o modo direto. Desligado, rádios em modo direto ficam mudas para você.")
        @Config.DefaultBoolean(true)
        public static boolean allowDirectStreams;

        @Config.Comment("Volume geral das rádios para você, de 0 a 100 (multiplica o slider de Jukebox/Discos do Minecraft).")
        @Config.DefaultInt(100)
        @Config.RangeInt(min = 0, max = 100)
        public static int radioVolume;

        @Config.Comment("Abafa o som das rádios atrás de paredes: lã abafa muito, pedra bastante, vidro quase nada. Com EFX, as paredes cortam os agudos; sem EFX, só o volume.")
        @Config.DefaultBoolean(true)
        public static boolean enableOcclusion;

        @Config.Comment("Reverb conforme o lugar onde você está: sala de pedra, caverna, campo aberto. Precisa de EFX (OpenAL Soft).")
        @Config.DefaultBoolean(true)
        public static boolean enableReverb;

        @Config.Comment("Mostra acima da barra de itens, como nos discos da jukebox, o que está tocando quando você começa a ouvir uma rádio ou a música muda.")
        @Config.DefaultBoolean(true)
        public static boolean showNowPlaying;

        @Config.Comment("Barras de espectro na tela da rádio e cone das caixas pulsando com a música.")
        @Config.DefaultBoolean(true)
        public static boolean radioVisualizer;

        @Config.Comment("Acústica de blocos específicos: modid:nome=absorção (0 a 1, quanto o bloco abafa o som que o atravessa) ou modid:nome=absorção,amortecimento (quanto a superfície absorve no reverb, 0 a 1, ou -1 para não refletir). Ex.: minecraft:glass=0.3")
        @Config.DefaultStringList({})
        public static String[] acousticOverrides;
    }
}
