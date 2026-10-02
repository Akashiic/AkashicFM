package com.akashiic.fm.server;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Permissions;
import com.akashiic.fm.common.Pos;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;

/** Vínculo entre caixas e rádios, sempre decidido no servidor. */
public final class SpeakerLinks {

    private SpeakerLinks() {}

    /** Resultado do vínculo: chave de tradução e argumento. */
    public static final class Result {

        public final boolean ok;
        public final String key;
        public final String arg;

        Result(boolean ok, String key, String arg) {
            this.ok = ok;
            this.key = key;
            this.arg = arg;
        }
    }

    public static boolean canAdminSpeaker(TileSpeaker speaker, EntityPlayer player) {
        if (player == null || player instanceof FakePlayer) return false;
        if (speaker.owner == null || speaker.isOwner(player.getUniqueID())) return true;
        return FmConfig.Protection.opsBypass && Permissions.isOp(player);
    }

    public static Result link(EntityPlayer player, World world, Pos speakerPos, TileRadio radio) {
        if (!world.blockExists(speakerPos.x, speakerPos.y, speakerPos.z)) {
            return new Result(false, "akashicfm.tuner.speaker_missing", "");
        }
        TileEntity te = world.getTileEntity(speakerPos.x, speakerPos.y, speakerPos.z);
        if (!(te instanceof TileSpeaker)) return new Result(false, "akashicfm.tuner.speaker_missing", "");
        TileSpeaker speaker = (TileSpeaker) te;
        Pos radioPos = radio.pos();
        int maxDist = Math.max(1, FmConfig.Limits.maxSpeakerDistance);
        if (speakerPos.distanceSqTo(radioPos) > (double) maxDist * maxDist) {
            return new Result(false, "akashicfm.tuner.too_far", String.valueOf(maxDist));
        }
        if (!Permissions.canAdmin(radio.state, player)) return new Result(false, "akashicfm.notice.no_permission", "");
        if (!canAdminSpeaker(speaker, player)) return new Result(false, "akashicfm.tuner.speaker_not_yours", "");
        RadioState s = radio.state;
        if (radioPos.equals(speaker.linkedRadio) && s.speakers.contains(speakerPos)) {
            return new Result(false, "akashicfm.tuner.already_linked", "");
        }
        int cap = RadioLimits.clamp(FmConfig.Limits.maxSpeakersPerRadio, 0, RadioLimits.MAX_SPEAKERS_HARD);
        if (s.speakers.size() >= cap) return new Result(false, "akashicfm.tuner.radio_full", String.valueOf(cap));

        // Uma caixa pertence a uma rádio só: tira da anterior, se ela estiver carregada.
        if (speaker.linkedRadio != null && !radioPos.equals(speaker.linkedRadio)) {
            Pos old = speaker.linkedRadio;
            if (world.blockExists(old.x, old.y, old.z)) {
                TileEntity oldTe = world.getTileEntity(old.x, old.y, old.z);
                if (oldTe instanceof TileRadio && ((TileRadio) oldTe).state.speakers.remove(speakerPos)) {
                    ((TileRadio) oldTe).markStateChanged();
                }
            }
        }
        if (!s.speakers.contains(speakerPos)) s.speakers.add(speakerPos);
        speaker.linkedRadio = radioPos;
        speaker.markChanged();
        radio.markStateChanged();
        return new Result(true, "akashicfm.tuner.linked", String.valueOf(s.speakers.size()));
    }

    /** Caixa quebrada: sai da lista da rádio dela. */
    public static void onSpeakerRemoved(World world, TileSpeaker speaker) {
        Pos r = speaker.linkedRadio;
        if (r == null || !world.blockExists(r.x, r.y, r.z)) return;
        TileEntity te = world.getTileEntity(r.x, r.y, r.z);
        if (te instanceof TileRadio && ((TileRadio) te).state.speakers.remove(speaker.pos())) {
            ((TileRadio) te).markStateChanged();
        }
    }

    /**
     * Manutenção periódica das rádios carregadas: remove caixas que sumiram, que foram religadas a outra
     * rádio ou que ficaram longe demais (config mudou), e reavalia o transporte de quem está tocando.
     * Nunca carrega chunk: caixas em chunk descarregado ficam como estão.
     */
    public static void maintain() {
        int maxDist = Math.max(1, FmConfig.Limits.maxSpeakerDistance);
        double maxDistSq = (double) maxDist * maxDist;
        for (TileRadio radio : ServerRadioRegistry.snapshot()) {
            World world = radio.getWorldObj();
            if (world == null || radio.isInvalid()) continue;
            RadioState s = radio.state;
            Pos radioPos = radio.pos();
            boolean changed = false;
            for (Pos p : s.speakers.toArray(new Pos[0])) {
                boolean drop = p.distanceSqTo(radioPos) > maxDistSq;
                if (!drop && world.blockExists(p.x, p.y, p.z)) {
                    TileEntity te = world.getTileEntity(p.x, p.y, p.z);
                    drop = !(te instanceof TileSpeaker) || !radioPos.equals(((TileSpeaker) te).linkedRadio);
                }
                if (drop) {
                    s.speakers.remove(p);
                    changed = true;
                }
            }
            // Sintonizada, quem reavalia política e transporte é o FrequencyService (a URL é a do transmissor).
            boolean urlMode = s.mode == TuneMode.URL;
            if (urlMode && s.playing && ServerPolicy.rejection(s.url) != null) {
                // A allowlist mudou (config recarregado) e esta URL não vale mais: para.
                s.playing = false;
                changed = true;
            }
            if (urlMode && s.playing) {
                Transport wanted = ServerPolicy.chooseTransport(s.url);
                if (wanted != s.transport) {
                    if (wanted == Transport.NONE) {
                        s.playing = false;
                    } else {
                        s.transport = wanted;
                        s.session++;
                    }
                    changed = true;
                }
            }
            if (changed) radio.markStateChanged();
        }
    }
}
