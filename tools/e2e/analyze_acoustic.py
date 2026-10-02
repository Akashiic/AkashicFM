#!/usr/bin/env python3
"""Mede o áudio de saída gravado pelo OpenAL Soft (backend "wave") no cenário E2E "acoustic".

Segmentos esperados, separados por silêncio (rádio muda por 2 s):
  R0 aquecimento | A aberto | B lã | C vidro | D aberto | E sala de pedra (para dentro: cauda) | F aberto (para)

Para cada segmento (sem o 1º segundo e o último meio segundo): nível RMS (dBFS) e "agudos" = energia acima de
4 kHz (passa-altas Butterworth de 2ª ordem) em relação à energia total, em dB. A música muda de um segmento
para outro, então os limites são largos; o efeito da lã (-34 dB nos agudos, -8,6 dB no geral) passa muito deles.

Cauda: na parada (E e F) mede a energia de 30 a 330 ms depois do corte em relação aos 500 ms antes dele.
A fonte é apagada no corte, então o que sobra é o reverb: na sala de pedra fica acima de -45 dB; no aberto,
silêncio (abaixo de -60 dB).

Uso: analyze_acoustic.py arquivo.wav log-do-cliente [--no-efx]
"""
import math
import re
import struct
import sys
from array import array

WIN_MS = 10
SILENCE_DBFS = -80.0
MIN_GAP_S = 1.0


def read_wav(path):
    """Amostras intercaladas (array compacto), canais, taxa e escala para [-1, 1]."""
    with open(path, 'rb') as f:
        data = f.read()
    if data[:4] != b'RIFF' or data[8:12] != b'WAVE':
        raise SystemExit('não é WAV: ' + path)
    pos, fmt, pcm = 12, None, None
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], struct.unpack('<I', data[pos + 4:pos + 8])[0]
        body = pos + 8
        if cid == b'fmt ':
            tag, ch, rate = struct.unpack('<HHI', data[body:body + 8])
            bits = struct.unpack('<H', data[body + 14:body + 16])[0]
            if tag == 0xFFFE:  # WAVE_FORMAT_EXTENSIBLE: o tipo real está no GUID
                tag = struct.unpack('<H', data[body + 24:body + 26])[0]
            fmt = (tag, ch, rate, bits)
        elif cid == b'data':
            # O tamanho só é escrito ao fechar o dispositivo; se o processo morreu, vale até o fim do arquivo.
            end = len(data) if size == 0 or body + size > len(data) else body + size
            pcm = data[body:end]
            break
        pos = body + size + (size & 1)
    if fmt is None or pcm is None:
        raise SystemExit('WAV sem fmt/data')
    tag, ch, rate, bits = fmt
    if tag == 1 and bits == 16:
        samples, scale = array('h'), 1 / 32768.0
    elif tag == 3 and bits == 32:
        samples, scale = array('f'), 1.0
    else:
        raise SystemExit('formato não suportado: tag=%d bits=%d' % (tag, bits))
    usable = len(pcm) - len(pcm) % (samples.itemsize * ch)
    samples.frombytes(pcm[:usable])
    if sys.byteorder != 'little':
        samples.byteswap()
    return samples, ch, rate, scale


def mono(samples, ch, scale, a, b):
    """Frames [a, b) em mono, normalizados."""
    out = []
    k = scale / ch
    for i in range(a, b):
        out.append(sum(samples[i * ch:(i + 1) * ch]) * k)
    return out


def db(x):
    return 10 * math.log10(x) if x > 1e-20 else -200.0


def window_levels(samples, ch, rate, scale):
    """Nível (dBFS, mono) em janelas de WIN_MS."""
    w = rate * WIN_MS // 1000
    frames = len(samples) // ch
    out = []
    k = (scale / ch) ** 2
    for i in range(0, frames - w + 1, w):
        seg = samples[i * ch:(i + w) * ch]
        if ch == 1:
            e = sum(v * v for v in seg)
        else:
            e = 0.0
            for j in range(0, len(seg), ch):
                m = sum(seg[j:j + ch])
                e += m * m
        out.append(db(e * k / w))
    return out, w


def regions(levels):
    """Trechos com som separados por pelo menos MIN_GAP_S de silêncio: lista de (início, fim) em janelas."""
    gap = int(MIN_GAP_S * 1000 / WIN_MS)
    out, start, quiet = [], None, 0
    for i, l in enumerate(levels):
        if l > SILENCE_DBFS:
            if start is None:
                start = i
            quiet = 0
        elif start is not None:
            quiet += 1
            if quiet >= gap:
                out.append((start, i - quiet + 1))
                start, quiet = None, 0
    if start is not None:
        out.append((start, len(levels) - quiet))
    return out


def highpass(x, rate, fc=4000.0):
    # Butterworth de 2ª ordem (RBJ), Q = 1/sqrt(2).
    w0 = 2 * math.pi * fc / rate
    alpha = math.sin(w0) / math.sqrt(2)
    c = math.cos(w0)
    b0, b1, b2 = (1 + c) / 2, -(1 + c), (1 + c) / 2
    a0, a1, a2 = 1 + alpha, -2 * c, 1 - alpha
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y, x1, x2, y1, y2 = [], 0.0, 0.0, 0.0, 0.0
    for v in x:
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y.append(o)
    return y


def measure(samples, ch, rate, scale, a, b):
    seg = mono(samples, ch, scale, a, b)
    total = sum(v * v for v in seg) / max(1, len(seg))
    hp = highpass(seg, rate)
    hf = sum(v * v for v in hp) / max(1, len(hp))
    return db(total), db(hf) - db(total)


def tail(levels, cut):
    """Energia de 30 a 330 ms depois do corte em relação aos 500 ms antes (dB)."""
    def energy(i0, i1):
        vals = [10 ** (l / 10) for l in levels[max(0, i0):i1]]
        return sum(vals) / max(1, len(vals))
    before = energy(cut - 500 // WIN_MS, cut)
    after = energy(cut + 30 // WIN_MS, cut + 330 // WIN_MS)
    return db(after) - db(before)


def find_cut(levels, est):
    """Maior queda de nível perto da estimativa: média de 200 ms antes menos média de 100 ms depois."""
    best, best_i = -1e9, est
    for i in range(max(20, est - 40), min(len(levels) - 10, est + 40)):
        before = sum(levels[i - 20:i]) / 20
        after = sum(levels[i:i + 10]) / 10
        if before - after > best:
            best, best_i = before - after, i
    return best_i


def marks(log_path):
    out = {}
    pat = re.compile(r'acoustic-mark (\S+) (\S+) ms=(\d+)')
    with open(log_path, errors='replace') as f:
        for line in f:
            m = pat.search(line)
            if m:
                out[(m.group(1), m.group(2))] = int(m.group(3))
    return out


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    wav, log = sys.argv[1], sys.argv[2]
    no_efx = '--no-efx' in sys.argv
    samples, ch, rate, scale = read_wav(wav)
    levels, w = window_levels(samples, ch, rate, scale)
    regs = regions(levels)
    print('WAV: %.1f s, %d Hz, %d canais; trechos com som: %d' % (len(samples) / ch / rate, rate, ch, len(regs)))
    for i, (a, b) in enumerate(regs):
        print('  trecho %d: %.2f s a %.2f s' % (i, a * WIN_MS / 1000, b * WIN_MS / 1000))
    names = ['R0', 'A-aberto', 'B-la', 'C-vidro', 'D-aberto', 'E-sala-de-pedra', 'F-aberto-parada']
    if len(regs) != len(names):
        print('ACÚSTICA: esperava %d trechos, achei %d' % (len(names), len(regs)))
        return 1
    mk = marks(log)
    res = {}
    for name, (a, b) in zip(names, regs):
        if name == 'R0':
            continue
        if name in ('E-sala-de-pedra', 'F-aberto-parada'):
            # O fim do trecho inclui a cauda: o corte é estimado pelas marcas do roteiro e refinado pela queda.
            start_ev = 'unmute' if name.startswith('E') else 'playing'
            dur = (mk[(name, 'stop')] - mk[(name, start_ev)]) / 1000.0
            cut = find_cut(levels, a + int(dur * 1000 / WIN_MS))
            end = cut
        else:
            cut, end = None, b
        lv, hf = measure(samples, ch, rate, scale, (a + 1000 // WIN_MS) * w, (end - 500 // WIN_MS) * w)
        t = tail(levels, cut) if cut is not None else None
        res[name] = (lv, hf, t)
        print('%-16s nível %6.1f dBFS  agudos %6.1f dB%s' % (
            name, lv, hf, '' if t is None else '  cauda %6.1f dB' % t))

    ref_lv = (res['A-aberto'][0] + res['D-aberto'][0]) / 2
    ref_hf = (res['A-aberto'][1] + res['D-aberto'][1]) / 2
    checks = []

    def check(desc, ok):
        checks.append(ok)
        print('%s %s' % ('OK  ' if ok else 'FALHA', desc))

    check('aberto A e D parecidos (%.1f e %.1f dBFS)' % (res['A-aberto'][0], res['D-aberto'][0]),
          abs(res['A-aberto'][0] - res['D-aberto'][0]) < 6)
    d_lv, d_hf = res['B-la'][0] - ref_lv, res['B-la'][1] - ref_hf
    if no_efx:
        # Sem EFX: só o ganho (1 - 0,85·0,9 = 0,235, -12,6 dB), sem mudar o equilíbrio dos agudos.
        check('lã sem EFX: nível %.1f dB (esperado ~-12,6)' % d_lv, -18 < d_lv < -8)
        check('lã sem EFX: agudos %.1f dB (sem low-pass, perto de 0)' % d_hf, abs(d_hf) < 6)
    else:
        check('lã: nível %.1f dB (esperado bem abaixo do aberto)' % d_lv, d_lv < -6)
        check('lã: agudos %.1f dB (low-pass: esperado < -10)' % d_hf, d_hf < -10)
    c_lv, c_hf = res['C-vidro'][0] - ref_lv, res['C-vidro'][1] - ref_hf
    check('vidro: nível %.1f dB (quase sem perda)' % c_lv, -4 < c_lv < 3)
    check('vidro: agudos %.1f dB (quase sem abafar)' % c_hf, -6 < c_hf < 4)
    e_tail, f_tail = res['E-sala-de-pedra'][2], res['F-aberto-parada'][2]
    if no_efx:
        check('sem EFX: sem cauda na sala (%.1f dB)' % e_tail, e_tail < -60)
    else:
        # O som direto some no corte (a fonte é apagada): energia depois dele só pode ser o reverb. O reverb do
        # OpenAL Soft 1.15 (LWJGL2, Java 8) é mais discreto que o das versões novas (~-33 contra ~-21 dB).
        check('sala de pedra: cauda do reverb %.1f dB (esperado > -45; no aberto < -60)' % e_tail, e_tail > -45)
    check('aberto: sem cauda na parada (%.1f dB)' % f_tail, f_tail < -60)
    ok = all(checks)
    print('ACÚSTICA: %s' % ('OK' if ok else 'FALHOU'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
