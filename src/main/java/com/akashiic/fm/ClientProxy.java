package com.akashiic.fm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.akashiic.fm.client.ClientEvents;
import com.akashiic.fm.client.ClientPortables;
import com.akashiic.fm.client.ClientRadioRegistry;
import com.akashiic.fm.client.ClientTaskQueue;
import com.akashiic.fm.client.MuteKeys;
import com.akashiic.fm.client.audio.AlCapabilityProbe;
import com.akashiic.fm.client.gui.FmScreen;
import com.akashiic.fm.client.gui.GuiPortableRadio;
import com.akashiic.fm.client.gui.GuiRadio;
import com.akashiic.fm.client.gui.GuiTransmitter;
import com.akashiic.fm.client.relay.ClockSync;
import com.akashiic.fm.client.relay.RelayClient;
import com.akashiic.fm.client.render.TileRadioRenderer;
import com.akashiic.fm.client.render.TileSpeakerRenderer;
import com.akashiic.fm.client.render.TileTransmitterRenderer;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileSpeaker;
import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.dev.DevE2E;
import com.akashiic.fm.dev.E2EClient;
import com.akashiic.fm.network.ClockStamps;
import com.akashiic.fm.network.S2CAudio;
import com.akashiic.fm.network.S2CClockPong;
import com.akashiic.fm.network.S2CListen;
import com.akashiic.fm.network.S2CPortableSources;
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
        MuteKeys.register();
        ClientRegistry.bindTileEntitySpecialRenderer(TileRadio.class, new TileRadioRenderer());
        ClientRegistry.bindTileEntitySpecialRenderer(TileSpeaker.class, new TileSpeakerRenderer());
        ClientRegistry.bindTileEntitySpecialRenderer(TileTransmitter.class, new TileTransmitterRenderer());
        AlCapabilityProbe.registerIfRequested();
        if (DevE2E.enabled()) E2EClient.register(DevE2E.SCENARIO);
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
        FmScreen gui = openGuiFor(radio.xCoord, radio.yCoord, radio.zCoord);
        if (gui instanceof GuiRadio) ((GuiRadio) gui).onStateUpdated();
    }

    @Override
    public void openRadioGui(World world, int x, int y, int z) {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiRadio(x, y, z));
    }

    @Override
    public void openTransmitterGui(World world, int x, int y, int z) {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiTransmitter(x, y, z));
    }

    @Override
    public void openPortableGui(int slot) {
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiPortableRadio(slot));
    }

    @Override
    public void onPortableSources(S2CPortableSources message) {
        ClientPortables.update(message, System.currentTimeMillis());
    }

    @Override
    public void enqueueClientTask(Runnable task) {
        ClientTaskQueue.add(task);
    }

    @Override
    public void onRadioNotice(S2CRadioNotice notice) {
        if (DevE2E.enabled() && E2EClient.INSTANCE != null) E2EClient.INSTANCE.onNotice(notice);
        FmScreen gui = openGuiFor(notice.x, notice.y, notice.z);
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
        if (DevE2E.enabled() && E2EClient.INSTANCE != null) E2EClient.INSTANCE.onPerms(perms);
        FmScreen gui = openGuiFor(perms.x, perms.y, perms.z);
        if (gui != null) gui.onPerms(perms);
    }

    @Override
    public void onRelayListen(S2CListen message) {
        RelayClient.onListen(message);
    }

    @Override
    public void onRelayAudio(S2CAudio message) {
        RelayClient.onAudio(message);
    }

    @Override
    public void onClockPong(S2CClockPong message, long fallbackT3) {
        Minecraft mc = Minecraft.getMinecraft();
        Object connection = mc.getNetHandler() == null ? null
            : mc.getNetHandler()
                .getNetworkManager();
        ClockSync.onPong(message, ClockStamps.take(connection, message.t0, fallbackT3));
    }

    private static FmScreen openGuiFor(int x, int y, int z) {
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof FmScreen)) return null;
        FmScreen gui = (FmScreen) screen;
        return gui.isFor(x, y, z) ? gui : null;
    }
}
