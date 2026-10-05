package com.akashiic.fm.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileIPodPlayer;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.content.TileTransmitter;

/**
 * Linhas de informação de uma rádio ou caixa, já traduzidas, para o WAILA (e qualquer outro painel). Não depende
 * do WAILA: o provider só repassa. Tudo que aparece aqui já está no cliente pelo pacote do bloco.
 */
public final class RadioInfo {

    private RadioInfo() {}

    public static List<String> lines(TileRadio radio) {
        RadioState s = radio.state;
        List<String> out = new ArrayList<>();
        boolean tuned = s.mode == TuneMode.FREQUENCY;
        if (tuned) out.add(t("akashicfm.waila.tuned", Frequency.format(s.frequency)));
        if (s.playing && !s.effectiveUrl()
            .isEmpty()) {
            out.add("§a" + t("akashicfm.waila.playing", NowPlaying.station(radio)));
            if (tuned) out.add(t("akashicfm.waila.signal", s.signal));
            String title = NowPlaying.title(radio);
            if (!title.isEmpty()) out.add("♪ " + title);
        } else if (!s.playing) {
            out.add("§7" + t("akashicfm.gui.status.stopped"));
        }
        out.add(t("akashicfm.gui.volume", s.volume) + " · " + t("akashicfm.gui.range", s.range));
        out.add(t("akashicfm.waila.transport", t("akashicfm.gui.transport." + s.transport.name())));
        String owner = s.ownerName.isEmpty() ? t("akashicfm.gui.no_owner") : t("akashicfm.gui.owner", s.ownerName);
        out.add(t("akashicfm.waila.access", t("akashicfm.gui.access." + s.access.name())) + " · " + owner);
        if (!s.speakers.isEmpty()) out.add(t("akashicfm.waila.speakers", s.speakers.size()));
        if (s.playlist && !tuned && !s.stations.isEmpty()) out.add(t("akashicfm.waila.playlist", s.stations.size()));
        if (radio instanceof TileIPodPlayer)
            out.add(t("akashicfm.ipod.queue", ((TileIPodPlayer) radio).ipod.queue.size()));
        if (!s.status.isEmpty()) out.add("§e" + status(s.status));
        if (ClientMutes.radioMuted(radio.dimension(), radio.pos(), s.effectiveUrl())) {
            out.add("§6" + t("akashicfm.waila.muted"));
        }
        return out;
    }

    public static List<String> lines(TileTransmitter transmitter) {
        TransmitterState s = transmitter.state;
        List<String> out = new ArrayList<>();
        String freq = Frequency.format(s.frequency);
        if (s.active()) out.add("§a" + t("akashicfm.waila.broadcasting", freq));
        else out.add("§7" + t("akashicfm.waila.not_broadcasting", freq));
        if (!s.name.isEmpty()) out.add(t("akashicfm.waila.station_name", s.name));
        if (!s.url.isEmpty()) out.add(t("akashicfm.waila.source", NowPlaying.hostOf(s.url)));
        if (!s.nowPlaying.isEmpty()) out.add("♪ " + s.nowPlaying);
        out.add(t("akashicfm.waila.coverage", s.range, s.antennas));
        if (s.energyRequired) {
            out.add(
                (s.powered ? "" : "§c") + t("akashicfm.waila.energy", s.energy, s.energyCapacity)
                    + (s.powered ? "" : " · " + t("akashicfm.gui.transmitter.no_energy")));
        }
        String owner = s.ownerName.isEmpty() ? t("akashicfm.gui.no_owner") : t("akashicfm.gui.owner", s.ownerName);
        out.add(t("akashicfm.waila.access", t("akashicfm.gui.access." + s.access.name())) + " · " + owner);
        if (ClientMutes.urlMuted(s.url)) out.add("§6" + t("akashicfm.waila.station_muted"));
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
        if (speaker.linkedRadio == null) {
            out.add("§7" + t("akashicfm.waila.speaker.unlinked"));
            return out;
        }
        Pos r = speaker.linkedRadio;
        World world = speaker.getWorldObj();
        TileEntity te = world == null ? null : world.getTileEntity(r.x, r.y, r.z);
        // "Ligada à rádio" ou "ao iPod Player"; com o bloco fora do alcance do cliente, rádio (o que era antes).
        out.add(
            t(
                te instanceof TileIPodPlayer ? "akashicfm.waila.speaker.linked_ipod" : "akashicfm.waila.speaker.linked",
                r.x,
                r.y,
                r.z));
        if (world != null) {
            String url = te instanceof TileRadio ? ((TileRadio) te).state.effectiveUrl() : null;
            if (ClientMutes.radioMuted(world.provider.dimensionId, r, url)) out.add("§6" + t("akashicfm.waila.muted"));
        }
        return out;
    }

    /** Status no formato "chave|argumento" (o mesmo da GUI). */
    private static String status(String raw) {
        int bar = raw.indexOf('|');
        if (bar < 0) return t(raw);
        // Os do iPod podem ter vários argumentos ("chave|a|b").
        if (raw.startsWith("akashicfm.ipod.")) {
            String[] parts = raw.split("\\|", -1);
            Object[] args = new Object[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            return t(parts[0], args);
        }
        return t(raw.substring(0, bar), raw.substring(bar + 1));
    }

    private static String t(String key, Object... args) {
        return args.length == 0 ? StatCollector.translateToLocal(key)
            : StatCollector.translateToLocalFormatted(key, args);
    }
}
