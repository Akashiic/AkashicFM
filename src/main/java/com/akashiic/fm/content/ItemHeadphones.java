package com.akashiic.fm.content;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.EnumHelper;

import com.akashiic.fm.AkashicFM;

import baubles.api.BaubleType;
import baubles.api.expanded.IBaubleExpanded;
import cpw.mods.fml.common.Optional;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Fone: o par do rádio portátil. Usado no slot de capacete (ou, com o Baubles Expanded, nos slots de cabeça e de
 * brinco), faz o portátil tocar só para quem o usa, em estéreo e sem posição. Não protege nada e não gasta.
 */
@Optional.Interface(iface = "baubles.api.expanded.IBaubleExpanded", modid = "Baubles|Expanded")
public class ItemHeadphones extends ItemArmor implements IBaubleExpanded {

    /** Sem proteção e sem encantabilidade: é só um fone. */
    public static final ArmorMaterial MATERIAL = EnumHelper
        .addArmorMaterial("AKASHICFM_HEADPHONES", 5, new int[] { 0, 0, 0, 0 }, 0);

    public ItemHeadphones() {
        super(MATERIAL, 0, 0);
        setMaxDamage(0); // não gasta ao levar dano
        setUnlocalizedName(AkashicFM.MODID + ".headphones");
        setTextureName(AkashicFM.MODID + ":headphones");
        setCreativeTab(FmContent.TAB);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister reg) {
        itemIcon = reg.registerIcon(AkashicFM.MODID + ":headphones");
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, int slot, String type) {
        return AkashicFM.MODID + ":textures/models/armor/headphones.png";
    }

    // ---- Baubles Expanded (opcional) ----

    @Override
    @Optional.Method(modid = "Baubles|Expanded")
    public String[] getBaubleTypes(ItemStack stack) {
        return new String[] { "head", "earring" };
    }

    /** Só tipos do Expanded (o Baubles antigo não tem slot de cabeça); o Expanded consulta os tipos acima. */
    @Override
    @Optional.Method(modid = "Baubles|Expanded")
    public BaubleType getBaubleType(ItemStack stack) {
        return null;
    }

    @Override
    @Optional.Method(modid = "Baubles|Expanded")
    public void onWornTick(ItemStack stack, EntityLivingBase player) {}

    @Override
    @Optional.Method(modid = "Baubles|Expanded")
    public void onEquipped(ItemStack stack, EntityLivingBase player) {}

    @Override
    @Optional.Method(modid = "Baubles|Expanded")
    public void onUnequipped(ItemStack stack, EntityLivingBase player) {}

    @Override
    @Optional.Method(modid = "Baubles|Expanded")
    public boolean canEquip(ItemStack stack, EntityLivingBase player) {
        return true;
    }

    @Override
    @Optional.Method(modid = "Baubles|Expanded")
    public boolean canUnequip(ItemStack stack, EntityLivingBase player) {
        return true;
    }
}
