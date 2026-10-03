package com.akashiic.fm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import org.lwjgl.input.Keyboard;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.content.TileTransmitter;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cpw.mods.fml.client.registry.ClientRegistry;

/**
 * As duas teclas de silenciar, sem tecla padrão (a GTNH já usa quase todas; o jogador escolhe em Controles):
 * <ul>
 * <li><b>Silenciar rádios:</b> liga e desliga o áudio do mod neste cliente (a opção {@code enableAudio} do config,
 * salva no arquivo);</li>
 * <li><b>Silenciar esta:</b> a rádio, caixa, transmissor ou jogador (o portátil dele) que você olha, até 32 blocos,
 * só para você e só nesta sessão ({@link ClientMutes}).</li>
 * </ul>
 * A confirmação aparece acima da barra de itens. Thread principal do cliente.
 */
public final class MuteKeys {

    static final double REACH = 32;
    static final String CATEGORY = "key.categories.akashicfm";
    public static final KeyBinding MUTE_ALL = new KeyBinding("key.akashicfm.mute_all", Keyboard.KEY_NONE, CATEGORY);
    public static final KeyBinding MUTE_TARGET = new KeyBinding(
        "key.akashicfm.mute_target",
        Keyboard.KEY_NONE,
        CATEGORY);

    private MuteKeys() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(MUTE_ALL);
        ClientRegistry.registerKeyBinding(MUTE_TARGET);
    }

    /** Fim do tick do cliente. */
    public static void tick(Minecraft mc) {
        while (MUTE_ALL.isPressed()) toggleAll(mc);
        while (MUTE_TARGET.isPressed()) toggleTarget(mc);
    }

    /** Alterna o áudio do mod neste cliente. Devolve true se ficou silenciado. */
    public static boolean toggleAll(Minecraft mc) {
        FmConfig.Client.enableAudio = !FmConfig.Client.enableAudio;
        try {
            ConfigurationManager.save(FmConfig.Client.class);
        } catch (RuntimeException e) {
            AkashicFM.LOG.warn("Não salvou o config do cliente: {}", e.toString());
        }
        show(mc, FmConfig.Client.enableAudio ? "akashicfm.mute.all_off" : "akashicfm.mute.all_on");
        return !FmConfig.Client.enableAudio;
    }

    /**
     * Silencia (ou devolve o som) o que o jogador olha. Devolve a chave da mensagem mostrada (o E2E confere), ou
     * null sem jogador.
     */
    public static String toggleTarget(Minecraft mc) {
        EntityClientPlayerMP player = mc.thePlayer;
        World world = mc.theWorld;
        if (player == null || world == null) return null;
        Vec3 eye = player.getPosition(1f);
        Vec3 look = player.getLook(1f);
        Vec3 end = eye.addVector(look.xCoord * REACH, look.yCoord * REACH, look.zCoord * REACH);
        MovingObjectPosition block = player.rayTrace(REACH, 1f);
        boolean blockHit = block != null && block.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        double nearest = blockHit ? block.hitVec.distanceTo(eye) : Double.MAX_VALUE;

        // Outro jogador na frente do bloco olhado: o portátil dele.
        EntityPlayer target = null;
        for (Object o : world.playerEntities) {
            if (o == player || !(o instanceof EntityPlayer)) continue;
            EntityPlayer other = (EntityPlayer) o;
            MovingObjectPosition hit = other.boundingBox.expand(0.3, 0.3, 0.3)
                .calculateIntercept(eye, end);
            if (hit == null) continue;
            double d = hit.hitVec.distanceTo(eye);
            if (d < nearest) {
                nearest = d;
                target = other;
            }
        }
        String key, arg = "";
        if (target != null) {
            arg = target.getCommandSenderName();
            key = ClientMutes.toggleCarrier(target.getEntityId(), target.getUniqueID()) ? "akashicfm.mute.player_on"
                : "akashicfm.mute.player_off";
        } else if (blockHit) {
            int dim = world.provider.dimensionId;
            TileEntity te = world.getTileEntity(block.blockX, block.blockY, block.blockZ);
            if (te instanceof TileRadio) {
                key = ClientMutes.toggleRadio(dim, ((TileRadio) te).pos()) ? "akashicfm.mute.radio_on"
                    : "akashicfm.mute.radio_off";
            } else if (te instanceof TileSpeaker && ((TileSpeaker) te).linkedRadio != null) {
                // A caixa toca a rádio dela: silencia a rádio (com todas as caixas).
                key = ClientMutes.toggleRadio(dim, ((TileSpeaker) te).linkedRadio) ? "akashicfm.mute.radio_on"
                    : "akashicfm.mute.radio_off";
            } else if (te instanceof TileTransmitter && !((TileTransmitter) te).state.url.isEmpty()) {
                // As rádios sintonizadas nele tocam a URL dele: silencia a estação.
                TileTransmitter t = (TileTransmitter) te;
                arg = t.state.name.isEmpty() ? NowPlaying.hostOf(t.state.url) : t.state.name;
                key = ClientMutes.toggleUrl(t.state.url) ? "akashicfm.mute.station_on" : "akashicfm.mute.station_off";
            } else {
                key = "akashicfm.mute.no_target";
            }
        } else {
            key = "akashicfm.mute.no_target";
        }
        show(mc, key, arg);
        return key;
    }

    private static void show(Minecraft mc, String key, Object... args) {
        if (mc.ingameGUI != null) mc.ingameGUI.func_110326_a(I18n.format(key, args), false);
    }
}
