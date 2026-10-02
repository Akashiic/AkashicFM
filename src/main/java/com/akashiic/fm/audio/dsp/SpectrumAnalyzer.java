package com.akashiic.fm.audio.dsp;

/**
 * Espectro em 8 bandas (quase oitavas, de 40 Hz a 16 kHz) e nível geral de um bloco de PCM, para o
 * visualizador. FFT de 2048 pontos (um bloco da reprodução) com janela de Hann sobre a mistura mono. Cada banda vai de
 * 0 a 1, mapeando
 * de -60 dBFS a 0 dBFS (seno de amplitude cheia = 0 dBFS). Sem alocação por chamada; uma instância por thread.
 * Testado em SpectrumAnalyzerTest.
 */
public final class SpectrumAnalyzer {

    public static final int BANDS = 8;
    public static final int FFT_SIZE = 2048;
    /** Bordas das bandas (Hz). */
    static final double[] EDGES = { 40, 100, 200, 400, 800, 1600, 3200, 6400, 16000 };
    static final double FLOOR_DB = -60;

    private final int sampleRate;
    private final double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE];
    private final double[] window = new double[FFT_SIZE];
    private final double windowPower;
    private final int[] bandStart = new int[BANDS], bandEnd = new int[BANDS];

    public SpectrumAnalyzer(int sampleRate) {
        this.sampleRate = sampleRate;
        double sumSq = 0;
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
            sumSq += window[i] * window[i];
        }
        windowPower = sumSq;
        double binHz = sampleRate / (double) FFT_SIZE;
        for (int b = 0; b < BANDS; b++) {
            bandStart[b] = Math.max(1, (int) Math.round(EDGES[b] / binHz));
            bandEnd[b] = Math.max(bandStart[b] + 1, Math.min(FFT_SIZE / 2, (int) Math.round(EDGES[b + 1] / binHz)));
        }
    }

    /**
     * Analisa até {@link #FFT_SIZE} frames estéreo intercalados (o resto é completado com zero) e escreve as 8
     * bandas em {@code bands}. Devolve o nível geral (0..1, mesma escala), do RMS sem janela.
     */
    public float analyze(short[] stereo, int frames, float[] bands) {
        int n = Math.min(frames, FFT_SIZE);
        double sumSq = 0;
        for (int i = 0; i < FFT_SIZE; i++) {
            double v = 0;
            if (i < n) {
                v = (stereo[2 * i] + stereo[2 * i + 1]) / 65536.0; // mistura mono em [-1, 1)
                sumSq += v * v;
            }
            re[i] = v * window[i];
            im[i] = 0;
        }
        fft(re, im);
        // Potência média por banda, normalizada para que um seno de amplitude A some A²/2 (Parseval com a janela).
        double norm = 2.0 / (FFT_SIZE * windowPower);
        for (int b = 0; b < BANDS; b++) {
            double p = 0;
            for (int k = bandStart[b]; k < bandEnd[b]; k++) p += re[k] * re[k] + im[k] * im[k];
            bands[b] = level(p * norm);
        }
        return n == 0 ? 0f : level(sumSq / n);
    }

    /** Potência média (seno cheio = 0,5) para 0..1 entre -60 e 0 dBFS. */
    static float level(double meanPower) {
        if (!(meanPower > 0)) return 0f;
        double db = 10 * Math.log10(meanPower / 0.5);
        double x = (db - FLOOR_DB) / -FLOOR_DB;
        return (float) (x < 0 ? 0 : (x > 1 ? 1 : x));
    }

    /** Frequência central aproximada da banda {@code b} (Hz), para testes e legendas. */
    public static double centerHz(int b) {
        return Math.sqrt(EDGES[b] * EDGES[b + 1]);
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** FFT radix-2 in-place (tamanho potência de 2). */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
