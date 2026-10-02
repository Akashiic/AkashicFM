package com.akashiic.fm.common;

/**
 * Decide quando mostrar o aviso "tocando agora" para quem ouve. A cada tick recebe a reprodução mais alta que o
 * jogador escuta de verdade (já tocando) e devolve o texto a mostrar, ou null.
 * <ul>
 * <li>mostra ao começar a ouvir uma estação e quando o título dela muda;</li>
 * <li>sem título, espera até {@link #TITLE_GRACE_MS} pelo título antes de mostrar o nome da estação (o título
 * costuma chegar logo; assim não saem dois avisos seguidos);</li>
 * <li>o mesmo texto da mesma estação não repete; quem fica {@link #FORGET_MS} sem ouvir é esquecido (voltar a
 * ouvir mostra de novo);</li>
 * <li>no máximo um aviso a cada {@link #MIN_INTERVAL_MS}: um pedido nesse intervalo fica pendente e sai depois,
 * se ainda for o que toca.</li>
 * </ul>
 * Lógica pura (relógio injetado), testada em NowPlayingTrackerTest.
 */
public final class NowPlayingTracker {

    /** Abaixo disto a rádio é inaudível (≈ -34 dB): não conta como ouvir. */
    public static final float AUDIBLE_GAIN = 0.02f;
    public static final long MIN_INTERVAL_MS = 3000;
    public static final long FORGET_MS = 10_000;
    public static final long TITLE_GRACE_MS = 4000;

    private String key;
    private long firstHeardMs;
    private String shownText;
    private String pending;
    private long lastHeardMs;
    private boolean everShown;
    private long lastShownMs;

    /**
     * @param listenKey chave da reprodução mais alta ouvida agora (já tocando), ou null
     * @param title     título atual, ou vazio
     * @param fallback  o que mostrar sem título (o nome da estação)
     * @param gain      ganho dessa reprodução para o jogador
     */
    public String update(String listenKey, String title, String fallback, float gain, long nowMs) {
        boolean hearing = listenKey != null && gain >= AUDIBLE_GAIN;
        if (!hearing) {
            if (key != null && nowMs - lastHeardMs > FORGET_MS) {
                key = null;
                shownText = null;
                pending = null;
            }
            return null;
        }
        lastHeardMs = nowMs;
        if (!listenKey.equals(key)) {
            key = listenKey;
            firstHeardMs = nowMs;
            shownText = null;
            pending = null;
        }
        String text = title != null && !title.isEmpty() ? title
            : nowMs - firstHeardMs >= TITLE_GRACE_MS ? fallback : null;
        if (text != null && !text.isEmpty() && !text.equals(shownText) && !text.equals(pending)) pending = text;
        if (pending == null) return null;
        if (everShown && nowMs - lastShownMs < MIN_INTERVAL_MS) return null;
        String out = pending;
        pending = null;
        shownText = out;
        lastShownMs = nowMs;
        everShown = true;
        return out;
    }

    /** Esquece tudo (desconexão, troca de mundo). */
    public void reset() {
        key = null;
        shownText = null;
        pending = null;
    }
}
