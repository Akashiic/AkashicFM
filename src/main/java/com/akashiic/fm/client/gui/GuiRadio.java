package com.akashiic.fm.client.gui;

import java.util.Collections;

import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.lwjgl.input.Keyboard;

import com.akashiic.fm.client.NowPlaying;
import com.akashiic.fm.client.audio.AudioEngine;
import com.akashiic.fm.client.audio.RadioAudioController;
import com.akashiic.fm.client.relay.RelayClient;
import com.akashiic.fm.client.relay.RelayFeed;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.Transport;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.network.C2SRadioAction;
import com.akashiic.fm.network.C2SRadioAction.Action;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;

/**
 * Tela da rádio. Não guarda estado próprio: tudo que mostra vem do TE (sincronizado pelo servidor) e tudo
 * que muda vira um pedido ao servidor. Os controles só habilitam depois que o servidor diz o que este
 * jogador pode fazer; a tela fecha sozinha se a rádio sumir ou o jogador se afastar.
 */
public final class GuiRadio extends GuiScreen {

    private static final int W = 256;
    private static final int H = 236;
    private static final int VISIBLE_STATIONS = 5;
    private static final long NOTICE_MILLIS = 8000;
    /** Mesmo limite que o servidor usa para aceitar ações. */
    private static final double MAX_DISTANCE_SQ = 64.0;

    /** Cores da tela, na ordem do botão de cor. A primeira é a padrão. */
    static final int[] PALETTE = { RadioLimits.SCREEN_COLOR_DEFAULT, 0xFFFFFF, 0xFFB000, 0xFF5555, 0xFF55FF, 0x8F7BFF,
        0x55AAFF, 0x55FFFF, 0x60FFD0, 0xFFFF55, 0xFF8040, 0xAAAAAA };

    private static final int ID_PLAY = 1, ID_STOP = 2, ID_VOLUME = 3, ID_RANGE = 4, ID_SAVE = 5, ID_UP = 6, ID_DOWN = 7,
        ID_SCREEN_OK = 8, ID_COLOR = 9, ID_ACCESS = 10, ID_REDSTONE = 11, ID_UNLINK = 12, ID_STATION_BASE = 100,
        ID_REMOVE_BASE = 200;

    private final int x, y, z;
    private int left, top;

    private GuiTextField urlField, screenField;
    private FlatButton play, stop, save, up, down, screenOk, color, access, redstone, unlink;
    private FlatSlider volume, range;
    private final FlatButton[] stationButtons = new FlatButton[VISIBLE_STATIONS];
    private final FlatButton[] removeButtons = new FlatButton[VISIBLE_STATIONS];
    private int scroll;

    private boolean permsRequested, permsKnown, canControl, canAdmin;
    private int maxRange = RadioLimits.RANGE_DEFAULT;
    private RadioAccess lastAccess;
    /** O jogador digitou algo diferente do estado: o campo para de seguir o servidor até bater de novo. */
    private boolean urlEdited, screenEdited;

    private String noticeText = "";
    private boolean noticeError;
    private long noticeUntil;

    public GuiRadio(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isFor(int px, int py, int pz) {
        return px == x && py == y && pz == z;
    }

    public boolean permsKnown() {
        return permsKnown;
    }

    public boolean canAdmin() {
        return permsKnown && canAdmin;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false; // no singleplayer, pausar pararia o som que o jogador está ajustando
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        TileRadio radio = radio();
        if (radio == null) {
            mc.displayGuiScreen(null);
            return;
        }
        RadioState s = radio.state;
        if (lastAccess == null) lastAccess = s.access;
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();

        // Num redimensionamento a tela é recriada: o texto que estava sendo digitado continua.
        String urlText = urlField != null ? urlField.getText() : s.url;
        String screenText = screenField != null ? screenField.getText() : s.screenText;

        urlField = new GuiTextField(fontRendererObj, left + 9, top + 31, 154, 14);
        urlField.setMaxStringLength(RadioLimits.MAX_URL_LENGTH);
        setFieldText(urlField, urlText);
        play = add(new FlatButton(ID_PLAY, left + 168, top + 30, 38, 16, I18n.format("akashicfm.gui.play")));
        stop = add(new FlatButton(ID_STOP, left + 210, top + 30, 38, 16, I18n.format("akashicfm.gui.stop")));

        volume = add(
            new FlatSlider(
                ID_VOLUME,
                left + 8,
                top + 50,
                118,
                16,
                "akashicfm.gui.volume",
                RadioLimits.VOLUME_MIN,
                RadioLimits.VOLUME_MAX,
                s.volume,
                v -> send(Action.SET_VOLUME, v, "")));
        range = add(
            new FlatSlider(
                ID_RANGE,
                left + 130,
                top + 50,
                118,
                16,
                "akashicfm.gui.range",
                RadioLimits.RANGE_MIN,
                Math.max(maxRange, s.range),
                s.range,
                v -> send(Action.SET_RANGE, v, "")));

        save = add(new FlatButton(ID_SAVE, left + 168, top + 70, 80, 14, I18n.format("akashicfm.gui.save_station")));
        for (int i = 0; i < VISIBLE_STATIONS; i++) {
            int rowY = top + 86 + i * 15;
            stationButtons[i] = add(new FlatButton(ID_STATION_BASE + i, left + 8, rowY, 210, 14, ""));
            stationButtons[i].alignLeft = true;
            removeButtons[i] = add(new FlatButton(ID_REMOVE_BASE + i, left + 220, rowY, 14, 14, "x"));
        }
        up = add(new FlatButton(ID_UP, left + 236, top + 86, 12, 14, "▲"));
        down = add(new FlatButton(ID_DOWN, left + 236, top + 146, 12, 14, "▼"));

        screenField = new GuiTextField(fontRendererObj, left + 9, top + 166, 104, 14);
        screenField.setMaxStringLength(RadioLimits.MAX_SCREEN_TEXT);
        setFieldText(screenField, screenText);
        screenOk = add(new FlatButton(ID_SCREEN_OK, left + 118, top + 165, 24, 16, I18n.format("akashicfm.gui.ok")));
        color = add(new FlatButton(ID_COLOR, left + 146, top + 165, 50, 16, I18n.format("akashicfm.gui.color")));
        access = add(new FlatButton(ID_ACCESS, left + 200, top + 165, 48, 16, ""));

        redstone = add(new FlatButton(ID_REDSTONE, left + 8, top + 185, 150, 16, ""));
        unlink = add(new FlatButton(ID_UNLINK, left + 162, top + 185, 86, 16, ""));

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
    }

    @Override
    public void updateScreen() {
        TileRadio radio = radio();
        if (radio == null || mc.thePlayer == null
            || mc.thePlayer.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) > MAX_DISTANCE_SQ) {
            mc.displayGuiScreen(null);
            return;
        }
        urlField.updateCursorCounter();
        screenField.updateCursorCounter();
        refreshWidgets(radio.state);
    }

    // ---- Respostas do servidor (ClientProxy, thread principal) ----

    public void onPerms(S2CRadioPerms perms) {
        permsKnown = true;
        canControl = perms.canControl;
        canAdmin = perms.canAdmin;
        maxRange = perms.maxRange;
        TileRadio radio = radio();
        if (radio != null) refreshWidgets(radio.state);
    }

    public void onNotice(S2CRadioNotice notice) {
        noticeText = I18n.format(notice.key, notice.arg);
        noticeError = notice.error;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    public void onStateUpdated() {
        TileRadio radio = radio();
        if (radio == null) return;
        RadioState s = radio.state;
        if (urlField != null && urlField.getText()
            .equals(s.url)) urlEdited = false;
        if (screenField != null && screenField.getText()
            .equals(s.screenText)) screenEdited = false;
        // Quem pode mexer depende do acesso (pública/privada): mudou, pergunta de novo.
        if (lastAccess != s.access) {
            lastAccess = s.access;
            send(Action.REQUEST_PERMS, 0, "");
        }
        if (urlField != null) refreshWidgets(s);
    }

    // ---- Estado dos controles ----

    private void refreshWidgets(RadioState s) {
        boolean control = permsKnown && canControl;
        boolean admin = permsKnown && canAdmin;

        urlField.setEnabled(control);
        if (!control) urlField.setFocused(false);
        if (!urlEdited && !urlField.isFocused()
            && !urlField.getText()
                .equals(s.url))
            setFieldText(urlField, s.url);
        play.enabled = control;
        stop.enabled = control && s.playing;

        volume.enabled = control;
        volume.setValue(s.volume);
        range.enabled = control;
        range.setRange(RadioLimits.RANGE_MIN, Math.max(maxRange, s.range));
        range.setValue(s.range);

        String typed = TextSanitizer.cleanUrl(urlField.getText(), RadioLimits.MAX_URL_LENGTH);
        save.enabled = admin && s.stations.size() < RadioLimits.MAX_STATIONS
            && !(typed.isEmpty() ? s.url : typed).isEmpty();

        int maxScroll = Math.max(0, s.stations.size() - VISIBLE_STATIONS);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (int i = 0; i < VISIBLE_STATIONS; i++) {
            int idx = scroll + i;
            boolean exists = idx < s.stations.size();
            stationButtons[i].visible = exists;
            removeButtons[i].visible = exists;
            if (!exists) continue;
            String url = s.stations.get(idx);
            boolean current = s.playing && url.equals(s.url);
            stationButtons[i].displayString = (current ? "▶ " : "") + displayUrl(url);
            stationButtons[i].textColor = current ? 0xFF7CFF8A : 0;
            stationButtons[i].enabled = control;
            removeButtons[i].enabled = admin;
        }
        up.visible = down.visible = s.stations.size() > VISIBLE_STATIONS;
        up.enabled = scroll > 0;
        down.enabled = scroll < maxScroll;

        screenField.setEnabled(admin);
        if (!admin) screenField.setFocused(false);
        if (!screenEdited && !screenField.isFocused()
            && !screenField.getText()
                .equals(s.screenText))
            setFieldText(screenField, s.screenText);
        screenOk.enabled = admin && !screenField.getText()
            .equals(s.screenText);
        color.enabled = admin;
        color.swatch = s.screenColor;
        access.enabled = admin;
        access.displayString = I18n.format("akashicfm.gui.access." + s.access.name());
        redstone.enabled = admin;
        redstone.displayString = I18n
            .format("akashicfm.gui.redstone", I18n.format("akashicfm.gui.redstone." + s.redstoneMode.name()));
        unlink.enabled = admin && !s.speakers.isEmpty();
        unlink.displayString = I18n.format("akashicfm.gui.unlink_all", s.speakers.size());
    }

    /**
     * Texto vindo do estado: mostra o começo (o GuiTextField do 1.7.10 deixa o cursor no fim e, com texto maior
     * que o campo, desenha um falso trecho selecionado).
     */
    private static void setFieldText(GuiTextField field, String text) {
        field.setText(text);
        field.setCursorPositionZero();
    }

    /** URL sem o esquema, para caber mais na linha. */
    static String displayUrl(String url) {
        if (url.startsWith("https://")) return url.substring(8);
        if (url.startsWith("http://")) return url.substring(7);
        return url;
    }

    // ---- Entrada ----

    @Override
    protected void actionPerformed(GuiButton button) {
        TileRadio radio = radio();
        if (radio == null) return;
        RadioState s = radio.state;
        switch (button.id) {
            case ID_PLAY:
                doPlay(s);
                return;
            case ID_STOP:
                send(Action.STOP, 0, "");
                return;
            case ID_SAVE: {
                String typed = TextSanitizer.cleanUrl(urlField.getText(), RadioLimits.MAX_URL_LENGTH);
                String url = typed.isEmpty() ? s.url : typed;
                if (!url.isEmpty()) send(Action.ADD_STATION, 0, url);
                return;
            }
            case ID_UP:
                scroll--;
                refreshWidgets(s);
                return;
            case ID_DOWN:
                scroll++;
                refreshWidgets(s);
                return;
            case ID_SCREEN_OK:
                commitScreenText(s);
                return;
            case ID_COLOR:
                send(Action.SET_SCREEN_COLOR, nextColor(s.screenColor), "");
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
            case ID_UNLINK:
                send(Action.UNLINK_ALL_SPEAKERS, 0, "");
                return;
            default:
                break;
        }
        // Sliders respondem ao soltar o mouse (FlatSlider), não aqui.
        if (button.id >= ID_STATION_BASE && button.id < ID_STATION_BASE + VISIBLE_STATIONS) {
            int idx = scroll + button.id - ID_STATION_BASE;
            if (idx < s.stations.size()) send(Action.PLAY_STATION, idx, s.stations.get(idx));
        } else if (button.id >= ID_REMOVE_BASE && button.id < ID_REMOVE_BASE + VISIBLE_STATIONS) {
            int idx = scroll + button.id - ID_REMOVE_BASE;
            if (idx < s.stations.size()) send(Action.REMOVE_STATION, idx, s.stations.get(idx));
        }
    }

    static int nextColor(int current) {
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == (current & 0xFFFFFF)) return PALETTE[(i + 1) % PALETTE.length];
        }
        return PALETTE[0];
    }

    private void doPlay(RadioState s) {
        String typed = TextSanitizer.cleanUrl(urlField.getText(), RadioLimits.MAX_URL_LENGTH);
        if (typed.isEmpty() || typed.equals(s.url)) {
            if (s.url.isEmpty()) {
                localNotice(true, "akashicfm.notice.no_url");
                return;
            }
            send(Action.PLAY, 0, "");
            return;
        }
        // Checagem sintática local (sem DNS) só para avisar na hora; quem decide é a política do servidor.
        if (!RadioState.isPlausibleUrl(typed)) {
            localNotice(true, "akashicfm.notice.url_invalid");
            return;
        }
        send(Action.PLAY, 0, typed);
    }

    private void commitScreenText(RadioState s) {
        String text = TextSanitizer.clean(screenField.getText(), RadioLimits.MAX_SCREEN_TEXT);
        if (!text.equals(s.screenText)) send(Action.SET_SCREEN_TEXT, 0, text);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean enter = keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER;
        TileRadio radio = radio();
        if (urlField.isFocused()) {
            if (enter) {
                if (radio != null && play.enabled) doPlay(radio.state);
                return;
            }
            if (urlField.textboxKeyTyped(typedChar, keyCode)) {
                if (radio != null) urlEdited = !urlField.getText()
                    .equals(radio.state.url);
                return;
            }
        }
        if (screenField.isFocused()) {
            if (enter) {
                if (radio != null && canAdmin) commitScreenText(radio.state);
                screenField.setFocused(false);
                return;
            }
            if (screenField.textboxKeyTyped(typedChar, keyCode)) {
                if (radio != null) screenEdited = !screenField.getText()
                    .equals(radio.state.screenText);
                return;
            }
        }
        boolean typing = urlField.isFocused() || screenField.isFocused();
        if (keyCode == Keyboard.KEY_ESCAPE || (!typing && keyCode == mc.gameSettings.keyBindInventory.getKeyCode())) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        // Campo desabilitado não ganha foco (o GuiTextField do 1.7.10 deixaria focar mesmo desabilitado).
        if (permsKnown && canControl) urlField.mouseClicked(mouseX, mouseY, mouseButton);
        else urlField.setFocused(false);
        if (permsKnown && canAdmin) screenField.mouseClicked(mouseX, mouseY, mouseButton);
        else screenField.setFocused(false);
    }

    // ---- Desenho ----

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, 0xFF3A7BD5);
        drawGradientRect(left, top, left + W, top + H, 0xF8181C24, 0xF80E1116);
        TileRadio radio = radio();
        if (radio == null) return;
        RadioState s = radio.state;

        drawCenteredString(fontRendererObj, I18n.format("akashicfm.gui.title"), left + W / 2, top + 6, 0xFFFFFF);
        String owner = s.ownerName.isEmpty() ? I18n.format("akashicfm.gui.no_owner")
            : I18n.format("akashicfm.gui.owner", s.ownerName);
        drawCenteredString(
            fontRendererObj,
            owner + " · " + I18n.format("akashicfm.gui.access." + s.access.name()),
            left + W / 2,
            top + 17,
            0xFF9AA4B0);

        urlField.drawTextBox();
        if (urlField.getText()
            .isEmpty() && !urlField.isFocused()) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(I18n.format("akashicfm.gui.url_hint"), 146),
                left + 13,
                top + 34,
                0xFF606870);
        }
        fontRendererObj.drawString(
            I18n.format("akashicfm.gui.stations", s.stations.size(), RadioLimits.MAX_STATIONS),
            left + 9,
            top + 73,
            0xFFC8D0DA);
        if (s.stations.isEmpty()) {
            fontRendererObj.drawString(I18n.format("akashicfm.gui.no_stations"), left + 12, top + 90, 0xFF606870);
        }
        screenField.drawTextBox();
        if (screenField.getText()
            .isEmpty() && !screenField.isFocused()) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(I18n.format("akashicfm.gui.screen_hint"), 96),
                left + 13,
                top + 169,
                0xFF606870);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawStatus(radio, s);

        for (int i = 0; i < VISIBLE_STATIONS; i++) {
            int idx = scroll + i;
            if (idx < s.stations.size() && stationButtons[i].isHovered(mouseX, mouseY)) {
                func_146283_a(Collections.singletonList(s.stations.get(idx)), mouseX, mouseY);
            }
        }
    }

    private void drawStatus(TileRadio radio, RadioState s) {
        int lx = left + 9;
        int width = W - 18;
        String line;
        int color;
        if (!permsKnown) {
            line = I18n.format("akashicfm.gui.status.checking");
            color = 0xFF9AA4B0;
        } else if (!s.playing) {
            line = I18n.format("akashicfm.gui.status.stopped");
            color = 0xFF9AA4B0;
        } else {
            line = I18n.format("akashicfm.gui.transport." + s.transport.name()) + " · " + playbackStatus(radio, s);
            color = 0xFFE0E6EE;
        }
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(line, width), lx, top + 205, color);

        String title = s.playing ? NowPlaying.title(radio) : "";
        String second = !s.status.isEmpty() ? translateStatus(s.status) : !title.isEmpty() ? "\u266A " + title : "";
        if (!second.isEmpty()) {
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(second, width), lx, top + 215, 0xFFB8C8FF);
        }
        if (!noticeText.isEmpty() && System.currentTimeMillis() < noticeUntil) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(noticeText, width),
                lx,
                top + 225,
                noticeError ? 0xFFFF6060 : 0xFF70FF80);
        }
    }

    private String playbackStatus(TileRadio radio, RadioState s) {
        if (s.transport != Transport.DIRECT && s.transport != Transport.RELAY) {
            return I18n.format("akashicfm.gui.status.no_transport");
        }
        if (!FmConfig.Client.enableAudio) return I18n.format("akashicfm.gui.status.audio_disabled");
        if (s.transport == Transport.DIRECT && !FmConfig.Client.allowDirectStreams) {
            return I18n.format("akashicfm.gui.status.direct_disabled");
        }
        if (mc.gameSettings.getSoundLevel(SoundCategory.MASTER) <= 0f
            || mc.gameSettings.getSoundLevel(SoundCategory.RECORDS) <= 0f
            || FmConfig.Client.radioVolume <= 0) return I18n.format("akashicfm.gui.status.muted");
        String key;
        if (s.transport == Transport.RELAY) {
            RelayFeed relay = RelayClient.feedForUrl(s.url);
            if (relay == null) return I18n.format("akashicfm.gui.status.relay_waiting");
            key = RadioAudioController.relayKey(relay);
        } else {
            key = RadioAudioController.keyFor(radio);
        }
        AudioEngine.PlaybackInfo info = AudioEngine.INSTANCE.info(key);
        if (info == null) return I18n.format("akashicfm.gui.status.not_here");
        switch (info.feedStatus) {
            case CONNECTING:
                return I18n.format("akashicfm.gui.status.connecting");
            case RECONNECTING:
                return I18n.format("akashicfm.gui.status.reconnecting");
            case ERROR:
                if (!info.done) break; // ainda tocando o que já chegou
                return I18n.format("akashicfm.gui.status.error", info.detail);
            case ENDED:
                if (!info.done) break;
                return I18n.format("akashicfm.gui.status.ended");
            default:
                break;
        }
        if (!info.playing) return I18n.format("akashicfm.gui.status.buffering");
        if (!Double.isNaN(info.syncErrorMs)) {
            return I18n.format("akashicfm.gui.status.playing_synced", String.format("%+.0f", info.syncErrorMs));
        }
        return I18n.format("akashicfm.gui.status.playing");
    }

    /** Status vindo do servidor: chave de tradução, com argumento opcional depois de '|'. */
    static String translateStatus(String status) {
        if (!status.startsWith("akashicfm.")) return status;
        int bar = status.indexOf('|');
        return bar < 0 ? I18n.format(status) : I18n.format(status.substring(0, bar), status.substring(bar + 1));
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

    private TileRadio radio() {
        World world = mc == null ? null : mc.theWorld;
        if (world == null || y < 0 || y > 255) return null;
        TileEntity te = world.getTileEntity(x, y, z);
        return te instanceof TileRadio && !te.isInvalid() ? (TileRadio) te : null;
    }
}
