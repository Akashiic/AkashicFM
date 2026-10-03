package com.akashiic.fm.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.akashiic.fm.client.ClientIPod;
import com.akashiic.fm.client.ClientPortables;
import com.akashiic.fm.common.IPodState;
import com.akashiic.fm.common.IPodTrack;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.content.ItemIPod;
import com.akashiic.fm.network.C2SIPodAction;
import com.akashiic.fm.network.C2SIPodAction.Action;
import com.akashiic.fm.network.FmNetwork;
import com.akashiic.fm.network.S2CIPodStatus;
import com.akashiic.fm.network.S2CPortableSources;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;
import com.akashiic.fm.server.ipod.IPodActionHandler;

/**
 * Tela do iPod do slot. Como as outras, não guarda estado: mostra o item (fila, faixa atual e pausa, do NBT que o
 * inventário sincroniza), o status que o servidor manda ao dono ({@link ClientIPod}: posição, motivo) e manda pedidos.
 * Fecha sozinha se o slot deixar de ter o iPod.
 * <p>
 * Fila: clique seleciona, clique duplo toca, roda do mouse rola.
 */
public final class GuiIPod extends GuiScreen implements FmScreen {

    private static final int W = 256;
    private static final int H = 226;
    private static final long NOTICE_MILLIS = 8000;
    private static final int ROWS = 6, ROW_H = 12, LIST_Y = 112;
    private static final long DOUBLE_CLICK_MS = 350;

    private static final int ID_ADD = 1, ID_PREV = 2, ID_TOGGLE = 3, ID_STOP = 4, ID_NEXT = 5, ID_SHUFFLE = 6,
        ID_REPEAT = 7, ID_REMOVE = 8, ID_CLEAR = 9, ID_VOLUME = 10, ID_UP = 11, ID_DOWN = 12;

    private final int slot;
    private int left, top;
    private GuiTextField input;
    private FlatButton add, prev, toggle, stop, next, shuffle, repeat, remove, clear, up, down;
    private FlatSlider volume;
    private int scroll;
    private int selected = -1;
    private int lastClickRow = -1;
    private long lastClickMs;
    private NBTTagCompound cachedTag;
    private IPodState cached;

    private String noticeText = "";
    private boolean noticeError;
    private long noticeUntil;

    public GuiIPod(int slot) {
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }

    @Override
    public boolean isFor(int x, int y, int z) {
        return y == IPodActionHandler.NOTICE_Y;
    }

    @Override
    public void onPerms(S2CRadioPerms perms) {}

    @Override
    public void onNotice(S2CRadioNotice notice) {
        noticeText = format(notice.key + (notice.arg.isEmpty() ? "" : "|" + notice.arg));
        noticeError = notice.error;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    /** "chave|a|b" traduzido com os argumentos (texto que não é chave do mod sai como veio). */
    static String format(String status) {
        if (status == null || status.isEmpty()) return "";
        if (!status.startsWith("akashicfm.")) return status;
        String[] parts = status.split("\\|", -1);
        Object[] args = new Object[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return I18n.format(parts[0], args);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private ItemStack stack() {
        if (mc == null || mc.thePlayer == null) return null;
        return ItemIPod.at(mc.thePlayer, slot);
    }

    /**
     * O estado do item, lido de novo só quando o NBT muda (a fila tem até 200 faixas e a tela desenha a cada quadro).
     * O cliente nunca escreve no NBT: cada atualização do servidor chega como uma cópia nova.
     */
    private IPodState state(ItemStack st) {
        NBTTagCompound tag = st.getTagCompound();
        if (cached == null || tag != cachedTag) {
            cached = ItemIPod.state(st);
            cachedTag = tag;
        }
        return cached;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ItemStack st = stack();
        if (st == null) {
            mc.displayGuiScreen(null);
            return;
        }
        IPodState s = state(st);
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        String typed = input != null ? input.getText() : "";
        input = new GuiTextField(fontRendererObj, left + 8, top + 31, 180, 14);
        input.setMaxStringLength(RadioLimits.MAX_URL_LENGTH);
        input.setText(typed);
        add = add(new FlatButton(ID_ADD, left + 192, top + 30, 56, 16, I18n.format("akashicfm.gui.ipod.add")));
        int y = top + 80;
        // Larguras para o texto caber (o botão corta o que passa): 18+48+40+18+52+54 e 2 px entre eles.
        prev = add(new FlatButton(ID_PREV, left + 8, y, 18, 16, "«"));
        toggle = add(new FlatButton(ID_TOGGLE, left + 28, y, 48, 16, ""));
        stop = add(new FlatButton(ID_STOP, left + 78, y, 40, 16, I18n.format("akashicfm.gui.stop")));
        next = add(new FlatButton(ID_NEXT, left + 120, y, 18, 16, "»"));
        shuffle = add(new FlatButton(ID_SHUFFLE, left + 140, y, 52, 16, I18n.format("akashicfm.gui.ipod.shuffle")));
        repeat = add(new FlatButton(ID_REPEAT, left + 194, y, 54, 16, I18n.format("akashicfm.gui.ipod.repeat")));
        up = add(new FlatButton(ID_UP, left + 236, top + LIST_Y, 12, 12, "▲"));
        down = add(new FlatButton(ID_DOWN, left + 236, top + LIST_Y + ROWS * ROW_H - 12, 12, 12, "▼"));
        int by = top + LIST_Y + ROWS * ROW_H + 4;
        remove = add(new FlatButton(ID_REMOVE, left + 8, by, 58, 16, I18n.format("akashicfm.gui.ipod.remove")));
        clear = add(new FlatButton(ID_CLEAR, left + 69, by, 50, 16, I18n.format("akashicfm.gui.ipod.clear")));
        volume = add(
            new FlatSlider(
                ID_VOLUME,
                left + 122,
                by,
                126,
                16,
                "akashicfm.gui.volume",
                RadioLimits.VOLUME_MIN,
                RadioLimits.VOLUME_MAX,
                s.volume,
                v -> send(Action.VOLUME, v, "")));
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
        ItemStack st = stack();
        if (st == null || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        input.updateCursorCounter();
        refreshWidgets(state(st));
    }

    private void refreshWidgets(IPodState s) {
        boolean has = !s.queue.isEmpty();
        toggle.displayString = I18n.format(s.on && !s.paused ? "akashicfm.gui.ipod.pause" : "akashicfm.gui.play");
        toggle.enabled = has;
        stop.enabled = s.on;
        prev.enabled = next.enabled = has;
        shuffle.enabled = s.queue.size() - Math.max(0, s.index + 1) >= 2;
        if (selected >= s.queue.size()) selected = -1;
        remove.enabled = selected >= 0;
        clear.enabled = has;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, s.queue.size() - ROWS)));
        up.enabled = scroll > 0;
        down.enabled = scroll < s.queue.size() - ROWS;
        volume.setValue(s.volume);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        ItemStack st = stack();
        if (st == null) return;
        IPodState s = state(st);
        switch (button.id) {
            case ID_ADD:
                submit();
                return;
            case ID_PREV:
                send(Action.PREVIOUS, 0, "");
                return;
            case ID_TOGGLE:
                send(Action.TOGGLE, 0, "");
                return;
            case ID_STOP:
                send(Action.STOP, 0, "");
                return;
            case ID_NEXT:
                send(Action.NEXT, 0, "");
                return;
            case ID_SHUFFLE:
                send(Action.SHUFFLE, 0, "");
                return;
            case ID_REPEAT:
                send(Action.REPEAT, 0, "");
                return;
            case ID_REMOVE:
                if (selected >= 0 && selected < s.queue.size()) send(Action.REMOVE, selected, "");
                selected = -1;
                return;
            case ID_CLEAR:
                send(Action.CLEAR, 0, "");
                selected = -1;
                return;
            case ID_UP:
                scroll = Math.max(0, scroll - 1);
                return;
            case ID_DOWN:
                scroll = Math.min(Math.max(0, s.queue.size() - ROWS), scroll + 1);
                return;
            default:
                return;
        }
    }

    private void submit() {
        String typed = TextSanitizer.clean(input.getText(), RadioLimits.MAX_URL_LENGTH);
        if (typed.isEmpty()) {
            localNotice(true, "akashicfm.ipod.notice.type_link");
            return;
        }
        send(Action.ADD, 0, typed);
        input.setText("");
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean enter = keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER;
        if (input.isFocused()) {
            if (enter) {
                submit();
                return;
            }
            if (input.textboxKeyTyped(typedChar, keyCode)) return;
        }
        boolean typing = input.isFocused();
        if (!typing && keyCode == Keyboard.KEY_SPACE) {
            send(Action.TOGGLE, 0, "");
            return;
        }
        if (!typing && keyCode == Keyboard.KEY_DELETE && selected >= 0) {
            send(Action.REMOVE, selected, "");
            selected = -1;
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || (!typing && keyCode == mc.gameSettings.keyBindInventory.getKeyCode())) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        ItemStack st = stack();
        if (st == null) return;
        int size = state(st).queue.size();
        scroll = Math.max(0, Math.min(Math.max(0, size - ROWS), scroll + (wheel > 0 ? -1 : 1)));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        input.mouseClicked(mouseX, mouseY, mouseButton);
        ItemStack st = stack();
        if (st == null || mouseButton != 0) return;
        int lx = left + 8, ly = top + LIST_Y;
        if (mouseX < lx || mouseX >= left + 232 || mouseY < ly || mouseY >= ly + ROWS * ROW_H) return;
        int row = scroll + (mouseY - ly) / ROW_H;
        IPodState s = state(st);
        if (row >= s.queue.size()) return;
        long now = System.currentTimeMillis();
        if (row == lastClickRow && now - lastClickMs <= DOUBLE_CLICK_MS) {
            send(Action.PLAY, row, "");
            lastClickRow = -1;
        } else {
            lastClickRow = row;
            lastClickMs = now;
        }
        selected = row;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, 0xFFB0B8C8);
        drawGradientRect(left, top, left + W, top + H, 0xF8181C24, 0xF80E1116);
        ItemStack st = stack();
        if (st == null) return;
        IPodState s = state(st);
        long now = System.currentTimeMillis();
        S2CPortableSources.Entry own = mc.thePlayer == null ? null
            : ClientPortables.own(mc.thePlayer.getEntityId(), now);
        S2CIPodStatus status = ClientIPod.status(s.id, now);

        drawCenteredString(fontRendererObj, I18n.format("akashicfm.gui.ipod.title"), left + W / 2, top + 6, 0xFFFFFF);
        String state = I18n
            .format(!s.on ? "akashicfm.ipod.off" : s.paused ? "akashicfm.ipod.paused" : "akashicfm.ipod.on");
        String sub = state + " · "
            + I18n
                .format(own != null && own.headphones ? "akashicfm.gui.ipod.headphones" : "akashicfm.gui.ipod.speaker");
        sub = fontRendererObj.trimStringToWidth(sub, W - 12);
        drawCenteredString(fontRendererObj, sub, left + W / 2, top + 17, 0xFF9AA4B0);

        input.drawTextBox();
        if (input.getText()
            .isEmpty() && !input.isFocused()) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(I18n.format("akashicfm.gui.ipod.hint"), 172),
                left + 12,
                top + 34,
                0xFF606870);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);

        // Faixa atual, motivo e progresso.
        int lx = left + 9, width = W - 18;
        IPodTrack cur = s.current();
        String title = cur == null ? I18n.format("akashicfm.gui.ipod.empty") : "♪ " + cur.display();
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(title, width), lx, top + 50, 0xFFE0E6EE);
        String line;
        int color = 0xFF9AA4B0;
        if (status != null && !status.status.isEmpty()) {
            line = format(status.status);
            color = status.phase == S2CIPodStatus.Phase.ERROR ? 0xFFFF8070 : 0xFFFFD070;
        } else if (!s.on) {
            line = s.queue.isEmpty() ? I18n.format("akashicfm.gui.ipod.hint_empty") : I18n.format("akashicfm.ipod.off");
        } else {
            line = cur != null && cur.source != IPodTrack.Source.SOUNDCLOUD ? I18n.format("akashicfm.gui.ipod.mirror")
                : I18n.format("akashicfm.gui.ipod.direct");
        }
        long pos = status == null ? 0 : ClientIPod.positionMs(status, now);
        long dur = status != null && status.durationMs > 0 ? status.durationMs
            : cur == null ? 0 : cur.durationSec * 1000L;
        String time = s.on ? clock(pos) + (dur > 0 ? " / " + clock(dur) : "") : dur > 0 ? clock(dur) : "";
        int tw = fontRendererObj.getStringWidth(time);
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(line, width - tw - 6), lx, top + 61, color);
        fontRendererObj.drawString(time, left + W - 9 - tw, top + 61, 0xFFB8C8FF);
        drawRect(lx, top + 72, lx + width, top + 75, 0xFF2A3038);
        if (s.on && dur > 0) {
            int fill = (int) (width * Math.min(1.0, pos / (double) dur));
            drawRect(lx, top + 72, lx + fill, top + 75, 0xFF6FB0FF);
        }

        // Fila.
        int ly = top + LIST_Y;
        drawRect(left + 8, ly, left + 232, ly + ROWS * ROW_H, 0x60000000);
        for (int r = 0; r < ROWS; r++) {
            int i = scroll + r;
            if (i >= s.queue.size()) break;
            IPodTrack t = s.queue.get(i);
            int ry = ly + r * ROW_H;
            if (i == selected) drawRect(left + 8, ry, left + 232, ry + ROW_H, 0x603A7BD5);
            int c = i == s.index ? 0xFF70FF80 : 0xFFD0D6E0;
            String d = t.durationSec > 0 ? clock(t.durationSec * 1000L) : "";
            int dw = fontRendererObj.getStringWidth(d);
            String mark = t.source == IPodTrack.Source.SOUNDCLOUD ? ""
                : t.source == IPodTrack.Source.YOUTUBE ? "[YT] " : "[SP] ";
            String text = (i + 1) + ". " + mark + t.display();
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text, 220 - dw - 8), left + 11, ry + 2, c);
            fontRendererObj.drawString(d, left + 229 - dw, ry + 2, 0xFF8890A0);
        }
        // O modo de repetição fica aqui (no botão não caberia).
        String count = I18n.format("akashicfm.ipod.queue", s.queue.size()) + " · "
            + I18n.format("akashicfm.gui.ipod.repeat." + s.repeat.name());
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(count, W - 18), left + 9, top + LIST_Y - 11, 0xFF707880);

        if (!noticeText.isEmpty() && now < noticeUntil) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(noticeText, width),
                lx,
                top + H - 12,
                noticeError ? 0xFFFF6060 : 0xFF70FF80);
        }
    }

    static String clock(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private void localNotice(boolean error, String key) {
        noticeText = I18n.format(key);
        noticeError = error;
        noticeUntil = System.currentTimeMillis() + NOTICE_MILLIS;
    }

    private void send(Action action, int intArg, String strArg) {
        ItemStack st = stack();
        if (st == null) return;
        FmNetwork.sendToServer(new C2SIPodAction(slot, state(st).id, action, intArg, strArg));
    }
}
