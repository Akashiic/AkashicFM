package com.akashiic.fm.server;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;

import com.akashiic.fm.audio.http.UrlPolicy;
import com.akashiic.fm.common.FmConfig;
import com.akashiic.fm.common.Frequency;
import com.akashiic.fm.common.RadioAccess;
import com.akashiic.fm.common.RadioLimits;
import com.akashiic.fm.common.RadioState;
import com.akashiic.fm.common.RateLimiter;
import com.akashiic.fm.common.RedstoneMode;
import com.akashiic.fm.common.TextSanitizer;
import com.akashiic.fm.common.TransmitterState;
import com.akashiic.fm.common.TuneMode;
import com.akashiic.fm.content.TileRadio;
import com.akashiic.fm.content.TileTransmitter;

/**
 * O que um computador (OpenComputers) pode fazer com uma rádio ou um transmissor, sem depender do OC: o adaptador
 * em {@code compat.oc} só converte argumentos e chama daqui. Thread principal (as chamadas do OC não são diretas).
 * <p>
 * Os métodos da rádio seguem o componente {@code openfm_radio} do OpenFM 1.7.10, para os scripts antigos
 * funcionarem: mesmos nomes, mesma escala de volume (lê de 0 a 1, escreve de 0 a 10) e o mesmo jeito de responder
 * (valores, ou {@code false} e o motivo). A diferença é a segurança, que o OpenFM não tinha:
 * <ul>
 * <li>as permissões de um jogador qualquer: controla bloco público ou sem dono, e só mexe na tela, na redstone e no
 * nome da estação de bloco sem dono (privado só com {@code opencomputers.allowPrivate}): um Adaptador encostado de
 * fora da casa alcançaria a rádio;</li>
 * <li>dono bloqueado por um admin, recusado;</li>
 * <li>a URL passa pela mesma política das rádios;</li>
 * <li>no máximo {@link #CHANGES_PER_SECOND} mudanças por segundo por bloco;</li>
 * <li>trocas de URL e frequência vão para o log de auditoria, com o endereço do computador.</li>
 * </ul>
 */
public final class RadioScripting {

    static final int CHANGES_PER_SECOND = 4;
    /** O {@code greet} do OpenFM (scripts antigos podem conferir). */
    static final String GREETING = "Lasciate ogne speranza, voi ch'intrate";

    /** Destino da auditoria (trocável nos testes, para não escrever arquivo). */
    interface Audit {

        void log(String actor, String action, String details);
    }

    static Audit audit = (actor, action, details) -> AuditLog.log(actor, null, action, details);
    /** Dono bloqueado por um admin (trocável nos testes, que não têm servidor). */
    static Predicate<UUID> blocked = Moderation::isBlocked;
    private static final RateLimiter LIMITER = new RateLimiter();

    private RadioScripting() {}

    // ---- Comum ----

    /**
     * null se o computador pode mexer no bloco; senão o motivo (em inglês, como as mensagens do OC). As mesmas
     * permissões de um jogador qualquer: controlar (tocar, URL, volume, frequência) num bloco público ou sem dono;
     * ações do dono (tela, redstone, nome da estação) só num bloco sem dono. {@code allowPrivate} libera tudo.
     */
    static String refusal(UUID owner, RadioAccess access, boolean ownerAction) {
        if (owner != null && blocked.test(owner)) return "the owner is blocked on this server";
        if (owner == null || FmConfig.OpenComputers.allowPrivate) return null;
        if (ownerAction) return "only the owner can change this (screen, redstone, station name)";
        return access == RadioAccess.PUBLIC ? null : "private block: computers only control public or ownerless radios";
    }

    /** Ficha do limite por bloco (chave derivada da posição). */
    private static boolean acquire(int dim, Object pos) {
        UUID key = UUID.nameUUIDFromBytes(("akashicfm-oc:" + dim + "@" + pos).getBytes(StandardCharsets.UTF_8));
        return LIMITER.tryAcquire(key, CHANGES_PER_SECOND);
    }

    /** null se a mudança de controle pode seguir; senão a resposta de recusa. */
    private static Object[] guard(int dim, Object pos, UUID owner, RadioAccess access) {
        return guard(dim, pos, owner, access, false);
    }

    /** Idem, para uma ação do dono ({@code ownerAction}). */
    private static Object[] guard(int dim, Object pos, UUID owner, RadioAccess access, boolean ownerAction) {
        String why = refusal(owner, access, ownerAction);
        if (why != null) return fail(why);
        if (!acquire(dim, pos)) return fail("too many changes per second");
        return null;
    }

    static Object[] ok(Object... values) {
        return values;
    }

    static Object[] fail(String reason) {
        return new Object[] { false, reason };
    }

    /** URL limpa e aprovada pela política, ou a recusa em {@code out[0]}. */
    private static String checkedUrl(Object raw, Object[][] refusal) {
        String text = raw instanceof byte[] ? new String((byte[]) raw, StandardCharsets.UTF_8)
            : raw == null ? "" : String.valueOf(raw);
        String url = TextSanitizer.cleanUrl(text, RadioLimits.MAX_URL_LENGTH);
        if (url.isEmpty()) {
            refusal[0] = fail("Error parsing URL in packet");
            return null;
        }
        UrlPolicy.PolicyException e = ServerPolicy.rejection(url);
        if (e != null) {
            refusal[0] = fail("URL refused: " + e.getMessage());
            return null;
        }
        return url;
    }

    /** MHz (98.7) → décimos, dentro da faixa; -1 se não é um número. */
    static int tenths(double mhz) {
        if (Double.isNaN(mhz) || Double.isInfinite(mhz)) return -1;
        return Frequency.clamp((int) Math.round(mhz * 10));
    }

    // ---- Rádio (componente openfm_radio) ----

    public static Object[] start(TileRadio r) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access);
        if (no != null) return no;
        switch (RadioActionHandler.applyPlay(r.state)) {
            case NO_URL:
                return fail("no URL set");
            case REJECTED:
                return fail("URL refused by the server policy");
            case NO_TRANSPORT:
                return fail("the server has no way to play this URL now");
            case CHANGED:
                RadioActionHandler.afterPlay(r);
                r.markStateChanged();
                return ok(true);
            default:
                return ok(true);
        }
    }

    public static Object[] stop(TileRadio r) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access);
        if (no != null) return no;
        if (RadioActionHandler.applyStop(r.state)) r.markStateChanged();
        return ok(true);
    }

    public static Object[] isPlaying(TileRadio r) {
        return ok(r.state.playing);
    }

    public static Object[] setURL(TileRadio r, Object raw, String actor) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access);
        if (no != null) return no;
        Object[][] refusal = new Object[1][];
        String url = checkedUrl(raw, refusal);
        if (url == null) return refusal[0];
        if (RadioActionHandler.applyUrl(r.state, url)) {
            r.markStateChanged();
            audit.log(actor, "radio.url", "dim " + r.dimension() + " " + r.pos() + " -> " + url);
        }
        return ok(true);
    }

    public static Object[] getURL(TileRadio r) {
        return ok(r.state.url);
    }

    /** Volume de 0 a 1, como o OpenFM. */
    public static Object[] getVol(TileRadio r) {
        return ok(r.state.volume / 100.0);
    }

    /** Volume de 0 a 10, como o OpenFM (que recusava o 10 por um erro de arredondamento; aqui vale). */
    public static Object[] setVol(TileRadio r, double tenScale) {
        if (Double.isNaN(tenScale) || tenScale < 0 || tenScale > 10) return ok(false);
        return applyVolume(r, (int) Math.round(tenScale * 10));
    }

    public static Object[] volUp(TileRadio r) {
        return r.state.volume + 10 > RadioLimits.VOLUME_MAX ? ok(false) : applyVolume(r, r.state.volume + 10);
    }

    public static Object[] volDown(TileRadio r) {
        return r.state.volume - 10 < RadioLimits.VOLUME_MIN ? ok(false) : applyVolume(r, r.state.volume - 10);
    }

    private static Object[] applyVolume(TileRadio r, int volume) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access);
        if (no != null) return no;
        int v = RadioLimits.clamp(volume, RadioLimits.VOLUME_MIN, RadioLimits.VOLUME_MAX);
        if (v != r.state.volume) {
            r.state.volume = v;
            r.markStateChanged();
        }
        return getVol(r);
    }

    public static Object[] setScreenColor(TileRadio r, int color) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access, true);
        if (no != null) return no;
        int c = color & 0xFFFFFF;
        if (c != r.state.screenColor) {
            r.state.screenColor = c;
            r.markStateChanged();
        }
        return ok(true);
    }

    public static Object[] getScreenColor(TileRadio r) {
        return ok(r.state.screenColor);
    }

    public static Object[] setScreenText(TileRadio r, String text) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access, true);
        if (no != null) return no;
        String t = TextSanitizer.clean(text == null ? "" : text, RadioLimits.MAX_SCREEN_TEXT);
        if (!t.equals(r.state.screenText)) {
            r.state.screenText = t;
            r.markStateChanged();
        }
        return ok(true);
    }

    public static Object[] getScreenText(TileRadio r) {
        return ok(r.state.screenText);
    }

    /** O OpenFM tinha {@code getAttachedSpeakerCount} e {@code getAttachedSpeakers}, os dois com a contagem. */
    public static Object[] speakerCount(TileRadio r) {
        return ok(r.state.speakers.size());
    }

    /** Ouvir a redstone do OpenFM = tocar enquanto ligada. */
    public static Object[] setListenRedstone(TileRadio r, boolean listen) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access, true);
        if (no != null) return no;
        RedstoneMode mode = listen ? RedstoneMode.WHILE_POWERED : RedstoneMode.IGNORE;
        if (mode != r.state.redstoneMode) {
            r.state.redstoneMode = mode;
            if (r.getWorldObj() != null) {
                r.state.lastPowered = r.getWorldObj()
                    .isBlockIndirectlyGettingPowered(r.xCoord, r.yCoord, r.zCoord);
            }
            r.markStateChanged();
        }
        return getListenRedstone(r);
    }

    public static Object[] getListenRedstone(TileRadio r) {
        return ok(r.state.redstoneMode != RedstoneMode.IGNORE);
    }

    public static Object[] greet() {
        return ok(GREETING);
    }

    /** {@code "url"} ou {@code "fm"}. */
    public static Object[] setMode(TileRadio r, String mode) {
        TuneMode m = "fm".equalsIgnoreCase(mode) ? TuneMode.FREQUENCY
            : "url".equalsIgnoreCase(mode) ? TuneMode.URL : null;
        if (m == null) return fail("mode must be \"url\" or \"fm\"");
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access);
        if (no != null) return no;
        if (RadioActionHandler.setMode(r, m)) r.markStateChanged();
        return getMode(r);
    }

    public static Object[] getMode(TileRadio r) {
        return ok(r.state.mode == TuneMode.FREQUENCY ? "fm" : "url");
    }

    public static Object[] setFrequency(TileRadio r, double mhz) {
        int f = tenths(mhz);
        if (f < 0) return fail("frequency must be a number, like 98.7");
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access);
        if (no != null) return no;
        if (f != r.state.frequency) {
            r.state.frequency = f;
            if (r.state.mode == TuneMode.FREQUENCY && r.state.playing) FrequencyService.retune(r);
            r.markStateChanged();
        }
        return getFrequency(r);
    }

    public static Object[] getFrequency(TileRadio r) {
        return ok(r.state.frequency / 10.0);
    }

    /** Título ICY que está tocando (vazio se não há). */
    public static Object[] getNowPlaying(TileRadio r) {
        return ok(r.state.nowPlaying);
    }

    /** No modo FM: sinal de 0 a 100 e o nome da estação sintonizada. */
    public static Object[] getSignal(TileRadio r) {
        RadioState s = r.state;
        return s.mode == TuneMode.FREQUENCY ? ok(s.signal, s.tunedName) : ok(0, "");
    }

    public static Object[] setPlaylist(TileRadio r, boolean on) {
        Object[] no = guard(r.dimension(), r.pos(), r.state.owner, r.state.access);
        if (no != null) return no;
        if (on != r.state.playlist) {
            r.state.playlist = on;
            r.markStateChanged();
        }
        return ok(r.state.playlist);
    }

    public static Object[] getPlaylist(TileRadio r) {
        return ok(r.state.playlist);
    }

    // ---- Transmissor (componente akashicfm_transmitter) ----

    public static Object[] start(TileTransmitter t) {
        TransmitterState s = t.state;
        Object[] no = guard(t.dimension(), t.pos(), s.owner, s.access);
        if (no != null) return no;
        if (s.url.isEmpty()) return fail("no URL set");
        boolean was = s.broadcasting;
        if (!TransmitterActionHandler.applyBroadcast(s)) return fail("URL refused by the server policy");
        if (!was) t.markStateChanged();
        if (TileTransmitter.energyRequired() && !t.hasEnergyReserve()) return ok(true, "waiting for energy");
        return ok(true);
    }

    public static Object[] stop(TileTransmitter t) {
        TransmitterState s = t.state;
        Object[] no = guard(t.dimension(), t.pos(), s.owner, s.access);
        if (no != null) return no;
        if (s.broadcasting) {
            s.broadcasting = false;
            t.markStateChanged();
        }
        return ok(true);
    }

    /** Ligado e, de fato, no ar (com URL e energia). */
    public static Object[] isBroadcasting(TileTransmitter t) {
        return ok(t.state.broadcasting, t.state.active());
    }

    public static Object[] setURL(TileTransmitter t, Object raw, String actor) {
        TransmitterState s = t.state;
        Object[] no = guard(t.dimension(), t.pos(), s.owner, s.access);
        if (no != null) return no;
        Object[][] refusal = new Object[1][];
        String url = checkedUrl(raw, refusal);
        if (url == null) return refusal[0];
        if (!url.equals(s.url)) {
            s.url = url;
            t.markStateChanged();
            audit.log(actor, "transmitter.url", "dim " + t.dimension() + " " + t.pos() + " -> " + url);
        }
        return ok(true);
    }

    public static Object[] getURL(TileTransmitter t) {
        return ok(t.state.url);
    }

    public static Object[] setFrequency(TileTransmitter t, double mhz, String actor) {
        int f = tenths(mhz);
        if (f < 0) return fail("frequency must be a number, like 98.7");
        TransmitterState s = t.state;
        Object[] no = guard(t.dimension(), t.pos(), s.owner, s.access);
        if (no != null) return no;
        if (f != s.frequency) {
            s.frequency = f;
            t.markStateChanged();
            audit.log(
                actor,
                "transmitter.frequency",
                "dim " + t.dimension() + " " + t.pos() + " -> " + Frequency.format(f));
        }
        return getFrequency(t);
    }

    public static Object[] getFrequency(TileTransmitter t) {
        return ok(t.state.frequency / 10.0);
    }

    public static Object[] setName(TileTransmitter t, String name) {
        TransmitterState s = t.state;
        Object[] no = guard(t.dimension(), t.pos(), s.owner, s.access, true);
        if (no != null) return no;
        String n = TextSanitizer.clean(name == null ? "" : name, RadioLimits.MAX_SCREEN_TEXT);
        if (!n.equals(s.name)) {
            s.name = n;
            t.markStateChanged();
        }
        return ok(true);
    }

    public static Object[] getName(TileTransmitter t) {
        return ok(t.state.name);
    }

    /** Alcance em blocos e antenas que contam. */
    public static Object[] getRange(TileTransmitter t) {
        return ok(t.state.range, t.state.antennas);
    }

    /** EU guardados e capacidade; {@code false} se este servidor não exige energia. */
    public static Object[] getEnergy(TileTransmitter t) {
        TransmitterState s = t.state;
        return s.energyRequired ? ok(s.energy, s.energyCapacity) : ok(false);
    }

    /** Servidor parando. */
    public static void clear() {
        LIMITER.clear();
    }
}
