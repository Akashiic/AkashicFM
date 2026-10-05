package com.akashiic.fm.content;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
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
    public static BlockTransmitter transmitter;
    public static BlockAntenna antenna;
    public static ItemPortableRadio portableRadio;
    public static ItemIPod ipod;
    public static ItemHeadphones headphones;
    public static BlockIPodPlayer ipodPlayer;

    /** preInit: blocos e itens precisam existir antes do init. */
    public static void registerBlocksAndItems() {
        radio = new BlockRadio();
        GameRegistry.registerBlock(radio, ItemBlockRadio.class, "radio");
        speaker = new BlockSpeaker();
        GameRegistry.registerBlock(speaker, "speaker");
        tuner = new ItemTuner();
        GameRegistry.registerItem(tuner, "tuner", AkashicFM.MODID);
        transmitter = new BlockTransmitter();
        GameRegistry.registerBlock(transmitter, ItemBlockTransmitter.class, "transmitter");
        antenna = new BlockAntenna();
        GameRegistry.registerBlock(antenna, "antenna");
        portableRadio = new ItemPortableRadio();
        GameRegistry.registerItem(portableRadio, "portable_radio", AkashicFM.MODID);
        ipod = new ItemIPod();
        GameRegistry.registerItem(ipod, "ipod", AkashicFM.MODID);
        headphones = new ItemHeadphones();
        GameRegistry.registerItem(headphones, "headphones", AkashicFM.MODID);
        GameRegistry.registerTileEntity(TileRadio.class, AkashicFM.MODID + ":radio");
        GameRegistry.registerTileEntity(TileSpeaker.class, AkashicFM.MODID + ":speaker");
        GameRegistry.registerTileEntity(TileTransmitter.class, AkashicFM.MODID + ":transmitter");
        // Fase 9b (no fim: a ordem de registro não muda os ids dos blocos de antes).
        ipodPlayer = new BlockIPodPlayer();
        GameRegistry.registerBlock(ipodPlayer, ItemBlockIPodPlayer.class, "ipod_player");
        GameRegistry.registerTileEntity(TileIPodPlayer.class, AkashicFM.MODID + ":ipod_player");
    }

    /** init: receitas padrão (o modpack pode desligar e definir as próprias). */
    public static void registerRecipes() {
        if (!FmConfig.Recipes.registerDefaultRecipes) return;
        // Ore dictionary (madeira, ferro e redstone de qualquer mod); lã com metadata coringa (qualquer cor).
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
                "ingotIron",
                'N',
                Blocks.noteblock,
                'R',
                "dustRedstone"));
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
                "dustRedstone"));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(tuner),
                "R",
                "I",
                "S",
                'R',
                "dustRedstone",
                'I',
                "ingotIron",
                'S',
                "stickWood"));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(transmitter),
                "IGI",
                "RNR",
                "III",
                'I',
                "ingotIron",
                'G',
                Blocks.glass_pane,
                'R',
                "dustRedstone",
                'N',
                Blocks.noteblock));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(new ItemStack(antenna, 4), "B", "I", "I", 'B', Blocks.iron_bars, 'I', "ingotIron"));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(portableRadio),
                "  B",
                "INI",
                "IRI",
                'B',
                Blocks.iron_bars,
                'I',
                "ingotIron",
                'N',
                Blocks.noteblock,
                'R',
                "dustRedstone"));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(ipod),
                "IGI",
                "INI",
                "IRI",
                'I',
                "ingotIron",
                'G',
                "paneGlass",
                'N',
                Blocks.noteblock,
                'R',
                "dustRedstone"));
        // O bloco do iPod: um iPod numa base com jukebox.
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(ipodPlayer),
                "PIP",
                "PJP",
                "PRP",
                'P',
                "plankWood",
                'I',
                ipod,
                'J',
                Blocks.jukebox,
                'R',
                "dustRedstone"));
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(headphones),
                "WIW",
                "N N",
                'W',
                anyWool,
                'I',
                "ingotIron",
                'N',
                Blocks.noteblock));
    }
}
