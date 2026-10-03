#!/usr/bin/env python3
"""Confere uma rodada de tools/prod/run.sh: logs do servidor e do cliente, e o WAV contra as marcas do roteiro.

Logs: o mod carregou, o relay abriu a estação, o /fm info mostra a rádio tocando (play1) e parada depois do
reload com a allowlist (refused), o mixin do cliente avisou o contexto OpenAL, e nenhuma pilha de exceção passa
por classe do mod (nem erro de mixin dele).

Áudio: som entre play1 e stop1, silêncio na janela recusada e som de novo entre play2 e stopall; silêncio no resto.
O WAV recomeça a cada abertura do dispositivo OpenAL, aproximada pela última linha "contexto OpenAL pronto" do
cliente (resolução de 1 s, por isso a tolerância).

Uso: analyze.py OUT_DIR   (sai com 1 se algo falhar)
"""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'e2e'))
import analyze_acoustic as aa  # noqa: E402

TOL = 4.0
STAMP = re.compile(r'\[(\d+:\d+:\d+)\]')


def secs(hms):
    h, m, s = hms.split(':')
    return int(h) * 3600 + int(m) * 60 + float(s)


def lines(path):
    return open(path, errors='replace').read().splitlines()


def timed(text_lines, pattern):
    """(segundos, linha) das linhas com carimbo que casam com o padrão."""
    out = []
    for line in text_lines:
        m = STAMP.match(line)
        if m and re.search(pattern, line):
            out.append((secs(m.group(1)), line))
    return out


def mod_traces(text_lines):
    """Linhas de pilha ou de erro de mixin que apontam para o mod."""
    bad = []
    for line in text_lines:
        if re.match(r'\s+at com\.akashiic\.', line) or re.search(r'(ERROR|WARN).*mixins\.akashicfm', line):
            bad.append(line.strip())
        elif re.search(r'(NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|ClassNotFoundException).*akashiic',
                       line):
            bad.append(line.strip())
    return bad


def main(out):
    fails = []
    marks = {}
    for line in lines(os.path.join(out, 'steps.log')):
        m = re.match(r'\[(\d+:\d+:\d+\.\d+)\] MARK (\w+)', line)
        if m:
            marks[m.group(2)] = secs(m.group(1))
    server = lines(os.path.join(out, 'server', 'server.out'))
    client = lines(os.path.join(out, 'client', 'logs', 'fml-client-latest.log'))

    if not timed(server, r'\[AkashicFM\]: AkashicFM .* carregado'):
        fails.append('o mod não carregou no servidor')
    if not timed(server, r'\[AkashicFM\]: Relay: .* aberta'):
        fails.append('o relay não abriu a estação')
    playing = [t for t, _ in timed(server, r'playing=true')]
    if not any(marks['play1'] <= t <= marks['stop1'] for t in playing):
        fails.append('/fm info não mostrou a rádio tocando depois de ligar pela redstone')
    stopped = [t for t, _ in timed(server, r'playing=false')]
    if not any(marks['refused'] <= t <= marks['play2'] for t in stopped):
        fails.append('/fm info não mostrou a rádio recusada depois do reload com a allowlist')
    if not timed(server, r'url=.* broadcasting=false on_air=false antennas='):
        fails.append('/fm info não mostrou o transmissor colocado')
    if not timed(server, r'Given .*portable_radio|Given .*headphones|Given \[.*\] \* 1 to Prod'):
        fails.append('o portátil e o fone não foram dados ao jogador')
    if any(marks['refused'] <= t <= marks['play2'] for t in playing):
        fails.append('a rádio tocou com a URL fora da allowlist')
    for name, text in (('servidor', server), ('cliente', client)):
        bad = mod_traces(text)
        if bad:
            fails.append('%s: %s' % (name, ' | '.join(bad[:5])))
    opens = timed(client, r'contexto OpenAL pronto')
    print('logs: mixin do cliente avisou %d contexto(s) OpenAL' % len(opens))
    if not opens:
        fails.append('o cliente não registrou "contexto OpenAL pronto" (mixin do cliente não aplicou?)')
    else:
        t0 = opens[-1][0]  # a última: cada reabertura do dispositivo recomeça o WAV
        samples, ch, rate, scale = aa.read_wav(os.path.join(out, 'client.wav'))
        levels, _ = aa.window_levels(samples, ch, rate, scale)
        win = aa.WIN_MS / 1000.0
        regs = [(t0 + a * win, t0 + b * win, max(levels[a:b])) for a, b in aa.regions(levels)]
        print('WAV: %.1f s, %d Hz, %d canais' % (len(levels) * win, rate, ch))
        for a, b, peak in regs:
            print('  som de %.1f a %.1f s (%.1f s), pico %.1f dBFS' % (a - t0, b - t0, b - a, peak))
        exp = [(marks['play1'], marks['stop1']), (marks['play2'], marks['stopall'])]
        print('  esperado: ' + ', '.join('%.1f a %.1f s' % (a - t0, b - t0) for a, b in exp))
        long_regs = [r for r in regs if r[1] - r[0] >= 3.0]
        if len(long_regs) != 2:
            fails.append('esperava 2 trechos com som, achei %d' % len(long_regs))
        else:
            for (ea, eb), (ra, rb, peak) in zip(exp, long_regs):
                if not ea - TOL <= ra <= ea + TOL + 3.0:  # entrada: relay + prebuffer
                    fails.append('começou em %.1f s, esperado %.1f' % (ra - t0, ea - t0))
                if abs(rb - eb) > TOL:
                    fails.append('parou em %.1f s, esperado %.1f' % (rb - t0, eb - t0))
                if peak < -40:
                    fails.append('baixo demais (pico %.1f dBFS)' % peak)
        stray = [r for r in regs if r[1] - r[0] < 3.0 and r[2] > -60]
        if stray:
            fails.append('sons soltos em ' + ', '.join('%.1f s' % (r[0] - t0) for r in stray))
    print('PROD: ' + ('OK' if not fails else 'FALHA: ' + '; '.join(fails)))
    return 0 if not fails else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
