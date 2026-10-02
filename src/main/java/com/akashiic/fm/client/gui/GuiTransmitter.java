package com.akashiic.fm.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.lwjgl.input.Keyboard;

import com.akashiic.fm.client.NowPlaying;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.content.TileTransmitter;
import com.akashiic.fm.network.C2SRadioAction;
import com.akashiic.fm.network.C2SRadioAction.Action;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;

/**
 * Tela do transmissor: URL, frequência, nome da estação, transmitir/parar, acesso e redstone, mais o alcance (das
 * antenas) e a energia. Como a da rádio, não guarda estado: mostra o TE e manda pedidos; os controles só habilitam
 * depois da resposta de permissões do servidor.
 */
public final class GuiTransmitter extends GuiScreen implements FmScreen {

    private static final int W = 256;
    private static final int H = 182;
    private static final long NOTICE_MILLIS = 8000;
    private static final double MAX_DISTANCE_SQ = 64.0;

    private static final int ID_PLAY = 1, ID_STOP = 2, ID_NAME_OK = 3, ID_ACCESS = 4, ID_REDSTONE = 5,
        ID_DIAL_BASE = 20;

    private final int x, y, z;
    private int left, top;

    private GuiTextField urlField, nameField;
    private FlatButton play, stop, nameOk, access, redstone;
    private FrequencyDial dial;

    private boolean permsRequested, permsKnown, canControl, canAdmin;
    private RadioAccess lastAccess;
    private boolean urlEdited, nameEdited;
    private int lastEpoch = Integer.MIN_VALUE;

    private String noticeText = "";
    private boolean noticeError;
    private long noticeUntil;

    public GuiTransmitter(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isFor(int px, int py, int pz) {
        return px == x && py == y && pz == z;
    }

    public boolean permsKnown() {
        return permsKnown;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        TileTransmitter t = transmitter();
        if (t == null) {
            mc.displayGuiScreen(null);
            return;
        }
        TransmitterState s = t.state;
        if (lastAccess == null) lastAccess = s.access;
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();

        String urlText = urlField != null ? urlField.getText() : s.url;
        String nameText = nameField != null ? nameField.getText() : s.name;

        urlField = new GuiTextField(fontRendererObj, left + 9, top + 31, 154, 14);
        urlField.setMaxStringLength(RadioLimits.MAX_URL_LENGTH);
        setFieldText(urlField, urlText);
        play = add(new FlatButton(ID_PLAY, left + 168, top + 30, 38, 16, I18n.format("akashicfm.gui.transmit")));
        stop = add(new FlatButton(ID_STOP, left + 210, top + 30, 38, 16, I18n.format("akashicfm.gui.stop")));

        dial = new FrequencyDial(ID_DIAL_BASE, left + 124, top + 51, 124, f -> send(Action.SET_FREQUENCY, f, ""));
        for (FlatButton b : dial.buttons()) add(b);

        nameField = new GuiTextField(fontRendererObj, left + 9, top + 72, 154, 14);
        nameField.setMaxStringLength(RadioLimits.MAX_SCREEN_TEXT);
        setFieldText(nameField, nameText);
        nameOk = add(new FlatButton(ID_NAME_OK, left + 168, top + 71, 24, 16, I18n.format("akashicfm.gui.ok")));
        access = add(new FlatButton(ID_ACCESS, left + 196, top + 71, 52, 16, ""));
        redstone = add(new FlatButton(ID_REDSTONE, left + 8, top + 91, 240, 16, ""));

        if (!permsRequested) {
            permsRequested = true;
            send(Action.REQUEST_PERMS, 0, "");
        }
        refreshWidgets(s);
    }

    private <T extends GuiButton> T add(T button) {
        buttonList.add(button);
        return button;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        TileTransmitter t = transmitter();
        if (t != null && dial != null) dial.flush(t.state.frequency, System.currentTimeMillis());
    }

    @Override
    public void updateScreen() {
        TileTransmitter t = transmitter();
        if (t == null || mc.thePlayer == null
            || mc.thePlayer.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) > MAX_DISTANCE_SQ) {
            mc.displayGuiScreen(null);
            return;
        }
        TransmitterState s = t.state;
        urlField.updateCursorCounter();
        nameField.updateCursorCounter();
        dial.tick(s.frequency, System.currentTimeMillis());
        if (s.epoch != lastEpoch) {
            lastEpoch = s.epoch;
            onStateUpdated(s);
        }
        refreshWidgets(s);
    }

    // ---- Respostas do servidor ----

    @Override
    public void onPerms(S2CRadioPerms perms) {
        permsKnown = true;
        canControl = perms.canControl;
        canAdmin = perms.canAdmin;
        TileTransmitter t = transmitter();
        if (t != null && urlField != null) refreshWidgets(t.state);
    }

    @Override
    public void onNotice(S2CRadioNotice notice) {
        noticeText = I18n.format(notice.key, notice.arg);
        noticeError = notice.error;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    private void onStateUpdated(TransmitterState s) {
        if (urlField.getText()
            .equals(s.url)) urlEdited = false;
        if (nameField.getText()
            .equals(s.name)) nameEdited = false;
        if (lastAccess != s.access) {
            lastAccess = s.access;
            send(Action.REQUEST_PERMS, 0, "");
        }
    }

    private void refreshWidgets(TransmitterState s) {
        boolean control = permsKnown && canControl;
        boolean admin = permsKnown && canAdmin;
        urlField.setEnabled(control);
        if (!control) urlField.setFocused(false);
        if (!urlEdited && !urlField.isFocused()
            && !urlField.getText()
                .equals(s.url))
            setFieldText(urlField, s.url);
        play.enabled = control;
        stop.enabled = control && s.broadcasting;
        dial.setEnabled(control);

        nameField.setEnabled(admin);
        if (!admin) nameField.setFocused(false);
        if (!nameEdited && !nameField.isFocused()
            && !nameField.getText()
                .equals(s.name))
            setFieldText(nameField, s.name);
        nameOk.enabled = admin && !nameField.getText()
            .equals(s.name);
        access.enabled = admin;
        access.displayString = I18n.format("akashicfm.gui.access." + s.access.name());
        redstone.enabled = admin;
        redstone.displayString = I18n
            .format("akashicfm.gui.redstone", I18n.format("akashicfm.gui.redstone." + s.redstoneMode.name()));
    }

    private static void setFieldText(GuiTextField field, String text) {
        field.setText(text);
        field.setCursorPositionZero();
    }

    // ---- Entrada ----

    @Override
    protected void actionPerformed(GuiButton button) {
        TileTransmitter t = transmitter();
        if (t == null) return;
        TransmitterState s = t.state;
        if (dial.handle(button.id, s.frequency, System.currentTimeMillis())) return;
        switch (button.id) {
            case ID_PLAY:
                doPlay(s);
                return;
            case ID_STOP:
                send(Action.STOP, 0, "");
                return;
            case ID_NAME_OK:
                commitName(s);
                return;
            case ID_ACCESS:
                RadioAccess next = s.access == RadioAccess.PUBLIC ? RadioAccess.PRIVATE : RadioAccess.PUBLIC;
                send(Action.SET_ACCESS, next.ordinal(), "");
                return;
            case ID_REDSTONE:
                send(
                    Action.SET_REDSTONE_MODE,
                    s.redstoneMode.next()
                        .ordinal(),
                    "");
                return;
            default:
                return;
        }
    }

    private void doPlay(TransmitterState s) {
        dial.flush(s.frequency, System.currentTimeMillis());
        String typed = TextSanitizer.cleanUrl(urlField.getText(), RadioLimits.MAX_URL_LENGTH);
        if (typed.isEmpty() || typed.equals(s.url)) {
            if (s.url.isEmpty()) {
                localNotice(true, "akashicfm.notice.no_url");
                return;
            }
            send(Action.PLAY, 0, "");
            return;
        }
        if (!RadioState.isPlausibleUrl(typed)) {
            localNotice(true, "akashicfm.notice.url_invalid");
            return;
        }
        send(Action.PLAY, 0, typed);
    }

    private void commitName(TransmitterState s) {
        String text = TextSanitizer.clean(nameField.getText(), RadioLimits.MAX_SCREEN_TEXT);
        if (!text.equals(s.name)) send(Action.SET_SCREEN_TEXT, 0, text);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean enter = keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER;
        TileTransmitter t = transmitter();
        if (urlField.isFocused()) {
            if (enter) {
                if (t != null && play.enabled) doPlay(t.state);
                return;
            }
            if (urlField.textboxKeyTyped(typedChar, keyCode)) {
                if (t != null) urlEdited = !urlField.getText()
                    .equals(t.state.url);
                return;
            }
        }
        if (nameField.isFocused()) {
            if (enter) {
                if (t != null && canAdmin) commitName(t.state);
                nameField.setFocused(false);
                return;
            }
            if (nameField.textboxKeyTyped(typedChar, keyCode)) {
                if (t != null) nameEdited = !nameField.getText()
                    .equals(t.state.name);
                return;
            }
        }
        boolean typing = urlField.isFocused() || nameField.isFocused();
        if (!typing && t != null && permsKnown && canControl) {
            int id = keyCode == Keyboard.KEY_LEFT ? dial.down.id : keyCode == Keyboard.KEY_RIGHT ? dial.up.id : -1;
            if (id >= 0 && dial.handle(id, t.state.frequency, System.currentTimeMillis())) return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || (!typing && keyCode == mc.gameSettings.keyBindInventory.getKeyCode())) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (permsKnown && canControl) urlField.mouseClicked(mouseX, mouseY, mouseButton);
        else urlField.setFocused(false);
        if (permsKnown && canAdmin) nameField.mouseClicked(mouseX, mouseY, mouseButton);
        else nameField.setFocused(false);
    }

    // ---- Desenho ----

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, 0xFFD58A3A);
        drawGradientRect(left, top, left + W, top + H, 0xF8181C24, 0xF80E1116);
        TileTransmitter t = transmitter();
        if (t == null) return;
        TransmitterState s = t.state;
        long now = System.currentTimeMillis();

        drawCenteredString(
            fontRendererObj,
            I18n.format("akashicfm.gui.transmitter.title"),
            left + W / 2,
            top + 6,
            0xFFFFFF);
        String owner = s.ownerName.isEmpty() ? I18n.format("akashicfm.gui.no_owner")
            : I18n.format("akashicfm.gui.owner", s.ownerName);
        drawCenteredString(
            fontRendererObj,
            owner + " · " + I18n.format("akashicfm.gui.access." + s.access.name()),
            left + W / 2,
            top + 17,
            0xFF9AA4B0);

        urlField.drawTextBox();
        hint(urlField, "akashicfm.gui.url_hint", top + 34);
        fontRendererObj.drawString(I18n.format("akashicfm.gui.frequency"), left + 9, top + 55, 0xFFC8D0DA);
        dial.draw(fontRendererObj, s.frequency, permsKnown && canControl, now);
        nameField.drawTextBox();
        hint(nameField, "akashicfm.gui.transmitter.name_hint", top + 75);

        super.drawScreen(mouseX, mouseY, partialTicks);

        int lx = left + 9, width = W - 18, ly = top + 114;
        line(I18n.format("akashicfm.gui.transmitter.coverage", s.range, s.antennas), lx, ly, width, 0xFFC8D0DA);
        ly += 11;
        if (s.energyRequired) {
            int color = s.powered ? 0xFFC8D0DA : 0xFFFF6060;
            String energy = I18n.format("akashicfm.gui.transmitter.energy", s.energy, s.energyCapacity);
            if (!s.powered) energy += " · " + I18n.format("akashicfm.gui.transmitter.no_energy");
            line(energy, lx, ly, width, color);
            drawEnergyBar(lx, ly + 9, width, s);
        } else {
            line(I18n.format("akashicfm.gui.transmitter.energy_free"), lx, ly, width, 0xFF9AA4B0);
        }
        ly += 16;
        String status;
        int color;
        String freq = Frequency.format(s.frequency);
        if (!permsKnown) {
            status = I18n.format("akashicfm.gui.status.checking");
            color = 0xFF9AA4B0;
        } else if (s.active()) {
            status = I18n.format("akashicfm.gui.transmitter.on_air", freq);
            color = 0xFF70FF80;
        } else if (s.broadcasting) {
            status = I18n.format("akashicfm.gui.transmitter.waiting_energy", freq);
            color = 0xFFFFD070;
        } else {
            status = I18n.format("akashicfm.gui.transmitter.off_air", freq);
            color = 0xFF9AA4B0;
        }
        line(status, lx, ly, width, color);
        ly += 11;
        if (s.active()) {
            String what = !s.nowPlaying.isEmpty() ? "♪ " + s.nowPlaying : NowPlaying.hostOf(s.url);
            line(what, lx, ly, width, 0xFFB8C8FF);
        }
        ly += 11;
        if (!noticeText.isEmpty() && now < noticeUntil) {
            line(noticeText, lx, ly, width, noticeError ? 0xFFFF6060 : 0xFF70FF80);
        }
    }

    private void hint(GuiTextField field, String key, int textY) {
        if (!field.getText()
            .isEmpty() || field.isFocused()) return;
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(I18n.format(key), 146), left + 13, textY, 0xFF606870);
    }

    private void line(String text, int lx, int ly, int width, int color) {
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text, width), lx, ly, color);
    }

    private void drawEnergyBar(int lx, int ly, int width, TransmitterState s) {
        drawRect(lx, ly, lx + width, ly + 4, 0xFF2A2F36);
        if (s.energyCapacity <= 0) return;
        int filled = (int) Math.round((double) width * Math.min(s.energy, s.energyCapacity) / s.energyCapacity);
        if (filled > 0) drawRect(lx, ly, lx + filled, ly + 4, s.powered ? 0xFFE0B040 : 0xFFB04040);
    }

    // ---- Utilidades ----

    private void localNotice(boolean error, String key) {
        noticeText = I18n.format(key);
        noticeError = error;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    private void send(Action action, int intArg, String strArg) {
        FmNetwork.sendToServer(new C2SRadioAction(x, y, z, action, intArg, strArg));
    }

    private TileTransmitter transmitter() {
        World world = mc == null ? null : mc.theWorld;
        if (world == null || y < 0 || y > 255) return null;
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileTransmitter && !te.isInvalid() ? (TileTransmitter) te : null;
    }
}
