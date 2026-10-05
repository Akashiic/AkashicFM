package com.akashiic.fm.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Para quem buscou: os resultados de uma busca do iPod (título, artista e duração, até {@link #MAX_RESULTS}), ou o
 * motivo de não haver. Sem os links: para escolher, o cliente manda só o índice, e o servidor usa a faixa que guardou.
 * Também leva o que a tela pode oferecer ({@link #flags}); a resposta a {@code HELLO} vem com {@code requestId} 0 e sem
 * faixas.
 */
public final class S2CIPodSearchResults implements IMessage {

    public static final int MAX_RESULTS = 10;
    /** O iPod está ligado no servidor. */
    public static final int FLAG_ENABLED = 1;
    /** Links do Spotify aceitos. */
    public static final int FLAG_SPOTIFY_LINKS = 2;
    /** A busca por nome no Spotify existe (o admin pôs a chave). */
    public static final int FLAG_SPOTIFY_SEARCH = 4;

    /** Um resultado. */
    public static final class Entry {

        public final String title;
        public final String artist;
        public final int durationSec;

        public Entry(String title, String artist, int durationSec) {
            this.title = TextSanitizer.clean(title == null ? "" : title, IPodTrack.MAX_TITLE);
            this.artist = TextSanitizer.clean(artist == null ? "" : artist, IPodTrack.MAX_ARTIST);
            this.durationSec = Math.max(0, Math.min(IPodTrack.MAX_DURATION_SEC, durationSec));
        }

        /** "Artista - Título", como na fila. */
        public String display() {
            return new IPodTrack(IPodTrack.Source.SOUNDCLOUD, "x", title, artist, durationSec).display();
        }
    }

    public int requestId;
    /** O serviço buscado (ordinal de {@link IPodTrack.Source}); null na resposta a HELLO. */
    public IPodTrack.Source service;
    public int flags;
    /** Chave de tradução (argumento depois de '|') quando não há resultados por um motivo; senão vazio. */
    public String status = "";
    public List<Entry> results = Collections.emptyList();

    public S2CIPodSearchResults() {}

    public S2CIPodSearchResults(int requestId, IPodTrack.Source service, int flags, String status,
        List<Entry> results) {
        this.requestId = requestId;
        this.service = service;
        this.flags = flags;
        this.status = status == null ? "" : status;
        this.results = results == null ? Collections.emptyList() : results;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        requestId = Math.max(0, buf.readInt());
        service = IPodTrack.Source.byOrdinal(buf.readUnsignedByte());
        flags = buf.readUnsignedByte() & (FLAG_ENABLED | FLAG_SPOTIFY_LINKS | FLAG_SPOTIFY_SEARCH);
        status = TextSanitizer.clean(ByteBufUtils.readUTF8String(buf), RadioLimits.MAX_STATUS_LENGTH);
        int n = buf.readUnsignedByte();
        List<Entry> list = new ArrayList<>(Math.min(n, MAX_RESULTS));
        for (int i = 0; i < n; i++) {
            String title = ByteBufUtils.readUTF8String(buf);
            String artist = ByteBufUtils.readUTF8String(buf);
            int sec = buf.readInt();
            if (list.size() < MAX_RESULTS) list.add(new Entry(title, artist, sec));
        }
        results = list;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(Math.max(0, requestId));
        buf.writeByte(service == null ? 255 : service.ordinal());
        buf.writeByte(flags & 0xFF);
        ByteBufUtils.writeUTF8String(buf, TextSanitizer.clean(status, RadioLimits.MAX_STATUS_LENGTH));
        int n = Math.min(results.size(), MAX_RESULTS);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) {
            Entry e = results.get(i);
            ByteBufUtils.writeUTF8String(buf, e.title);
            ByteBufUtils.writeUTF8String(buf, e.artist);
            buf.writeInt(e.durationSec);
        }
    }

    public static final class Handler implements IMessageHandler<S2CIPodSearchResults, IMessage> {

        @Override
        public IMessage onMessage(S2CIPodSearchResults message, MessageContext ctx) {
            AkashicFM.proxy.enqueueClientTask(() -> AkashicFM.proxy.onIPodSearchResults(message));
            return null;
        }
    }
}
