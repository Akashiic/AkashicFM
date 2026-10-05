package com.akashiic.fm.client.gui;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.akashiic.fm.network.S2CIPodSearchResults;
import com.akashiic.fm.network.S2CIPodStatus;
import com.akashiic.fm.network.S2CPortableSources;
import com.akashiic.fm.network.S2CRadioNotice;
import com.akashiic.fm.network.S2CRadioPerms;
import com.akashiic.fm.server.ipod.IPodActionHandler;

/**
 * Tela do iPod do slot. Como as outras, não guarda estado do iPod: mostra o item (fila, faixa atual e pausa, do NBT
 * que o inventário sincroniza), o status que o servidor manda ao dono ({@link ClientIPod}: posição, motivo) e manda
 * pedidos. Fecha sozinha se o slot deixar de ter o iPod.
 * <p>
 * Abas: a fila e uma por serviço (SoundCloud, YouTube, Spotify). Numa aba de serviço o campo busca pelo nome e a
 * lista mostra os resultados: um clique põe na fila (e começa, se o iPod estava parado), "Tocar agora" toca o
 * escolhido logo depois da atual. Um link colado em qualquer aba entra na fila direto. Na fila: clique seleciona,
 * clique duplo toca; a roda do mouse rola a lista.
 */
public final class GuiIPod extends GuiScreen implements FmScreen {

    /** As abas, na ordem da tela. */
    public enum Tab {

        QUEUE(null),
        SOUNDCLOUD(IPodTrack.Source.SOUNDCLOUD),
        YOUTUBE(IPodTrack.Source.YOUTUBE),
        SPOTIFY(IPodTrack.Source.SPOTIFY);

        /** O serviço buscado (null na fila). */
        final IPodTrack.Source source;

        Tab(IPodTrack.Source source) {
            this.source = source;
        }
    }

    private static final int W = 256;
    private static final int H = 240;
    private static final long NOTICE_MILLIS = 8000;
    private static final int ROWS = 6, ROW_H = 12, LIST_Y = 126;
    private static final long DOUBLE_CLICK_MS = 350;
    /** Sem resposta da busca por este tempo: mostra que não veio. */
    private static final long SEARCH_TIMEOUT_MS = 60_000;
    private static final int TAB_Y = 28, TAB_H = 14;
    private static final int ACTIVE = 0xFF7CFF8A;

    private static final int ID_ADD = 1, ID_PREV = 2, ID_TOGGLE = 3, ID_STOP = 4, ID_NEXT = 5, ID_SHUFFLE = 6,
        ID_REPEAT = 7, ID_REMOVE = 8, ID_CLEAR = 9, ID_VOLUME = 10, ID_UP = 11, ID_DOWN = 12, ID_ADD_RESULT = 13,
        ID_PLAY_RESULT = 14, ID_TAB = 100;

    /** A aba da última vez que a tela foi aberta (na mesma sessão). */
    private static Tab lastTab = Tab.QUEUE;

    private final int slot;
    private int left, top;
    private Tab tab = lastTab;
    private GuiTextField input;
    private FlatButton add, prev, toggle, stop, next, shuffle, repeat, remove, clear, up, down, addResult, playResult;
    private final Map<Tab, FlatButton> tabButtons = new EnumMap<>(Tab.class);
    private FlatSlider volume;
    private final Map<Tab, Integer> scroll = new EnumMap<>(Tab.class);
    private final Map<Tab, Integer> selected = new EnumMap<>(Tab.class);
    private int lastClickRow = -1;
    private long lastClickMs;
    private NBTTagCompound cachedTag;
    private IPodState cached;

    /** Por aba de serviço: o pedido em andamento, quando foi feito, os resultados e os já postos na fila. */
    private final Map<Tab, Integer> request = new EnumMap<>(Tab.class);
    private final Map<Tab, Long> requestedAt = new EnumMap<>(Tab.class);
    private final Map<Tab, S2CIPodSearchResults> results = new EnumMap<>(Tab.class);
    private final Map<Tab, Set<Integer>> added = new EnumMap<>(Tab.class);

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

    // ---- Montagem ----

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ItemStack st = stack();
        if (st == null) {
            mc.displayGuiScreen(null);
            return;
        }
        IPodState s = state(st);
        boolean first = input == null;
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        tabButtons.clear();
        layoutTabs();
        String typed = input != null ? input.getText() : "";
        input = new GuiTextField(fontRendererObj, left + 8, top + 48, 180, 14);
        input.setMaxStringLength(RadioLimits.MAX_URL_LENGTH);
        input.setText(typed);
        add = add(new FlatButton(ID_ADD, left + 192, top + 47, 56, 16, ""));
        int y = top + 95;
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
        remove = add(new FlatButton(ID_REMOVE, left + 8, by, 70, 16, I18n.format("akashicfm.gui.ipod.remove")));
        clear = add(new FlatButton(ID_CLEAR, left + 81, by, 80, 16, I18n.format("akashicfm.gui.ipod.clear")));
        addResult = add(
            new FlatButton(ID_ADD_RESULT, left + 8, by, 70, 16, I18n.format("akashicfm.gui.ipod.add_result")));
        playResult = add(
            new FlatButton(ID_PLAY_RESULT, left + 81, by, 80, 16, I18n.format("akashicfm.gui.ipod.play_now")));
        volume = add(
            new FlatSlider(
                ID_VOLUME,
                left + 164,
                by,
                84,
                16,
                "akashicfm.gui.volume",
                RadioLimits.VOLUME_MIN,
                RadioLimits.VOLUME_MAX,
                s.volume,
                v -> send(Action.VOLUME, v, "")));
        if (first) send(Action.HELLO, 0, ""); // a busca do Spotify depende do servidor
        refreshWidgets(s);
    }

    /** As abas lado a lado, com a folga repartida para caber na largura (os nomes variam com o idioma). */
    private void layoutTabs() {
        Tab[] tabs = Tab.values();
        int gap = 2, avail = W - 16 - gap * (tabs.length - 1), text = 0;
        for (Tab t : tabs) text += fontRendererObj.getStringWidth(tabLabel(t));
        int pad = Math.max(4, Math.min(12, (avail - text) / tabs.length));
        int x = left + 8;
        for (Tab t : tabs) {
            int w = fontRendererObj.getStringWidth(tabLabel(t)) + pad;
            FlatButton b = add(new FlatButton(ID_TAB + t.ordinal(), x, top + TAB_Y, w, TAB_H, tabLabel(t)));
            tabButtons.put(t, b);
            x += w + gap;
        }
    }

    private static String tabLabel(Tab t) {
        return I18n.format("akashicfm.gui.ipod.tab." + t.name());
    }

    private <T extends GuiButton> T add(T button) {
        buttonList.add(button);
        return button;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        lastTab = tab;
    }

    @Override
    public void updateScreen() {
        ItemStack st = stack();
        if (st == null || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        input.updateCursorCounter();
        for (Tab t : Tab.values()) {
            Integer req = request.get(t);
            if (req == null) continue;
            S2CIPodSearchResults r = ClientIPod.results(req);
            if (r != null && results.get(t) != r) {
                results.put(t, r);
                added.put(t, new HashSet<>());
                selected.remove(t);
                scroll.put(t, 0);
            }
        }
        refreshWidgets(state(st));
    }

    private void refreshWidgets(IPodState s) {
        boolean has = !s.queue.isEmpty();
        boolean queueTab = tab == Tab.QUEUE;
        for (Map.Entry<Tab, FlatButton> e : tabButtons.entrySet())
            e.getValue().textColor = e.getKey() == tab ? ACTIVE : 0;
        add.displayString = I18n.format(
            queueTab || (tab == Tab.SPOTIFY && !spotifySearch()) ? "akashicfm.gui.ipod.add"
                : "akashicfm.gui.ipod.search");
        toggle.displayString = I18n.format(s.on && !s.paused ? "akashicfm.gui.ipod.pause" : "akashicfm.gui.play");
        toggle.enabled = has;
        stop.enabled = s.on;
        prev.enabled = next.enabled = has;
        shuffle.enabled = s.queue.size() - Math.max(0, s.index + 1) >= 2;
        int rows = rowCount(s);
        int sel = selected(tab);
        if (sel >= rows) selected.remove(tab);
        remove.visible = clear.visible = queueTab;
        addResult.visible = playResult.visible = !queueTab;
        remove.enabled = queueTab && selected(tab) >= 0;
        clear.enabled = has;
        boolean picked = !queueTab && selected(tab) >= 0;
        addResult.enabled = picked && !addedSet(tab).contains(selected(tab));
        playResult.enabled = picked;
        int sc = Math.max(0, Math.min(scroll(tab), Math.max(0, rows - ROWS)));
        scroll.put(tab, sc);
        up.enabled = sc > 0;
        down.enabled = sc < rows - ROWS;
        volume.setValue(s.volume);
    }

    private int selected(Tab t) {
        Integer v = selected.get(t);
        return v == null ? -1 : v;
    }

    private int scroll(Tab t) {
        Integer v = scroll.get(t);
        return v == null ? 0 : v;
    }

    private Set<Integer> addedSet(Tab t) {
        Set<Integer> s = added.get(t);
        return s == null ? Collections.emptySet() : s;
    }

    /** Linhas da lista da aba atual (fila ou resultados). */
    private int rowCount(IPodState s) {
        if (tab == Tab.QUEUE) return s.queue.size();
        S2CIPodSearchResults r = results.get(tab);
        return r == null ? 0 : r.results.size();
    }

    private boolean spotifySearch() {
        return (ClientIPod.flags() & S2CIPodSearchResults.FLAG_SPOTIFY_SEARCH) != 0;
    }

    // ---- Ações ----

    @Override
    protected void actionPerformed(GuiButton button) {
        ItemStack st = stack();
        if (st == null) return;
        IPodState s = state(st);
        if (button.id >= ID_TAB && button.id < ID_TAB + Tab.values().length) {
            selectTab(Tab.values()[button.id - ID_TAB]);
            return;
        }
        switch (button.id) {
            case ID_ADD:
                submitText(input.getText());
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
            case ID_REMOVE: {
                int sel = selected(Tab.QUEUE);
                if (sel >= 0 && sel < s.queue.size()) send(Action.REMOVE, sel, "");
                selected.remove(Tab.QUEUE);
                return;
            }
            case ID_CLEAR:
                send(Action.CLEAR, 0, "");
                selected.remove(Tab.QUEUE);
                return;
            case ID_ADD_RESULT:
                pickResult(selected(tab), false);
                return;
            case ID_PLAY_RESULT:
                pickResult(selected(tab), true);
                return;
            case ID_UP:
                scroll.put(tab, Math.max(0, scroll(tab) - 1));
                return;
            case ID_DOWN:
                scroll.put(tab, Math.min(Math.max(0, rowCount(s) - ROWS), scroll(tab) + 1));
                return;
            default:
                return;
        }
    }

    /** Troca de aba (público para o teste em jogo). */
    public void selectTab(Tab t) {
        if (t == null || t == tab) return;
        tab = t;
        lastClickRow = -1;
        ItemStack st = stack();
        if (st != null) refreshWidgets(state(st));
    }

    public Tab tab() {
        return tab;
    }

    /**
     * O texto do campo: na fila (ou no Spotify sem a busca) entra na fila como link ou busca; numa aba de serviço
     * busca pelo nome, e um link colado entra na fila direto. Público para o teste em jogo.
     */
    public void submitText(String raw) {
        String typed = TextSanitizer.clean(raw, RadioLimits.MAX_URL_LENGTH);
        if (typed.isEmpty()) {
            localNotice(
                true,
                tab == Tab.QUEUE ? "akashicfm.ipod.notice.type_link" : "akashicfm.ipod.notice.type_search");
            return;
        }
        boolean link = typed.contains("://") || typed.startsWith("spotify:");
        if (tab == Tab.QUEUE || link || (tab == Tab.SPOTIFY && !spotifySearch())) {
            send(Action.ADD, 0, typed);
        } else {
            int req = ClientIPod.newRequest();
            request.put(tab, req);
            requestedAt.put(tab, System.currentTimeMillis());
            results.remove(tab);
            added.remove(tab);
            selected.remove(tab);
            send(Action.SEARCH, C2SIPodAction.Action.pack(req, tab.source.ordinal()), typed);
        }
        input.setText("");
    }

    /** Põe o resultado {@code index} da aba na fila (ou toca agora). */
    private void pickResult(int index, boolean now) {
        S2CIPodSearchResults r = results.get(tab);
        if (r == null || index < 0 || index >= r.results.size()) return;
        send(now ? Action.PLAY_RESULT : Action.ADD_RESULT, C2SIPodAction.Action.pack(r.requestId, index), "");
        Set<Integer> set = added.get(tab);
        if (set == null) added.put(tab, set = new HashSet<>());
        set.add(index);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean enter = keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER;
        if (input.isFocused()) {
            if (enter) {
                submitText(input.getText());
                return;
            }
            if (input.textboxKeyTyped(typedChar, keyCode)) return;
        }
        boolean typing = input.isFocused();
        if (!typing && keyCode == Keyboard.KEY_SPACE) {
            send(Action.TOGGLE, 0, "");
            return;
        }
        if (!typing && keyCode == Keyboard.KEY_DELETE && tab == Tab.QUEUE && selected(Tab.QUEUE) >= 0) {
            send(Action.REMOVE, selected(Tab.QUEUE), "");
            selected.remove(Tab.QUEUE);
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
        int rows = rowCount(state(st));
        scroll.put(tab, Math.max(0, Math.min(Math.max(0, rows - ROWS), scroll(tab) + (wheel > 0 ? -1 : 1))));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        input.mouseClicked(mouseX, mouseY, mouseButton);
        ItemStack st = stack();
        if (st == null || mouseButton != 0) return;
        int lx = left + 8, ly = top + LIST_Y;
        if (mouseX < lx || mouseX >= left + 232 || mouseY < ly || mouseY >= ly + ROWS * ROW_H) return;
        int row = scroll(tab) + (mouseY - ly) / ROW_H;
        IPodState s = state(st);
        if (row >= rowCount(s)) return;
        if (tab != Tab.QUEUE) {
            // Um clique põe na fila (uma vez); o selecionado também pode tocar agora.
            if (!addedSet(tab).contains(row)) pickResult(row, false);
            selected.put(tab, row);
            return;
        }
        long now = System.currentTimeMillis();
        if (row == lastClickRow && now - lastClickMs <= DOUBLE_CLICK_MS) {
            send(Action.PLAY, row, "");
            lastClickRow = -1;
        } else {
            lastClickRow = row;
            lastClickMs = now;
        }
        selected.put(tab, row);
    }

    // ---- Desenho ----

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
                fontRendererObj.trimStringToWidth(I18n.format(hintKey()), 172),
                left + 12,
                top + 51,
                0xFF606870);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        FlatButton active = tabButtons.get(tab);
        if (active != null) drawRect(
            active.xPosition + 1,
            active.yPosition + TAB_H,
            active.xPosition + active.width - 1,
            active.yPosition + TAB_H + 2,
            ACTIVE);

        drawNowPlaying(s, status, now);
        if (tab == Tab.QUEUE) drawQueue(s);
        else drawResults(now);

        if (!noticeText.isEmpty() && now < noticeUntil) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(noticeText, W - 18),
                left + 9,
                top + H - 14,
                noticeError ? 0xFFFF6060 : 0xFF70FF80);
        }
    }

    private String hintKey() {
        if (tab == Tab.QUEUE) return "akashicfm.gui.ipod.hint";
        if (tab == Tab.SPOTIFY && !spotifySearch()) return "akashicfm.gui.ipod.hint_spotify_link";
        return "akashicfm.gui.ipod.hint_search";
    }

    /** Faixa atual, motivo e progresso. */
    private void drawNowPlaying(IPodState s, S2CIPodStatus status, long now) {
        int lx = left + 9, width = W - 18;
        IPodTrack cur = s.current();
        String title = cur == null ? I18n.format("akashicfm.gui.ipod.empty") : "♪ " + cur.display();
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(title, width), lx, top + 66, 0xFFE0E6EE);
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
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(line, width - tw - 6), lx, top + 77, color);
        fontRendererObj.drawString(time, left + W - 9 - tw, top + 77, 0xFFB8C8FF);
        drawRect(lx, top + 88, lx + width, top + 91, 0xFF2A3038);
        if (s.on && dur > 0) {
            int fill = (int) (width * Math.min(1.0, pos / (double) dur));
            drawRect(lx, top + 88, lx + fill, top + 91, 0xFF6FB0FF);
        }
    }

    private void drawListBackground() {
        int ly = top + LIST_Y;
        drawRect(left + 8, ly, left + 232, ly + ROWS * ROW_H, 0x60000000);
    }

    private void drawQueue(IPodState s) {
        drawListBackground();
        int ly = top + LIST_Y, sc = scroll(Tab.QUEUE), sel = selected(Tab.QUEUE);
        for (int r = 0; r < ROWS; r++) {
            int i = sc + r;
            if (i >= s.queue.size()) break;
            IPodTrack t = s.queue.get(i);
            int ry = ly + r * ROW_H;
            if (i == sel) drawRect(left + 8, ry, left + 232, ry + ROW_H, 0x603A7BD5);
            int c = i == s.index ? 0xFF70FF80 : 0xFFD0D6E0;
            String text = (i + 1) + ". " + mark(t.source) + t.display();
            drawRow(text, t.durationSec, ry, c);
        }
        // O modo de repetição fica aqui (no botão não caberia).
        String count = I18n.format("akashicfm.ipod.queue", s.queue.size()) + " · "
            + I18n.format("akashicfm.gui.ipod.repeat." + s.repeat.name());
        drawHeader(count, 0xFF707880);
    }

    private void drawResults(long now) {
        drawListBackground();
        if (tab == Tab.SPOTIFY && !spotifySearch()) {
            boolean links = (ClientIPod.flags() & S2CIPodSearchResults.FLAG_SPOTIFY_LINKS) != 0;
            drawHeader(I18n.format("akashicfm.gui.ipod.spotify_title"), 0xFF707880);
            drawWrapped(I18n.format(links ? "akashicfm.gui.ipod.spotify_nokey" : "akashicfm.ipod.err.spotify_off"));
            return;
        }
        Integer req = request.get(tab);
        S2CIPodSearchResults r = results.get(tab);
        String header;
        int color = 0xFF707880;
        if (req == null) {
            header = I18n.format("akashicfm.gui.ipod.search_prompt", tabLabel(tab));
        } else if (r == null) {
            Long at = requestedAt.get(tab);
            boolean late = at != null && now - at > SEARCH_TIMEOUT_MS;
            header = I18n.format(late ? "akashicfm.gui.ipod.search_late" : "akashicfm.gui.ipod.searching");
            color = late ? 0xFFFF8070 : 0xFFFFD070;
        } else if (!r.status.isEmpty()) {
            header = format(r.status);
            color = 0xFFFF8070;
        } else if (r.results.isEmpty()) {
            header = I18n.format("akashicfm.gui.ipod.no_results");
        } else {
            header = I18n.format("akashicfm.gui.ipod.results", r.results.size(), tabLabel(tab));
        }
        drawHeader(header, color);
        if (r == null) return;
        List<S2CIPodSearchResults.Entry> list = r.results;
        int ly = top + LIST_Y, sc = scroll(tab), sel = selected(tab);
        Set<Integer> done = addedSet(tab);
        for (int row = 0; row < ROWS; row++) {
            int i = sc + row;
            if (i >= list.size()) break;
            S2CIPodSearchResults.Entry e = list.get(i);
            int ry = ly + row * ROW_H;
            if (i == sel) drawRect(left + 8, ry, left + 232, ry + ROW_H, 0x603A7BD5);
            boolean in = done.contains(i);
            drawRow((in ? "✓ " : "") + e.display(), e.durationSec, ry, in ? 0xFF70FF80 : 0xFFD0D6E0);
        }
    }

    private void drawHeader(String text, int color) {
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text, W - 18), left + 9, top + LIST_Y - 11, color);
    }

    private void drawRow(String text, int durationSec, int ry, int color) {
        String d = durationSec > 0 ? clock(durationSec * 1000L) : "";
        int dw = fontRendererObj.getStringWidth(d);
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text, 220 - dw - 8), left + 11, ry + 2, color);
        fontRendererObj.drawString(d, left + 229 - dw, ry + 2, 0xFF8890A0);
    }

    /** Texto de várias linhas dentro da lista. */
    @SuppressWarnings("unchecked")
    private void drawWrapped(String text) {
        List<String> lines = fontRendererObj.listFormattedStringToWidth(text, 216);
        int y = top + LIST_Y + 3;
        for (int i = 0; i < lines.size() && i < ROWS; i++) {
            fontRendererObj.drawString(lines.get(i), left + 12, y, 0xFFC8D0D8);
            y += ROW_H;
        }
    }

    private static String mark(IPodTrack.Source source) {
        return source == IPodTrack.Source.SOUNDCLOUD ? "" : source == IPodTrack.Source.YOUTUBE ? "[YT] " : "[SP] ";
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
