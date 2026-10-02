package com.akashiic.fm.compat.baubles;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import baubles.api.BaublesApi;
import baubles.api.expanded.BaubleExpandedSlots;

/**
 * Acesso ao inventário do Baubles. Só é carregada quando o Baubles está instalado (quem chama confere antes):
 * nenhuma outra classe do mod referencia a API dele.
 */
public final class BaublesCompat {

    private BaublesCompat() {}

    /** Tipos de slot do Baubles Expanded em que o fone entra. */
    public static final String[] HEADPHONE_SLOTS = { "head", "earring" };

    /** Pede ao Baubles Expanded os slots do fone (o jeito documentado de garantir que existam). preInit. */
    public static void requestSlots() {
        for (String type : HEADPHONE_SLOTS) BaubleExpandedSlots.tryAssignSlotsUpToMinimum(type, 1);
    }

    /** Põe o item no primeiro slot de bauble livre dos tipos do fone que o aceite (testes E2E). */
    public static boolean equip(EntityPlayer player, ItemStack stack) {
        IInventory inv = BaublesApi.getBaubles(player);
        if (inv == null) return false;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            String type = BaubleExpandedSlots.getSlotType(i);
            boolean wanted = false;
            for (String t : HEADPHONE_SLOTS) wanted |= t.equals(type);
            if (!wanted || inv.getStackInSlot(i) != null || !inv.isItemValidForSlot(i, stack)) continue;
            inv.setInventorySlotContents(i, stack);
            return true;
        }
        return false;
    }

    /** Tira dos slots de bauble todo item da classe dada (testes E2E). */
    public static void removeAll(EntityPlayer player, Class<?> itemClass) {
        IInventory inv = BaublesApi.getBaubles(player);
        if (inv == null) return;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && itemClass.isInstance(st.getItem())) inv.setInventorySlotContents(i, null);
        }
    }

    /** Algum slot de bauble do jogador tem um item da classe dada. Nunca lança. */
    public static boolean wearing(EntityPlayer player, Class<?> itemClass) {
        try {
            IInventory inv = BaublesApi.getBaubles(player);
            if (inv == null) return false;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st != null && itemClass.isInstance(st.getItem())) return true;
            }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        return false;
    }
}
