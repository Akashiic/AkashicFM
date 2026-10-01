package com.akashiic.fm.common;

import com.akashiic.fm.AkashicFM;
import com.gtnewhorizon.gtnhlib.config.Config;

/** Config do servidor e do cliente (config/akashicfm.cfg). Uma classe por categoria. */
public final class FmConfig {

    private FmConfig() {}

    @Config(modid = AkashicFM.MODID, category = "relay")
    public static final class Relay {

        @Config.Comment("O servidor baixa cada estação uma vez e retransmite em Opus para quem está no alcance. Sincronia real e privacidade para os jogadores.")
        @Config.DefaultBoolean(true)
        public static boolean enabled;

        @Config.Comment("Bitrate do Opus por ouvinte, em kbps. 64 kbps gasta ~8 KB/s de upload do servidor por jogador ouvindo.")
        @Config.DefaultInt(64)
        @Config.RangeInt(min = 24, max = 128)
        public static int opusBitrateKbps;

        @Config.Comment("Máximo de estações diferentes sendo baixadas ao mesmo tempo (cada uma custa ~2% de um núcleo).")
        @Config.DefaultInt(8)
        @Config.RangeInt(min = 1, max = 64)
        public static int maxStations;

        @Config.Comment("Máximo de jogadores recebendo áudio pelo relay ao mesmo tempo, somando todas as estações.")
        @Config.DefaultInt(64)
        @Config.RangeInt(min = 1, max = 1024)
        public static int maxListeners;

        @Config.Comment("Atraso fixo entre o servidor e a reprodução, em ms. Absorve variação de rede para todos tocarem juntos.")
        @Config.DefaultInt(1500)
        @Config.RangeInt(min = 300, max = 5000)
        public static int latencyTargetMs;
    }

    @Config(modid = AkashicFM.MODID, category = "direct")
    public static final class Direct {

        @Config.Comment("Permite o modo direto: cada cliente baixa o stream sozinho. Não gasta banda do servidor, mas expõe o IP dos jogadores à URL e a sincronia é aproximada.")
        @Config.DefaultBoolean(false)
        public static boolean enabled;
    }

    @Config(modid = AkashicFM.MODID, category = "policy")
    public static final class Policy {

        @Config.Comment("Domínios permitidos para as URLs (subdomínios incluídos). Vazio = qualquer host público. Endereços internos são sempre recusados.")
        @Config.DefaultStringList({})
        public static String[] allowedHosts;

        @Config.Comment("Aceita qualquer porta acima de 1024 além de 80/443. Portas baixas (exceto 80/443) são sempre recusadas.")
        @Config.DefaultBoolean(true)
        public static boolean allowHighPorts;
    }

    @Config(modid = AkashicFM.MODID, category = "limits")
    public static final class Limits {

        @Config.Comment("Máximo de rádios por jogador.")
        @Config.DefaultInt(16)
        @Config.RangeInt(min = 1, max = 1024)
        public static int maxRadiosPerPlayer;

        @Config.Comment("Máximo de caixas de som ligadas a uma rádio.")
        @Config.DefaultInt(8)
        @Config.RangeInt(min = 0, max = 64)
        public static int maxSpeakersPerRadio;

        @Config.Comment("Distância máxima, em blocos, entre uma caixa de som e a rádio dela.")
        @Config.DefaultInt(32)
        @Config.RangeInt(min = 1, max = 128)
        public static int maxSpeakerDistance;
    }
}
