package com.akashiic.fm.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.StatCollector;

import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;

/**
 * Linhas de informação de uma rádio ou caixa, já traduzidas, para o WAILA (e qualquer outro painel). Não depende
 * do WAILA: o provider só repassa. Tudo que aparece aqui já está no cliente pelo pacote do bloco.
 */
public final class RadioInfo {

    private RadioInfo() {}

    public static List<String> lines(TileRadio radio) {
        RadioState s = radio.state;
        List<String> out = new ArrayList<>();
        if (s.playing && !s.url.isEmpty()) {
            out.add("§a" + t("akashicfm.waila.playing", NowPlaying.hostOf(s.url)));
            String title = NowPlaying.title(radio);
            if (!title.isEmpty()) out.add("♪ " + title);
        } else {
            out.add("§7" + t("akashicfm.gui.status.stopped"));
        }
        out.add(t("akashicfm.gui.volume", s.volume) + " · " + t("akashicfm.gui.range", s.range));
        out.add(t("akashicfm.waila.transport", t("akashicfm.gui.transport." + s.transport.name())));
        String owner = s.ownerName.isEmpty() ? t("akashicfm.gui.no_owner") : t("akashicfm.gui.owner", s.ownerName);
        out.add(t("akashicfm.waila.access", t("akashicfm.gui.access." + s.access.name())) + " · " + owner);
        if (!s.speakers.isEmpty()) out.add(t("akashicfm.waila.speakers", s.speakers.size()));
        if (!s.status.isEmpty()) out.add("§e" + status(s.status));
        return out;
    }

    public static List<String> lines(TileSpeaker speaker) {
        List<String> out = new ArrayList<>();
        out.add(
            t(
                "akashicfm.waila.speaker.channel",
                t(
                    "akashicfm.channel." + speaker.channel.name()
                        .toLowerCase(java.util.Locale.ROOT))));
        if (speaker.linkedRadio == null) out.add("§7" + t("akashicfm.waila.speaker.unlinked"));
        else out.add(
            t("akashicfm.waila.speaker.linked", speaker.linkedRadio.x, speaker.linkedRadio.y, speaker.linkedRadio.z));
        return out;
    }

    /** Status no formato "chave|argumento" (o mesmo da GUI). */
    private static String status(String raw) {
        int bar = raw.indexOf('|');
        return bar < 0 ? t(raw) : t(raw.substring(0, bar), raw.substring(bar + 1));
    }

    private static String t(String key, Object... args) {
        return args.length == 0 ? StatCollector.translateToLocal(key)
            : StatCollector.translateToLocalFormatted(key, args);
    }
}
