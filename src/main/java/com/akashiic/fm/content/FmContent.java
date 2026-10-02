package com.akashiic.fm.content;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;

import com.akashiic.fm.AkashicFM;
import com.akashiic.fm.common.FmConfig;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Registro de blocos, itens, tile entities e receitas. */
public final class FmContent {

    private FmContent() {}

    public static final CreativeTabs TAB = new CreativeTabs(AkashicFM.MODID) {

        @Override
        @SideOnly(Side.CLIENT)
        public Item getTabIconItem() {
            return Item.getItemFromBlock(radio);
        }
    };

    public static BlockRadio radio;
    public static BlockSpeaker speaker;
    public static ItemTuner tuner;

    /** preInit: blocos e itens precisam existir antes do init. */
    public static void registerBlocksAndItems() {
        radio = new BlockRadio();
        GameRegistry.registerBlock(radio, ItemBlockRadio.class, "radio");
        speaker = new BlockSpeaker();
        GameRegistry.registerBlock(speaker, "speaker");
        tuner = new ItemTuner();
        GameRegistry.registerItem(tuner, "tuner", AkashicFM.MODID);
        GameRegistry.registerTileEntity(TileRadio.class, AkashicFM.MODID + ":radio");
        GameRegistry.registerTileEntity(TileSpeaker.class, AkashicFM.MODID + ":speaker");
    }

    /** init: receitas padrão (o modpack pode desligar e definir as próprias). */
    public static void registerRecipes() {
        if (!FmConfig.Recipes.registerDefaultRecipes) return;
        // Ore dictionary para aceitar qualquer madeira; lã com metadata coringa para aceitar qualquer cor.
        ItemStack anyWool = new ItemStack(Blocks.wool, 1, OreDictionary.WILDCARD_VALUE);
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(radio),
                "PIP",
                "NRN",
                "PPP",
                'P',
                "plankWood",
                'I',
                Items.iron_ingot,
                'N',
                Blocks.noteblock,
                'R',
                Items.redstone));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(speaker),
                "PWP",
                "WNW",
                "PRP",
                'P',
                "plankWood",
                'W',
                anyWool,
                'N',
                Blocks.noteblock,
                'R',
                Items.redstone));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(tuner),
                "R",
                "I",
                "S",
                'R',
                Items.redstone,
                'I',
                Items.iron_ingot,
                'S',
                "stickWood"));
    }
}
