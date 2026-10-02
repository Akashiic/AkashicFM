package com.akashiic.fm.audio.spatial;

/**
 * Parâmetros do reverb a partir de uma sondagem da sala: raios saindo do ouvinte em direções espalhadas, com a
 * distância até a primeira superfície refletora (ou -1 se não bateu em nada até o limite) e o amortecimento
 * dela (pedra e metal refletem quase tudo; lã absorve quase tudo).
 * <ul>
 * <li><b>fechamento</b> (fração dos raios que bateram): campo aberto ~0,5 (o chão devolve, o céu não), sala ou
 * caverna ~1. Só começa a soar como sala acima de 0,5;</li>
 * <li><b>decaimento</b> pela fórmula de Sabine, T60 = 0,161·V/(S·α). Tratando o espaço como uma esfera de raio
 * igual à distância média, V/S = r/3, então T60 ≈ 0,054·r/α: espaço maior ou superfície mais dura, reverb mais
 * longo;</li>
 * <li><b>brilho</b> (razão de decaimento dos agudos): superfícies macias apagam os agudos primeiro;</li>
 * <li><b>primeiras reflexões</b> chegam depois de ida e volta até as paredes (2·r/343 s).</li>
 * </ul>
 * Funções puras, testadas em RoomModelTest.
 */
public final class RoomModel {

    /** Reverb pronto para o OpenAL (faixas do reverb padrão do EFX). */
    public static final class Params {

        /** Nível do reverb (ganho do slot auxiliar), 0..1. */
        public final float wet;
        /** Tempo de decaimento (s), 0,1..20 no EFX. */
        public final float decayTime;
        /** Razão do decaimento dos agudos, 0,1..2. */
        public final float decayHfRatio;
        /** Atraso das primeiras reflexões (s), 0..0,3. */
        public final float reflectionsDelay;
        /** Atraso da cauda em relação às primeiras reflexões (s), 0..0,1. */
        public final float lateReverbDelay;

        Params(float wet, float decayTime, float decayHfRatio, float reflectionsDelay, float lateReverbDelay) {
            this.wet = wet;
            this.decayTime = decayTime;
            this.decayHfRatio = decayHfRatio;
            this.reflectionsDelay = reflectionsDelay;
            this.lateReverbDelay = lateReverbDelay;
        }

        /** Diferença audível o bastante para valer reenviar ao OpenAL. */
        public boolean differsFrom(Params o) {
            return o == null || Math.abs(wet - o.wet) > 0.01f
                || Math.abs(decayTime - o.decayTime) > 0.03f
                || Math.abs(decayHfRatio - o.decayHfRatio) > 0.03f
                || Math.abs(reflectionsDelay - o.reflectionsDelay) > 0.002f
                || Math.abs(lateReverbDelay - o.lateReverbDelay) > 0.002f;
        }

        @Override
        public String toString() {
            return String.format(
                "wet=%.2f decay=%.2fs hf=%.2f refl=%.3fs late=%.3fs",
                wet,
                decayTime,
                decayHfRatio,
                reflectionsDelay,
                lateReverbDelay);
        }
    }

    /** Sem reverb (campo aberto). */
    public static final Params DRY = new Params(0f, 0.3f, 1f, 0.005f, 0.005f);

    static final double SPEED_OF_SOUND = 343.0;
    /** 0,161/3: Sabine com V/S = r/3. */
    static final double SABINE_SPHERE = 0.161 / 3;
    static final double MIN_DECAY = 0.2, MAX_DECAY = 4.0;
    static final double MAX_WET = 0.8;
    /** Amortecimento mínimo considerado (evita decaimento infinito com superfícies "perfeitas"). */
    static final double MIN_DAMPING = 0.05;

    private RoomModel() {}

    /**
     * @param distances distância até a primeira superfície por raio, ou -1
     * @param dampings  amortecimento da superfície atingida por raio (ignorado onde a distância é -1)
     */
    public static Params fromProbe(double[] distances, double[] dampings) {
        int hits = 0;
        double sumDist = 0, sumDamp = 0;
        for (int i = 0; i < distances.length; i++) {
            if (!(distances[i] >= 0)) continue;
            hits++;
            sumDist += distances[i];
            sumDamp += clamp(dampings[i], 0, 1);
        }
        if (hits == 0) return DRY;
        double enclosure = hits / (double) distances.length;
        double meanDist = sumDist / hits;
        double alpha = Math.max(MIN_DAMPING, sumDamp / hits);
        double closed = Math.max(0, (enclosure - 0.5) / 0.5);
        // Sala muito absorvente devolve menos energia: o nível cai junto com o decaimento.
        float wet = (float) (MAX_WET * closed * closed * (1 - 0.5 * alpha));
        float decay = (float) clamp(SABINE_SPHERE * meanDist / alpha, MIN_DECAY, MAX_DECAY);
        float hf = (float) clamp(1.4 - 1.2 * alpha, 0.1, 2.0);
        float reflections = (float) clamp(2 * meanDist / SPEED_OF_SOUND, 0.0, 0.3);
        float late = (float) clamp(meanDist / SPEED_OF_SOUND, 0.005, 0.1);
        return new Params(wet, decay, hf, reflections, late);
    }

    /** Aproxima {@code a} de {@code b} (fator 0..1 para a nova leitura). */
    public static Params blend(Params a, Params b, double k) {
        if (a == null) return b;
        return new Params(
            (float) (a.wet + (b.wet - a.wet) * k),
            (float) (a.decayTime + (b.decayTime - a.decayTime) * k),
            (float) (a.decayHfRatio + (b.decayHfRatio - a.decayHfRatio) * k),
            (float) (a.reflectionsDelay + (b.reflectionsDelay - a.reflectionsDelay) * k),
            (float) (a.lateReverbDelay + (b.lateReverbDelay - a.lateReverbDelay) * k));
    }

    /**
     * {@code n} direções quase uniformes na esfera (espiral de Fibonacci), giradas de {@code phase} radianos em
     * torno do eixo Y. Metade aponta para baixo e metade para cima: no campo aberto, metade bate no chão.
     */
    public static double[][] directions(int n, double phase) {
        double[][] out = new double[n][3];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i + 0.5) * 2.0 / n;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double theta = golden * i + phase;
            out[i][0] = Math.cos(theta) * r;
            out[i][1] = y;
            out[i][2] = Math.sin(theta) * r;
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
