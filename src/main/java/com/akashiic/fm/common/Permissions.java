package com.akashiic.fm.common;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import com.mojang.authlib.GameProfile;

/** Regras de permissão das rádios. Só no servidor. */
public final class Permissions {

    private Permissions() {}

    /**
     * Op de verdade. Nunca lança: FakePlayers (mineradores do GregTech, robôs, deployers) e perfis sem UUID
     * simplesmente não são ops. O OpenFM chamava a lista de ops com o perfil de FakePlayer e dava NPE.
     */
    public static boolean isOp(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP) || player instanceof FakePlayer) return false;
        GameProfile profile = player.getGameProfile();
        if (profile == null || profile.getId() == null || profile.getName() == null) return false;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return false;
        try {
            return server.getConfigurationManager()
                .func_152596_g(profile);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean opBypass(EntityPlayer player) {
        return FmConfig.Protection.opsBypass && isOp(player);
    }

    /** Tocar, parar, trocar URL/volume/alcance/tela. */
    public static boolean canControl(RadioState state, EntityPlayer player) {
        if (player == null) return false;
        if (state.isOwner(player.getUniqueID())) return true;
        if (state.owner == null || state.access == RadioAccess.PUBLIC) return !(player instanceof FakePlayer);
        return opBypass(player);
    }

    /** Configurações de dono: acesso, favoritos, redstone, caixas de som. */
    public static boolean canAdmin(RadioState state, EntityPlayer player) {
        if (player == null) return false;
        if (state.isOwner(player.getUniqueID())) return true;
        return opBypass(player);
    }

    /** Quebrar o bloco. Máquinas (FakePlayer) não quebram blocos privados de ninguém. */
    public static boolean canBreak(RadioState state, EntityPlayer player) {
        if (!FmConfig.Protection.protectPrivateBlocks) return true;
        if (state.owner == null || state.access == RadioAccess.PUBLIC) return true;
        if (player == null || player instanceof FakePlayer) return false;
        return state.isOwner(player.getUniqueID()) || isOp(player);
    }
}
