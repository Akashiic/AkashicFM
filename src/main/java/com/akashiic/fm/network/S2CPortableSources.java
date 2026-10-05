package com.akashiic.fm.network;

import java.util.ArrayList;
import java.util.List;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Os rádios portáteis que este jogador ouve agora (o dele e os dos jogadores perto), lista completa. O servidor manda
 * quando a lista muda e a renova a cada 2 s; o cliente esquece uma lista que deixou de ser renovada (o servidor é
 * quem decide quem ouve, como no relay).
 */
public final class S2CPortableSources implements IMessage {

    static final int MAX_ENTRIES = 32;

    /** Uma fonte: o portátil de um jogador. */
    public static final class Entry {

        /** Entidade do portador. */
        public int entityId;
        /** Muda quando a fonte muda (o cliente recomeça a reprodução no modo direto). */
        public int session;
        public String url = "";
        public Transport transport = Transport.NONE;
        public int volume;
        public int range;
        /** O portador usa fone: só ele ouve, em estéreo e sem posição. */
        public boolean headphones;
        public String title = "";
        // Para a tela do portador: o que o servidor sintonizou e por que não toca (se não toca).
        public TuneMode mode = TuneMode.URL;
        public int frequency = Frequency.DEFAULT;
        public int signal;
        public String station = "";
        public String status = "";
        /**
         * Pés do portador no servidor. O cliente prefere a entidade (posição suave a cada quadro); esta serve quando
         * ele não a vê: o rastreador do 1.7.10 só mostra de novo um jogador teleportado quando ele se mexe. Fica fora
         * da assinatura (andar não gera pacote); vai em toda lista enviada, renovada a cada 2 s.
         */
        public double x, y, z;

        /** Assinatura para o servidor saber se algo mudou (sem comparar campo a campo). */
        public String signature() {
            return entityId + "|"
                + session
                + "|"
                + url
                + "|"
                + transport.ordinal()
                + "|"
                + volume
                + "|"
                + range
                + "|"
                + headphones
                + "|"
                + title
                + "|"
                + mode.ordinal()
                + "|"
                + frequency
                + "|"
                + signal
                + "|"
                + station
                + "|"
                + status;
        }

        void sanitize() {
            url = TextSanitizer.cleanUrl(url == null ? "" : url, RadioLimits.MAX_URL_LENGTH);
            if (transport == null) transport = Transport.NONE;
            volume = RadioLimits.clamp(volume, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
            range = RadioLimits.clamp(range, 0, 256);
            title = TextSanitizer.clean(title == null ? "" : title, RadioLimits.MAX_TITLE_LENGTH);
            if (mode == null) mode = TuneMode.URL;
            frequency = Frequency.clamp(frequency);
            signal = RadioLimits.clamp(signal, 0, 100);
            station = TextSanitizer.clean(station == null ? "" : station, RadioLimits.MAX_SCREEN_TEXT);
            status = TextSanitizer.clean(status == null ? "" : status, RadioLimits.MAX_STATUS_LENGTH);
            x = coord(x);
            y = coord(y);
            z = coord(z);
        }

        private static double coord(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
            return Math.max(-3.0e7, Math.min(3.0e7, v));
        }
    }

    public final List<Entry> entries = new ArrayList<>();

    public S2CPortableSources() {}

    public S2CPortableSources(List<Entry> entries) {
        for (Entry e : entries) {
            if (this.entries.size() >= MAX_ENTRIES) break;
            this.entries.add(e);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = Math.min(buf.readUnsignedByte(), MAX_ENTRIES);
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.entityId = buf.readInt();
            e.session = buf.readInt();
            e.url = ByteBufUtils.readUTF8String(buf);
            e.transport = Transport.values()[Math.min(buf.readUnsignedByte(), Transport.values().length - 1)];
            e.volume = buf.readUnsignedByte();
            e.range = buf.readUnsignedShort();
            e.headphones = buf.readBoolean();
            e.title = ByteBufUtils.readUTF8String(buf);
            e.mode = TuneMode.tunable(buf.readUnsignedByte());
            e.frequency = buf.readShort();
            e.signal = buf.readUnsignedByte();
            e.station = ByteBufUtils.readUTF8String(buf);
            e.status = ByteBufUtils.readUTF8String(buf);
            e.x = buf.readDouble();
            e.y = buf.readFloat();
            e.z = buf.readDouble();
            e.sanitize();
            entries.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int n = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            e.sanitize();
            buf.writeInt(e.entityId);
            buf.writeInt(e.session);
            ByteBufUtils.writeUTF8String(buf, e.url);
            buf.writeByte(e.transport.ordinal());
            buf.writeByte(e.volume);
            buf.writeShort(e.range);
            buf.writeBoolean(e.headphones);
            ByteBufUtils.writeUTF8String(buf, e.title);
            buf.writeByte(e.mode.ordinal());
            buf.writeShort(e.frequency);
            buf.writeByte(e.signal);
            ByteBufUtils.writeUTF8String(buf, e.station);
            ByteBufUtils.writeUTF8String(buf, e.status);
            buf.writeDouble(e.x);
            buf.writeFloat((float) e.y);
            buf.writeDouble(e.z);
        }
    }

    public static final class Handler implements IMessageHandler<S2CPortableSources, IMessage> {

        @Override
        public IMessage onMessage(S2CPortableSources message, MessageContext ctx) {
            AkashicFM.proxy.enqueueClientTask(() -> AkashicFM.proxy.onPortableSources(message));
            return null;
        }
    }
}
