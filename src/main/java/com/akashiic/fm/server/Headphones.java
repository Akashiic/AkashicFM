package com.akashiic.fm.server;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.akashiic.fm.compat.baubles.BaublesCompat;
import com.akashiic.fm.content.ItemHeadphones;

import cpw.mods.fml.common.Loader;

/** O jogador está de fone: no slot de capacete ou, com o Baubles, em qualquer slot de bauble. */
public final class Headphones {

    private static Boolean baubles;

    private Headphones() {}

    public static boolean isWorn(EntityPlayer player) {
        if (player == null || player.inventory == null) return false;
        ItemStack helmet = player.inventory.armorInventory[3];
        if (helmet != null && helmet.getItem() instanceof ItemHeadphones) return true;
        return baublesLoaded() && BaublesCompat.wearing(player, ItemHeadphones.class);
    }

    private static boolean baublesLoaded() {
        if (baubles == null) baubles = Loader.isModLoaded("Baubles");
        return baubles;
    }
}
