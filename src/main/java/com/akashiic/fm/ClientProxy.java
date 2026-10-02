package com.akashiic.fm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.akashiic.fm.client.ClientEvents;
import com.akashiic.fm.client.ClientRadioRegistry;
import com.akashiic.fm.client.ClientTaskQueue;
import com.akashiic.fm.client.audio.AlCapabilityProbe;
import com.akashiic.fm.client.gui.GuiRadio;
import com.akashiic.fm.client.render.TileRadioRenderer;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientEvents events = new ClientEvents();
        FMLCommonHandler.instance()
            .bus()
            .register(events);
        MinecraftForge.EVENT_BUS.register(events);
        ClientRegistry.bindTileEntitySpecialRenderer(TileRadio.class, new TileRadioRenderer());
        AlCapabilityProbe.registerIfRequested();
    }

    @Override
    public void onClientRadioLoaded(TileRadio radio) {
        ClientRadioRegistry.add(radio);
    }

    @Override
    public void onClientRadioUnloaded(TileRadio radio) {
        ClientRadioRegistry.remove(radio);
    }

    @Override
    public void onClientRadioUpdated(TileRadio radio) {
        GuiRadio gui = openGuiFor(radio.xCoord, radio.yCoord, radio.zCoord);
        if (gui != null) gui.onStateUpdated();
    }

    @Override
    public void openRadioGui(World world, int x, int y, int z) {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiRadio(x, y, z));
    }

    @Override
    public void enqueueClientTask(Runnable task) {
        ClientTaskQueue.add(task);
    }

    @Override
    public void onRadioNotice(S2CRadioNotice notice) {
        GuiRadio gui = openGuiFor(notice.x, notice.y, notice.z);
        if (gui != null) {
            gui.onNotice(notice);
            return;
        }
        // Sem a GUI aberta (fechou antes da resposta), o aviso vai para o chat.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || notice.key.isEmpty()) return;
        ChatComponentText line = new ChatComponentText("[AkashicFM] ");
        line.getChatStyle()
            .setColor(notice.error ? EnumChatFormatting.RED : EnumChatFormatting.GREEN);
        line.appendSibling(new ChatComponentTranslation(notice.key, notice.arg));
        mc.thePlayer.addChatMessage(line);
    }

    @Override
    public void onRadioPerms(S2CRadioPerms perms) {
        GuiRadio gui = openGuiFor(perms.x, perms.y, perms.z);
        if (gui != null) gui.onPerms(perms);
    }

    private static GuiRadio openGuiFor(int x, int y, int z) {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof GuiRadio)) return null;
        GuiRadio gui = (GuiRadio) screen;
        return gui.isFor(x, y, z) ? gui : null;
    }
}
