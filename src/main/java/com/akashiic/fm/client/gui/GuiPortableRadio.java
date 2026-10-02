package com.akashiic.fm.client.gui;

import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import com.akashiic.fm.client.ClientPortables;
import com.akashiic.fm.client.NowPlaying;
import com.akashiic.fm.client.audio.AudioEngine;
import com.akashiic.fm.client.audio.RadioAudioController;
import com.akashiic.fm.client.relay.RelayClient;
import com.akashiic.fm.client.relay.RelayFeed;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.PortableState;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.ItemPortableRadio;
import com.akashiic.fm.network.C2SPortableAction;
import com.akashiic.fm.network.C2SPortableAction.Action;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CPortableSources;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;
import com.akashiic.fm.server.PortableActionHandler;

/**
 * Tela do rádio portátil do slot. Como as outras, não guarda estado: mostra o item (NBT sincronizado pelo
 * inventário) e o que o servidor diz que ele está tocando ({@link ClientPortables}), e manda pedidos. Fecha
 * sozinha se o slot deixar de ter o portátil.
 */
public final class GuiPortableRadio extends GuiScreen implements FmScreen {

    private static final int W = 256;
    private static final int H = 150;
    private static final long NOTICE_MILLIS = 8000;

    private static final int ID_ON = 1, ID_OFF = 2, ID_MODE = 3, ID_VOLUME = 4, ID_DIAL_BASE = 20;

    private final int slot;
    private int left, top;

    private GuiTextField urlField;
    private FlatButton on, off, mode;
    private FlatSlider volume;
    private FrequencyDial dial;
    private boolean urlEdited;

    private String noticeText = "";
    private boolean noticeError;
    private long noticeUntil;

    public GuiPortableRadio(int slot) {
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }

    /** Os avisos do portátil chegam com y = -1 (nenhum bloco tem essa altura). */
    @Override
    public boolean isFor(int x, int y, int z) {
        return y == PortableActionHandler.NOTICE_Y;
    }

    @Override
    public void onPerms(S2CRadioPerms perms) {}

    @Override
    public void onNotice(S2CRadioNotice notice) {
        noticeText = I18n.format(notice.key, notice.arg);
        noticeError = notice.error;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private ItemStack stack() {
        if (mc == null || mc.thePlayer == null || slot < 0 || slot >= mc.thePlayer.inventory.mainInventory.length)
            return null;
        ItemStack st = mc.thePlayer.inventory.mainInventory[slot];
        return st != null && st.getItem() instanceof ItemPortableRadio ? st : null;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ItemStack st = stack();
        if (st == null) {
            mc.displayGuiScreen(null);
            return;
        }
        PortableState s = ItemPortableRadio.state(st);
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        String urlText = urlField != null ? urlField.getText() : s.url;

        mode = add(new FlatButton(ID_MODE, left + 8, top + 30, 28, 16, ""));
        urlField = new GuiTextField(fontRendererObj, left + 41, top + 31, 122, 14);
        urlField.setMaxStringLength(RadioLimits.MAX_URL_LENGTH);
        urlField.setText(urlText);
        urlField.setCursorPositionZero();
        dial = new FrequencyDial(ID_DIAL_BASE, left + 40, top + 30, 124, f -> send(Action.SET_FREQUENCY, f, ""));
        for (FlatButton b : dial.buttons()) buttonList.add(b);
        on = add(new FlatButton(ID_ON, left + 168, top + 30, 38, 16, I18n.format("akashicfm.gui.play")));
        off = add(new FlatButton(ID_OFF, left + 210, top + 30, 38, 16, I18n.format("akashicfm.gui.stop")));
        volume = add(
            new FlatSlider(
                ID_VOLUME,
                left + 8,
                top + 50,
                240,
                16,
                "akashicfm.gui.volume",
                RadioLimits.VOLUME_MIN,
                RadioLimits.VOLUME_MAX,
                s.volume,
                v -> send(Action.SET_VOLUME, v, "")));
        refreshWidgets(s);
    }

    private <T extends GuiButton> T add(T button) {
        buttonList.add(button);
        return button;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        ItemStack st = stack();
        if (st != null && dial != null) dial.flush(ItemPortableRadio.state(st).frequency, System.currentTimeMillis());
    }

    @Override
    public void updateScreen() {
        ItemStack st = stack();
        if (st == null || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        PortableState s = ItemPortableRadio.state(st);
        urlField.updateCursorCounter();
        dial.tick(s.frequency, System.currentTimeMillis());
        if (urlField.getText()
            .equals(s.url)) urlEdited = false;
        refreshWidgets(s);
    }

    private static boolean fm(PortableState s) {
        return s.mode == TuneMode.FREQUENCY;
    }

    private void refreshWidgets(PortableState s) {
        boolean fm = fm(s);
        mode.displayString = I18n.format(fm ? "akashicfm.gui.mode.fm" : "akashicfm.gui.mode.url");
        dial.setVisible(fm);
        urlField.setEnabled(!fm);
        if (fm) urlField.setFocused(false);
        if (!urlEdited && !urlField.isFocused()
            && !urlField.getText()
                .equals(s.url)) {
            urlField.setText(s.url);
            urlField.setCursorPositionZero();
        }
        off.enabled = s.on;
        volume.setValue(s.volume);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        ItemStack st = stack();
        if (st == null) return;
        PortableState s = ItemPortableRadio.state(st);
        long now = System.currentTimeMillis();
        if (dial.handle(button.id, s.frequency, now)) return;
        switch (button.id) {
            case ID_MODE:
                dial.flush(s.frequency, now);
                send(Action.SET_MODE, (fm(s) ? TuneMode.URL : TuneMode.FREQUENCY).ordinal(), "");
                return;
            case ID_ON:
                turnOn(s);
                return;
            case ID_OFF:
                send(Action.TURN_OFF, 0, "");
                return;
            default:
                return;
        }
    }

    private void turnOn(PortableState s) {
        if (fm(s)) {
            dial.flush(s.frequency, System.currentTimeMillis());
            send(Action.TURN_ON, 0, "");
            return;
        }
        String typed = TextSanitizer.cleanUrl(urlField.getText(), RadioLimits.MAX_URL_LENGTH);
        if (!typed.isEmpty() && !typed.equals(s.url)) {
            if (!RadioState.isPlausibleUrl(typed)) {
                localNotice(true, "akashicfm.notice.url_invalid");
                return;
            }
            send(Action.SET_URL, 0, typed);
        } else if (s.url.isEmpty()) {
            localNotice(true, "akashicfm.notice.no_url");
            return;
        }
        send(Action.TURN_ON, 0, "");
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean enter = keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER;
        ItemStack st = stack();
        PortableState s = st == null ? null : ItemPortableRadio.state(st);
        if (urlField.isFocused()) {
            if (enter) {
                if (s != null) turnOn(s);
                return;
            }
            if (urlField.textboxKeyTyped(typedChar, keyCode)) {
                if (s != null) urlEdited = !urlField.getText()
                    .equals(s.url);
                return;
            }
        }
        boolean typing = urlField.isFocused();
        if (!typing && s != null && fm(s)) {
            int id = keyCode == Keyboard.KEY_LEFT ? dial.down.id : keyCode == Keyboard.KEY_RIGHT ? dial.up.id : -1;
            if (id >= 0 && dial.handle(id, s.frequency, System.currentTimeMillis())) return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || (!typing && keyCode == mc.gameSettings.keyBindInventory.getKeyCode())) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        ItemStack st = stack();
        boolean fm = st != null && fm(ItemPortableRadio.state(st));
        if (!fm) urlField.mouseClicked(mouseX, mouseY, mouseButton);
        else urlField.setFocused(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, 0xFF4CB06A);
        drawGradientRect(left, top, left + W, top + H, 0xF8181C24, 0xF80E1116);
        ItemStack st = stack();
        if (st == null) return;
        PortableState s = ItemPortableRadio.state(st);
        long now = System.currentTimeMillis();
        S2CPortableSources.Entry own = mc.thePlayer == null ? null
            : ClientPortables.own(mc.thePlayer.getEntityId(), now);

        drawCenteredString(
            fontRendererObj,
            I18n.format("akashicfm.gui.portable.title"),
            left + W / 2,
            top + 6,
            0xFFFFFF);
        String sub = I18n.format(s.on ? "akashicfm.portable.on" : "akashicfm.portable.off") + " · "
            + I18n.format(
                own != null && own.headphones ? "akashicfm.gui.portable.headphones" : "akashicfm.gui.portable.speaker");
        drawCenteredString(fontRendererObj, sub, left + W / 2, top + 17, 0xFF9AA4B0);

        if (fm(s)) {
            dial.draw(fontRendererObj, s.frequency, true, now);
        } else {
            urlField.drawTextBox();
            if (urlField.getText()
                .isEmpty() && !urlField.isFocused()) {
                fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(I18n.format("akashicfm.gui.url_hint"), 114),
                    left + 45,
                    top + 34,
                    0xFF606870);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);

        int lx = left + 9, width = W - 18;
        String line;
        int color;
        if (!s.on) {
            line = I18n.format("akashicfm.portable.off");
            color = 0xFF9AA4B0;
        } else if (own == null) {
            line = I18n.format("akashicfm.gui.status.connecting");
            color = 0xFF9AA4B0;
        } else if (own.url.isEmpty()) {
            line = fm(s) ? I18n.format("akashicfm.gui.fm.no_signal", Frequency.format(s.frequency))
                : I18n.format("akashicfm.gui.status.no_transport");
            color = 0xFFFFD070;
        } else {
            String head = fm(s) ? I18n.format("akashicfm.gui.fm.signal", own.signal, NowPlaying.portableStation(own))
                : I18n.format("akashicfm.gui.transport." + own.transport.name());
            line = head + " · " + playbackStatus(own);
            color = 0xFFE0E6EE;
        }
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(line, width), lx, top + 76, color);
        if (s.on && own != null) {
            String key = playbackKey(own);
            String title = NowPlaying.portableTitle(own, key);
            String second = !own.status.isEmpty() ? GuiRadio.translateStatus(own.status)
                : !title.isEmpty() ? "♪ " + title : "";
            if (!second.isEmpty()) {
                fontRendererObj.drawString(fontRendererObj.trimStringToWidth(second, width), lx, top + 88, 0xFFB8C8FF);
            }
        }
        fontRendererObj.drawSplitString(I18n.format("akashicfm.gui.portable.hint"), lx, top + 106, width, 0xFF707880);
        if (!noticeText.isEmpty() && now < noticeUntil) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(noticeText, width),
                lx,
                top + 130,
                noticeError ? 0xFFFF6060 : 0xFF70FF80);
        }
    }

    private static String playbackKey(S2CPortableSources.Entry own) {
        if (own.transport == Transport.RELAY) {
            RelayFeed relay = RelayClient.feedForUrl(own.url);
            return relay == null ? null : RadioAudioController.relayKey(relay);
        }
        return own.transport == Transport.DIRECT ? RadioAudioController.portableKey(own) : null;
    }

    private String playbackStatus(S2CPortableSources.Entry own) {
        if (own.transport != Transport.DIRECT && own.transport != Transport.RELAY) {
            return I18n.format("akashicfm.gui.status.no_transport");
        }
        if (!FmConfig.Client.enableAudio) return I18n.format("akashicfm.gui.status.audio_disabled");
        if (own.transport == Transport.DIRECT && !FmConfig.Client.allowDirectStreams) {
            return I18n.format("akashicfm.gui.status.direct_disabled");
        }
        if (mc.gameSettings.getSoundLevel(SoundCategory.MASTER) <= 0f
            || mc.gameSettings.getSoundLevel(SoundCategory.RECORDS) <= 0f
            || FmConfig.Client.radioVolume <= 0) return I18n.format("akashicfm.gui.status.muted");
        String key = playbackKey(own);
        if (key == null) return I18n.format("akashicfm.gui.status.relay_waiting");
        AudioEngine.PlaybackInfo info = AudioEngine.INSTANCE.info(key);
        if (info == null) return I18n.format("akashicfm.gui.status.connecting");
        if (!info.playing) return I18n.format("akashicfm.gui.status.buffering");
        return I18n.format("akashicfm.gui.status.playing");
    }

    private void localNotice(boolean error, String key) {
        noticeText = I18n.format(key);
        noticeError = error;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    private void send(Action action, int intArg, String strArg) {
        ItemStack st = stack();
        if (st == null) return;
        long id = ItemPortableRadio.state(st).id;
        FmNetwork.sendToServer(new C2SPortableAction(slot, id, action, intArg, strArg));
    }
}
