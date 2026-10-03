package com.akashiic.fm.client.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.akashiic.fm.audio.dsp.GainModel;
import com.akashiic.fm.audio.spatial.OcclusionTracer;
import com.akashiic.fm.audio.spatial.RoomModel;
import com.akashiic.fm.client.ClientMutes;
import com.akashiic.fm.client.ClientPortables;
import com.akashiic.fm.client.ClientRadioRegistry;
import com.akashiic.fm.client.NowPlaying;
import com.akashiic.fm.client.gui.NowPlayingMessage;
import com.akashiic.fm.client.relay.RelayClient;
import com.akashiic.fm.client.relay.RelayFeed;
import com.akashiic.fm.client.spatial.OcclusionField;
import com.akashiic.fm.client.spatial.RoomProbe;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.NowPlayingTracker;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.SpeakerChannel;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.content.Facing;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.network.S2CPortableSources;

/**
 * Decide, a cada tick do cliente, quais rádios tocam e com que ganho em cada fonte. Só rádios carregadas
 * no mundo atual, dentro do alcance, entram; as mais próximas ganham até o limite do config. Tudo que sai
 * desta lista é encerrado na hora (rede fechada, fontes liberadas).
 * <p>
 * Também calcula a oclusão de cada fonte escolhida ({@link OcclusionField}), enquanto algo toca a sala do
 * ouvinte para o reverb ({@link RoomProbe}), e avisa no HUD o que está tocando na rádio mais alta que o jogador
 * ouve ({@link NowPlayingTracker}).
 */
public final class RadioAudioController {

    /**
     * Para começar precisa estar dentro do alcance; para continuar, até alcance + isto (evita liga/desliga na borda).
     */
    static final double HYSTERESIS = 4.0;
    /** Meia largura do estéreo de um bloco (fontes L e R ficam a ±0,3 do centro). */
    static final double STEREO_HALF_WIDTH = 0.3;
    /** Teto de fontes AL por rádio (as mais próximas do ouvinte ficam). */
    static final int MAX_VOICES_PER_RADIO = 16;
    /**
     * Teto de fontes AL do mod somando todas as rádios. O OpenAL Soft tem 256 no total e o Minecraft (com o
     * Hodgepodge) reserva até 72; sobra folga para outros mods.
     */
    static final int MAX_VOICES_TOTAL = 96;

    private static final NowPlayingTracker NOW_PLAYING = new NowPlayingTracker();

    private RadioAudioController() {}

    /** Uma reprodução possível: uma rádio em modo direto, ou uma estação do relay com as rádios dela. */
    private static final class Candidate {

        final String key;
        final List<EmitterSpec> emitters;
        final double nearest;
        final Supplier<AudioFeed> feed;
        /** Uma rádio desta reprodução (para o título e o nome no aviso), ou null se é só de portáteis. */
        final TileRadio radio;
        /** O portátil desta reprodução quando não há rádio (título e nome no aviso). */
        final S2CPortableSources.Entry portable;

        Candidate(String key, List<EmitterSpec> emitters, double nearest, Supplier<AudioFeed> feed, TileRadio radio,
            S2CPortableSources.Entry portable) {
            this.key = key;
            this.emitters = emitters;
            this.nearest = nearest;
            this.feed = feed;
            this.radio = radio;
            this.portable = portable;
        }
    }

    /** Rádios em RELAY que tocam a mesma estação viram uma reprodução só (com as fontes de todas): sincronia exata. */
    private static final class RelayGroup {

        final RelayFeed feed;
        final List<EmitterSpec> emitters = new ArrayList<>();
        double nearest = Double.MAX_VALUE;
        /** A rádio mais próxima do grupo (todas tocam a mesma estação). */
        TileRadio radio;
        /** O portátil do grupo, quando nenhuma rádio dele toca para este jogador. */
        S2CPortableSources.Entry portable;

        RelayGroup(RelayFeed feed) {
            this.feed = feed;
        }
    }

    /** Chave da reprodução de uma estação do relay neste cliente (uma por estação, não por rádio). */
    public static String relayKey(RelayFeed feed) {
        return "relay:" + feed.stationId();
    }

    /**
     * Chave da reprodução que toca esta rádio neste cliente (no relay, a da estação), ou null se ela não está
     * tocando. A reprodução pode ainda não existir (fora do alcance): a engine responde por ela.
     */
    public static String playbackKey(TileRadio radio) {
        RadioState s = radio.state;
        String url = s.effectiveUrl();
        if (!s.playing || url.isEmpty()) return null;
        if (s.transport == Transport.RELAY) {
            RelayFeed feed = RelayClient.feedForUrl(url);
            return feed == null ? null : relayKey(feed);
        }
        return s.transport == Transport.DIRECT ? keyFor(radio) : null;
    }

    /** Chave da reprodução: muda quando a sessão muda (play, troca de URL ou de transporte). */
    public static String keyFor(TileRadio radio) {
        return radio.dimension() + "@"
            + radio.xCoord
            + ","
            + radio.yCoord
            + ","
            + radio.zCoord
            + "#"
            + radio.state.session;
    }

    public static void tick(Minecraft mc) {
        AudioEngine engine = AudioEngine.INSTANCE;
        World world = mc.theWorld;
        EntityClientPlayerMP player = mc.thePlayer;
        float master = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
        float records = mc.gameSettings.getSoundLevel(SoundCategory.RECORDS);
        int clientVolume = FmConfig.Client.radioVolume;
        // Sem mundo, áudio desligado ou volume zerado: nada toca e nenhum stream fica baixando à toa.
        if (world == null || player == null
            || !FmConfig.Client.enableAudio
            || master <= 0f
            || records <= 0f
            || clientVolume <= 0) {
            if (engine.activeCount() > 0) engine.stopAll();
            OcclusionField.INSTANCE.clear();
            RoomProbe.INSTANCE.reset();
            NOW_PLAYING.update(null, null, null, 0f, System.currentTimeMillis());
            return;
        }
        // Mesma posição que o Minecraft usa para o listener do OpenAL (posY do jogador no cliente).
        double lx = player.posX, ly = player.posY, lz = player.posZ;

        List<Candidate> candidates = new ArrayList<>();
        Map<String, RelayGroup> relayGroups = new HashMap<>();
        for (TileRadio radio : ClientRadioRegistry.snapshot()) {
            if (radio.isInvalid() || radio.getWorldObj() != world) continue;
            RadioState s = radio.state;
            // Sintonizada, toca a URL do transmissor ouvido (vazia = sem sinal, silêncio).
            final String url = s.effectiveUrl();
            if (!s.playing || url.isEmpty()) continue;
            // Silenciada por este jogador (tecla): só as fontes dela saem; o grupo do relay segue com as outras.
            if (ClientMutes.radioMuted(radio.dimension(), radio.pos(), url)) continue;
            if (s.transport == Transport.DIRECT && !FmConfig.Client.allowDirectStreams) continue;
            RelayFeed relay = null;
            if (s.transport == Transport.RELAY) {
                relay = RelayClient.feedForUrl(url);
                if (relay == null) continue; // o servidor não está mandando esta estação para nós
            } else if (s.transport != Transport.DIRECT) {
                continue;
            }
            // Só conta o que soa de verdade: caixas em chunk que este cliente não tem não entram.
            List<EmitterSpec> emitters = emittersFor(world, radio, lx, ly, lz, records, clientVolume);
            double nearest = Double.MAX_VALUE;
            for (EmitterSpec e : emitters) nearest = Math.min(nearest, Math.sqrt(distSq(e, lx, ly, lz)));
            String key = relay != null ? relayKey(relay) : keyFor(radio);
            double limit = s.range + (engine.has(key) ? HYSTERESIS : 0);
            if (nearest > limit) continue;
            if (relay != null) {
                final RelayFeed feed = relay;
                RelayGroup g = relayGroups.computeIfAbsent(key, k -> new RelayGroup(feed));
                g.emitters.addAll(emitters);
                if (nearest < g.nearest) {
                    g.nearest = nearest;
                    g.radio = radio;
                }
            } else {
                final String threadName = "AkashicFM-Direct-" + radio.xCoord + "," + radio.yCoord + "," + radio.zCoord;
                candidates.add(
                    new Candidate(key, emitters, nearest, () -> new DirectFeed(url, threadName).start(), radio, null));
            }
        }
        addPortables(world, player, lx, ly, lz, records, clientVolume, engine, candidates, relayGroups);
        for (Map.Entry<String, RelayGroup> e : relayGroups.entrySet()) {
            RelayGroup g = e.getValue();
            List<EmitterSpec> emitters = g.emitters.size() <= MAX_VOICES_PER_RADIO ? g.emitters
                : new ArrayList<>(nearestFirst(g.emitters, lx, ly, lz).subList(0, MAX_VOICES_PER_RADIO));
            final RelayFeed feed = g.feed;
            candidates.add(
                new Candidate(
                    e.getKey(),
                    emitters,
                    g.nearest,
                    () -> feed,
                    g.radio,
                    g.radio == null ? g.portable : null));
        }
        Collections.sort(candidates, (a, b) -> Double.compare(a.nearest, b.nearest));
        int max = Math.max(1, FmConfig.Client.maxSimultaneousRadios);

        Set<String> keep = new HashSet<>();
        List<Candidate> chosen = new ArrayList<>();
        List<List<EmitterSpec>> chosenEmitters = new ArrayList<>();
        int budget = MAX_VOICES_TOTAL;
        int total = 0;
        for (int i = 0; i < candidates.size() && keep.size() < max && budget > 0; i++) {
            Candidate c = candidates.get(i);
            // emittersFor já ordena por distância quando corta; aqui só cabe no que resta do orçamento.
            List<EmitterSpec> emitters = c.emitters.size() <= budget ? c.emitters
                : nearestFirst(c.emitters, lx, ly, lz).subList(0, budget);
            budget -= emitters.size();
            total += emitters.size();
            chosen.add(c);
            chosenEmitters.add(emitters);
            keep.add(c.key);
        }

        // Oclusão de todas as fontes escolhidas de uma vez: o agendador reparte o orçamento de raios entre elas.
        // As presas a quem ouve (portátil de quem carrega) não têm oclusão: ficam fora.
        int inWorld = 0;
        for (List<EmitterSpec> list : chosenEmitters) for (EmitterSpec e : list) if (!e.relative) inWorld++;
        double[] xyz = new double[inWorld * 3];
        int k = 0;
        for (List<EmitterSpec> list : chosenEmitters) for (EmitterSpec e : list) {
            if (e.relative) continue;
            xyz[k++] = e.x;
            xyz[k++] = e.y;
            xyz[k++] = e.z;
        }
        double[] occlusion = OcclusionField.INSTANCE.resolve(world, lx, ly, lz, xyz);
        k = 0;
        Candidate loudest = null;
        float loudestGain = 0f;
        for (int i = 0; i < chosen.size(); i++) {
            List<EmitterSpec> list = chosenEmitters.get(i);
            List<EmitterSpec> occluded = new ArrayList<>(list.size());
            Candidate c = chosen.get(i);
            for (EmitterSpec e : list) {
                EmitterSpec o = e.relative ? e : e.withOcclusion(occlusion[k++]);
                occluded.add(o);
            }
            engine.touch(c.key, c.feed, occluded);
            // Aviso "tocando agora": só reprodução que já soa (não a que ainda conecta ou enche o buffer).
            if (!engine.isPlaying(c.key)) continue;
            for (EmitterSpec o : occluded) {
                // Ganho percebido aproximado (com o abafado), só para escolher a rádio do aviso.
                float heard = o.gain * (float) OcclusionTracer.directGain(o.occlusion);
                if (heard > loudestGain) {
                    loudestGain = heard;
                    loudest = c;
                }
            }
        }
        engine.retainOnly(keep);
        announceNowPlaying(loudest, loudestGain);

        // Reverb: a sala de quem ouve, só enquanto alguma rádio toca.
        if (!keep.isEmpty() && FmConfig.Client.enableReverb) {
            engine.setRoom(RoomProbe.INSTANCE.tick(world, lx, ly, lz), true);
        } else {
            RoomProbe.INSTANCE.reset();
            engine.setRoom(RoomModel.DRY, false);
        }
    }

    /** Aviso "tocando agora" da rádio (ou portátil) mais alta ouvida (o tracker decide quando). */
    private static void announceNowPlaying(Candidate loudest, float gain) {
        long now = System.currentTimeMillis();
        String title = null, station = null, sub = "";
        if (loudest != null && loudest.radio != null) {
            title = NowPlaying.title(loudest.radio);
            station = NowPlaying.station(loudest.radio);
            RadioState s = loudest.radio.state;
            String dial = NowPlaying.dial(s);
            sub = s.screenText.isEmpty() ? dial : dial.isEmpty() ? s.screenText : dial + " · " + s.screenText;
        } else if (loudest != null && loudest.portable != null) {
            S2CPortableSources.Entry p = loudest.portable;
            title = NowPlaying.portableTitle(p, loudest.key);
            station = NowPlaying.portableStation(p);
            sub = NowPlaying.portableDial(p);
        }
        String show = NOW_PLAYING.update(loudest == null ? null : loudest.key, title, station, gain, now);
        if (show != null && FmConfig.Client.showNowPlaying) NowPlayingMessage.INSTANCE.show(show, sub, now);
    }

    /** Chave da reprodução de um portátil no modo direto (uma por portador, muda com a fonte). */
    public static String portableKey(S2CPortableSources.Entry p) {
        return "portable:" + p.entityId + "#" + p.session;
    }

    /** Altura das fontes do portátil de outro jogador acima dos pés (mais ou menos a mão/peito). */
    static final double PORTABLE_HEIGHT = 1.2;

    /**
     * Os portáteis que o servidor diz que este jogador ouve. O próprio: fonte presa a quem ouve, no centro (com o
     * reverb da sala) ou, de fone, um par estéreo a ±{@link #STEREO_HALF_WIDTH} sem filtro nem reverb. O de outro
     * jogador: uma fonte no corpo dele, com oclusão, dentro do alcance. No relay entram no grupo da estação (as
     * mesmas reproduções das rádios: tudo sincronizado).
     */
    private static void addPortables(World world, EntityClientPlayerMP player, double lx, double ly, double lz,
        float records, int clientVolume, AudioEngine engine, List<Candidate> candidates,
        Map<String, RelayGroup> relayGroups) {
        for (S2CPortableSources.Entry p : ClientPortables.current(System.currentTimeMillis())) {
            if (p.url.isEmpty()) continue;
            if (p.transport == Transport.DIRECT && !FmConfig.Client.allowDirectStreams) continue;
            boolean self = p.entityId == player.getEntityId();
            Entity carrier = self ? player : world.getEntityByID(p.entityId);
            if (!self) {
                // O portátil de outro jogador silenciado (pelo UUID), ou tocando uma estação silenciada. O seu
                // próprio portátil não: foi você que ligou.
                if (carrier instanceof EntityPlayer) ClientMutes.sawCarrier(p.entityId, carrier.getUniqueID());
                if (ClientMutes.carrierMuted(p.entityId) || ClientMutes.urlMuted(p.url)) continue;
            }
            RelayFeed relay = null;
            if (p.transport == Transport.RELAY) {
                relay = RelayClient.feedForUrl(p.url);
                if (relay == null) continue;
            } else if (p.transport != Transport.DIRECT) {
                continue;
            }
            List<EmitterSpec> emitters = new ArrayList<>(2);
            double nearest;
            if (self) {
                nearest = 0;
                float gain = GainModel.sourceGain(records, clientVolume, p.volume, 0, Math.max(1, p.range), 1.0);
                if (p.headphones) {
                    emitters.add(EmitterSpec.relative(-STEREO_HALF_WIDTH, 0, 0, SpeakerChannel.LEFT, gain, true));
                    emitters.add(EmitterSpec.relative(STEREO_HALF_WIDTH, 0, 0, SpeakerChannel.RIGHT, gain, true));
                } else {
                    emitters.add(EmitterSpec.relative(0, 0, 0, SpeakerChannel.MIX, gain, false));
                }
            } else {
                if (p.headphones) continue; // o servidor já não manda; por garantia
                // A entidade dá a posição suave; sem ela (o rastreador do 1.7.10 não mostra de novo um jogador
                // teleportado parado), a posição que o servidor mandou com a lista.
                double x = carrier != null ? carrier.posX : p.x;
                double y = (carrier != null ? carrier.boundingBox.minY : p.y) + PORTABLE_HEIGHT;
                double z = carrier != null ? carrier.posZ : p.z;
                double dx = x - lx, dy = y - ly, dz = z - lz;
                nearest = Math.sqrt(dx * dx + dy * dy + dz * dz);
                float gain = GainModel.sourceGain(records, clientVolume, p.volume, nearest, Math.max(1, p.range), 1.0);
                emitters.add(new EmitterSpec(x, y, z, SpeakerChannel.MIX, gain));
            }
            String key = relay != null ? relayKey(relay) : portableKey(p);
            double limit = p.range + (engine.has(key) ? HYSTERESIS : 0);
            if (!self && nearest > limit) continue;
            if (relay != null) {
                final RelayFeed feed = relay;
                RelayGroup g = relayGroups.computeIfAbsent(key, k -> new RelayGroup(feed));
                g.emitters.addAll(emitters);
                // O aviso usa a rádio do grupo, se houver; senão o portátil mais perto.
                if (g.radio == null && (g.portable == null || nearest < g.nearest)) g.portable = p;
                g.nearest = Math.min(g.nearest, nearest);
            } else {
                final String url = p.url;
                final String threadName = "AkashicFM-Direct-portable-" + p.entityId;
                candidates
                    .add(new Candidate(key, emitters, nearest, () -> new DirectFeed(url, threadName).start(), null, p));
            }
        }
    }

    /** Esquece o que já foi anunciado (desconexão). */
    public static void resetNowPlaying() {
        NOW_PLAYING.reset();
        NowPlayingMessage.INSTANCE.reset();
    }

    private static List<EmitterSpec> nearestFirst(List<EmitterSpec> emitters, double lx, double ly, double lz) {
        List<EmitterSpec> sorted = new ArrayList<>(emitters);
        Collections.sort(sorted, (a, b) -> Double.compare(distSq(a, lx, ly, lz), distSq(b, lx, ly, lz)));
        return sorted;
    }

    /** Fontes da rádio (par estéreo) e das caixas carregadas, com o ganho de cada uma para este ouvinte. */
    static List<EmitterSpec> emittersFor(World world, TileRadio radio, double lx, double ly, double lz, float records,
        int clientVolume) {
        RadioState s = radio.state;
        List<EmitterSpec> out = new ArrayList<>();
        addEmitter(
            out,
            radio.pos(),
            Facing.sanitize(radio.getBlockMetadata()),
            SpeakerChannel.STEREO,
            s,
            lx,
            ly,
            lz,
            records,
            clientVolume);
        for (Pos p : s.speakers) {
            if (!world.blockExists(p.x, p.y, p.z)) continue; // caixa em chunk que o cliente não tem: não toca
            TileEntity te = world.getTileEntity(p.x, p.y, p.z);
            if (!(te instanceof TileSpeaker)) continue;
            addEmitter(
                out,
                p,
                Facing.sanitize(world.getBlockMetadata(p.x, p.y, p.z)),
                ((TileSpeaker) te).channel,
                s,
                lx,
                ly,
                lz,
                records,
                clientVolume);
        }
        if (out.size() > MAX_VOICES_PER_RADIO)
            out = new ArrayList<>(nearestFirst(out, lx, ly, lz).subList(0, MAX_VOICES_PER_RADIO));
        return out;
    }

    private static void addEmitter(List<EmitterSpec> out, Pos block, int facing, SpeakerChannel channel, RadioState s,
        double lx, double ly, double lz, float records, int clientVolume) {
        double cx = block.centerX(), cy = block.centerY(), cz = block.centerZ();
        if (channel == SpeakerChannel.STEREO) {
            double[] r = Facing.rightVector(facing);
            add(
                out,
                cx - r[0] * STEREO_HALF_WIDTH,
                cy,
                cz - r[2] * STEREO_HALF_WIDTH,
                SpeakerChannel.LEFT,
                s,
                lx,
                ly,
                lz,
                records,
                clientVolume);
            add(
                out,
                cx + r[0] * STEREO_HALF_WIDTH,
                cy,
                cz + r[2] * STEREO_HALF_WIDTH,
                SpeakerChannel.RIGHT,
                s,
                lx,
                ly,
                lz,
                records,
                clientVolume);
        } else {
            add(out, cx, cy, cz, channel, s, lx, ly, lz, records, clientVolume);
        }
    }

    private static void add(List<EmitterSpec> out, double x, double y, double z, SpeakerChannel channel, RadioState s,
        double lx, double ly, double lz, float records, int clientVolume) {
        double dx = x - lx, dy = y - ly, dz = z - lz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float gain = GainModel.sourceGain(records, clientVolume, s.volume, d, s.range, 1.0);
        out.add(new EmitterSpec(x, y, z, channel, gain));
    }

    private static double distSq(EmitterSpec e, double lx, double ly, double lz) {
        if (e.relative) return 0; // presa a quem ouve: sempre a mais perto
        double dx = e.x - lx, dy = e.y - ly, dz = e.z - lz;
        return dx * dx + dy * dy + dz * dz;
    }
}
