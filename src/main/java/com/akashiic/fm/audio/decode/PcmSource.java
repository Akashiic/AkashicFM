package com.akashiic.fm.audio.decode;

import java.io.Closeable;
import java.io.IOException;

/** Saída comum dos decoders: PCM 16-bit intercalado. */
public interface PcmSource extends Closeable {

    int sampleRate();

    int channels();

    String codec();

    /** Lê até {@code len} amostras intercaladas. Devolve quantas leu, ou -1 no fim do stream. */
    int read(short[] out, int off, int len) throws IOException;
}
