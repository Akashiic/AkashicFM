package com.akashiic.fm.client.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.akashiic.fm.audio.dsp.GainModel;
import com.akashiic.fm.client.ClientRadioRegistry;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.SpeakerChannel;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.content.Facing;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;

/**
 * Decide, a cada tick do cliente, quais rádios tocam e com que ganho em cada fonte. Só rádios carregadas
 * no mundo atual, dentro do alcance, entram; as mais próximas ganham até o limite do config. Tudo que sai
 * desta lista é encerrado na hora (rede fechada, fontes liberadas).
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

    private RadioAudioController() {}

    private static final class Candidate {

        final TileRadio radio;
        final String key;
        final List<EmitterSpec> emitters;
        final double nearest;

        Candidate(TileRadio radio, String key, List<EmitterSpec> emitters, double nearest) {
            this.radio = radio;
            this.key = key;
            this.emitters = emitters;
            this.nearest = nearest;
        }
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
            return;
        }
        // Mesma posição que o Minecraft usa para o listener do OpenAL (posY do jogador no cliente).
        double lx = player.posX, ly = player.posY, lz = player.posZ;

        List<Candidate> candidates = new ArrayList<>();
        for (TileRadio radio : ClientRadioRegistry.snapshot()) {
            if (radio.isInvalid() || radio.getWorldObj() != world) continue;
            RadioState s = radio.state;
            if (!s.playing || s.url.isEmpty() || s.transport != Transport.DIRECT) continue;
            if (!FmConfig.Client.allowDirectStreams) continue;
            // Só conta o que soa de verdade: caixas em chunk que este cliente não tem não entram.
            List<EmitterSpec> emitters = emittersFor(world, radio, lx, ly, lz, records, clientVolume);
            double nearest = Double.MAX_VALUE;
            for (EmitterSpec e : emitters) nearest = Math.min(nearest, Math.sqrt(distSq(e, lx, ly, lz)));
            String key = keyFor(radio);
            double limit = s.range + (engine.has(key) ? HYSTERESIS : 0);
            if (nearest <= limit) candidates.add(new Candidate(radio, key, emitters, nearest));
        }
        Collections.sort(candidates, (a, b) -> Double.compare(a.nearest, b.nearest));
        int max = Math.max(1, FmConfig.Client.maxSimultaneousRadios);

        Set<String> keep = new HashSet<>();
        int budget = MAX_VOICES_TOTAL;
        for (int i = 0; i < candidates.size() && keep.size() < max && budget > 0; i++) {
            Candidate c = candidates.get(i);
            // emittersFor já ordena por distância quando corta; aqui só cabe no que resta do orçamento.
            List<EmitterSpec> emitters = c.emitters.size() <= budget ? c.emitters
                : nearestFirst(c.emitters, lx, ly, lz).subList(0, budget);
            budget -= emitters.size();
            TileRadio radio = c.radio;
            final String url = radio.state.url;
            final String threadName = "AkashicFM-Direct-" + radio.xCoord + "," + radio.yCoord + "," + radio.zCoord;
            engine.touch(c.key, () -> new DirectFeed(url, threadName).start(), emitters);
            keep.add(c.key);
        }
        engine.retainOnly(keep);
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
        double dx = e.x - lx, dy = e.y - ly, dz = e.z - lz;
        return dx * dx + dy * dy + dz * dz;
    }
}
