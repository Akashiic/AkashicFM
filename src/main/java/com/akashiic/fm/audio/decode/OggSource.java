package com.akashiic.fm.audio.decode;

import java.io.IOException;
import java.io.InputStream;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

/**
 * OGG com Vorbis (JOrbis) ou Opus (Concentus).
 * Suporta streams encadeados: muitos servidores Icecast abrem um stream lógico novo
 * (página BOS com cabeçalhos novos) a cada troca de música. Decoders que ignoram isso
 * param de tocar quando a música muda.
 */
final class OggSource implements PcmSource {

    private static final int READ_CHUNK = 4096;

    private final InputStream in;
    private final boolean opus;
    private final SyncState sync = new SyncState();
    private StreamState stream = new StreamState();
    private final Page page = new Page();
    private final Packet packet = new Packet();

    // Vorbis
    private Info info;
    private Comment comment;
    private DspState dsp;
    private Block block;
    private int vorbisHeadersLeft;
    private final float[][][] pcmRef = new float[1][][];
    private int[] pcmIndex;

    // Opus
    private OpusDecoder opusDecoder;
    private int opusPreSkip;
    private int opusHeadersLeft;

    private int serial = Integer.MIN_VALUE;
    private int sampleRate;
    private int channels;
    private short[] pending = new short[0];
    private int pendingOff;
    private int pendingLen;
    private boolean eof;
    private int streamsSeen;
    private int resyncs;
    private boolean awaitingBos;
    private long skippedWhileAwaiting;
    private static final long MAX_SKIP_WAITING_BOS = 2L * 1024 * 1024;

    OggSource(InputStream in, boolean opus) throws IOException {
        this.in = in;
        this.opus = opus;
        sync.init();
        if (!fill()) throw new IOException("stream OGG sem áudio");
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public String codec() {
        return (opus ? "ogg/opus" : "ogg/vorbis") + (streamsSeen > 1 ? " (" + streamsSeen + " streams encadeados)" : "")
            + (resyncs > 0 ? " [" + resyncs + " ressincronizações]" : "");
    }

    @Override
    public int read(short[] out, int off, int len) throws IOException {
        if (pendingLen == 0 && !fill()) return -1;
        int n = Math.min(len, pendingLen);
        System.arraycopy(pending, pendingOff, out, off, n);
        pendingOff += n;
        pendingLen -= n;
        return n;
    }

    /** Lê páginas até produzir PCM. Devolve false no fim do stream. */
    private boolean fill() throws IOException {
        while (!eof) {
            int r = sync.pageout(page);
            if (r == 0) {
                int idx = sync.buffer(READ_CHUNK);
                int n = in.read(sync.data, idx, READ_CHUNK);
                if (n == -1) {
                    eof = true;
                    break;
                }
                sync.wrote(n);
                continue;
            }
            if (r < 0) continue; // buraco no stream: o JOrbis já ressincronizou
            if (page.bos() != 0) {
                startLogicalStream(page.serialno());
                awaitingBos = false;
                skippedWhileAwaiting = 0;
            } else if (page.serialno() != serial && !awaitingBos) {
                // stream lógico novo sem cabeçalhos: sem eles não dá para decodificar, espera o próximo BOS
                awaitingBos = true;
                resyncs++;
            }
            if (awaitingBos) {
                skippedWhileAwaiting += page.header_len + page.body_len;
                if (skippedWhileAwaiting > MAX_SKIP_WAITING_BOS)
                    throw new IOException("stream OGG sem cabeçalhos por tempo demais");
                continue;
            }
            stream.pagein(page);
            int pr;
            while ((pr = stream.packetout(packet)) != 0) {
                if (pr < 0) continue; // buraco dentro do stream lógico: o próximo pacote ainda vale
                try {
                    if (opus) handleOpusPacket();
                    else handleVorbisPacket();
                } catch (HeaderException e) {
                    awaitingBos = true; // cabeçalho ruim: descarta este stream lógico e espera o próximo
                    resyncs++;
                    break;
                }
            }
            if (pendingLen > 0) return true;
        }
        return pendingLen > 0;
    }

    private void startLogicalStream(int newSerial) {
        serial = newSerial;
        streamsSeen++;
        // instância nova: StreamState.init() do JOrbis reaproveitado mantém pacotes e pageno do stream
        // anterior, e o primeiro cabeçalho do stream novo sai fora de ordem
        stream = new StreamState();
        stream.init(newSerial);
        if (opus) {
            opusHeadersLeft = 2;
            opusDecoder = null;
        } else {
            info = new Info();
            comment = new Comment();
            info.init();
            comment.init();
            vorbisHeadersLeft = 3;
            dsp = null;
        }
    }

    private boolean handleVorbisPacket() throws IOException {
        if (vorbisHeadersLeft > 0) {
            if (info.synthesis_headerin(comment, packet) < 0) throw new HeaderException("cabeçalho Vorbis inválido");
            if (--vorbisHeadersLeft == 0) {
                dsp = new DspState();
                dsp.synthesis_init(info);
                block = new Block(dsp);
                sampleRate = info.rate;
                channels = info.channels;
                pcmIndex = new int[info.channels];
            }
            return false;
        }
        if (block.synthesis(packet) != 0) return false;
        dsp.synthesis_blockin(block);
        int samples;
        while ((samples = dsp.synthesis_pcmout(pcmRef, pcmIndex)) > 0) {
            float[][] pcm = pcmRef[0];
            ensurePending(samples * channels);
            int base = pendingOff + pendingLen;
            for (int i = 0; i < samples; i++) {
                for (int c = 0; c < channels; c++) {
                    float v = pcm[c][pcmIndex[c] + i];
                    int s = (int) (v * 32767f);
                    pending[base + i * channels + c] = (short) Math.max(-32768, Math.min(32767, s));
                }
            }
            pendingLen += samples * channels;
            dsp.synthesis_read(samples);
        }
        return true;
    }

    private boolean handleOpusPacket() throws IOException {
        byte[] data = packet.packet_base;
        int off = packet.packet;
        int len = packet.bytes;
        if (opusHeadersLeft == 2) {
            if (len < 19
                || !new String(data, off, 8, java.nio.charset.StandardCharsets.ISO_8859_1).equals("OpusHead")) {
                throw new HeaderException("cabeçalho OpusHead ausente");
            }
            channels = Math.min(2, data[off + 9] & 0xFF);
            opusPreSkip = (data[off + 10] & 0xFF) | (data[off + 11] & 0xFF) << 8;
            sampleRate = 48000; // Opus decodifica sempre em 48 kHz aqui
            try {
                opusDecoder = new OpusDecoder(48000, Math.max(1, channels));
            } catch (OpusException e) {
                throw new HeaderException("OpusHead com canais inválidos");
            }
            opusHeadersLeft = 1;
            return false;
        }
        if (opusHeadersLeft == 1) { // OpusTags
            opusHeadersLeft = 0;
            return false;
        }
        int maxFrame = 5760; // 120 ms a 48 kHz
        ensurePending(maxFrame * channels);
        int base = pendingOff + pendingLen;
        int decoded;
        try {
            decoded = opusDecoder.decode(data, off, len, pending, base, maxFrame, false);
        } catch (OpusException e) {
            return false; // pacote ruim: pula
        }
        int skip = Math.min(opusPreSkip, decoded);
        opusPreSkip -= skip;
        if (skip > 0) System.arraycopy(pending, base + skip * channels, pending, base, (decoded - skip) * channels);
        pendingLen += (decoded - skip) * channels;
        return true;
    }

    private void ensurePending(int extra) {
        if (pendingOff > 0 && pendingLen > 0) {
            System.arraycopy(pending, pendingOff, pending, 0, pendingLen);
        }
        if (pendingLen == 0 || pendingOff > 0) pendingOff = 0;
        if (pending.length < pendingLen + extra) {
            short[] bigger = new short[Math.max(pending.length * 2, pendingLen + extra)];
            System.arraycopy(pending, 0, bigger, 0, pendingLen);
            pending = bigger;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Cabeçalho ruim num stream lógico: recuperável esperando o próximo BOS. */
    private static final class HeaderException extends IOException {

        HeaderException(String msg) {
            super(msg);
        }
    }
}
