package com.akashiic.fm.audio.dsp;

/**
 * Ganho de cada fonte de uma rádio. Funções puras, testadas em GainModelTest.
 * <p>
 * A atenuação por distância é feita aqui e não pelo OpenAL (as fontes usam rolloff 0): assim o alcance
 * escolhido na rádio é exato, o modelo de distância global do paulscode não é alterado e o OpenAL fica só
 * com o posicionamento (pan/HRTF).
 */
public final class GainModel {

    /** Até esta distância (blocos) o volume é cheio. */
    public static final double NEAR_DISTANCE = 2.0;

    private GainModel() {}

    /**
     * 1 até {@link #NEAR_DISTANCE}, cai como (1-x)² e chega a 0 exatamente no alcance. Sem salto: a
     * derivada também é 0 no alcance, então sair da área não dá corte audível.
     */
    public static double distanceAttenuation(double distance, double range) {
        if (!(distance >= 0) || !(range > 0)) return 0;
        if (range <= NEAR_DISTANCE) return distance <= range ? 1 : 0;
        if (distance <= NEAR_DISTANCE) return 1;
        if (distance >= range) return 0;
        double x = (distance - NEAR_DISTANCE) / (range - NEAR_DISTANCE);
        double a = 1 - x;
        return a * a;
    }

    /** Volume da rádio (0..100) em ganho, com curva quadrática para o slider soar uniforme ao ouvido. */
    public static double volumeCurve(int volume) {
        double v = Math.max(0, Math.min(100, volume)) / 100.0;
        return v * v;
    }

    /**
     * Ganho final de uma fonte. O volume Master do Minecraft não entra: o paulscode já o aplica como ganho
     * do listener do OpenAL, que vale para todas as fontes (multiplicar de novo daria Master²).
     */
    public static float sourceGain(float recordsLevel, int clientVolume, int radioVolume, double distance, double range,
        double occlusionGain) {
        double g = clamp01(recordsLevel) * (Math.max(0, Math.min(100, clientVolume)) / 100.0)
            * volumeCurve(radioVolume)
            * distanceAttenuation(distance, range)
            * clamp01(occlusionGain);
        return (float) clamp01(g);
    }

    /**
     * Aproxima {@code current} de {@code target} com constante de tempo {@code tauSeconds} (suaviza mudanças
     * de ganho entre frames, sem "zipper noise").
     */
    public static float smooth(float current, float target, double dtSeconds, double tauSeconds) {
        if (tauSeconds <= 0 || dtSeconds <= 0) return target;
        double k = 1 - Math.exp(-dtSeconds / tauSeconds);
        return (float) (current + (target - current) * k);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
