#!/usr/bin/env python3
"""Confere uma rodada de tools/prod/run.sh: logs do servidor e do cliente, e o WAV contra as marcas do roteiro.

Logs: o mod carregou, o relay abriu a estação, o /fm info mostra a rádio tocando (play1) e parada depois do
reload com a allowlist (refused), o mixin do cliente avisou o contexto OpenAL, e nenhuma pilha de exceção passa
por classe do mod (nem erro de mixin dele).

Áudio: som entre play1 e stop1, silêncio na janela recusada e som de novo entre play2 e stopall; silêncio no resto.
O WAV recomeça a cada abertura do dispositivo OpenAL, aproximada pela última linha "contexto OpenAL pronto" do
cliente (resolução de 1 s, por isso a tolerância).

Roteiro "ipod": o yt-dlp ficou pronto, as duas estações do iPod abriram, o /fm list portables mostrou as faixas
(a do SoundCloud com o título que veio ao tocar; a do YouTube tocando pelo espelho), nenhuma falhou, e o som vai de
ipod1 (depois da resolução) a ipod1end e de ipod2 (resolução e busca do espelho) ao stopall.

Roteiro "ipodblock": as estações dos dois iPod Players abriram, o /fm list e o /fm info mostraram cada um tocando a
sua faixa (com a fila) e o alto-falante ligado, o primeiro parou ao desligar a redstone e o segundo com o /fm stop
(com a redstone ainda ligada), nenhuma faixa falhou, e o som, que só chega pelos alto-falantes, vai de blk1 a blk1end
e de blk2 ao stop.

Uso: analyze.py OUT_DIR [radio|ipod|ipodblock]   (sai com 1 se algo falhar)
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


def check_radio(server, marks, fails):
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
    # Entrada: relay + prebuffer.
    return [(marks['play1'], marks['stop1'], 3.0), (marks['play2'], marks['stopall'], 3.0)]


def check_ipod(server, marks, fails):
    if not timed(server, r'iPod: yt-dlp pronto'):
        fails.append('o yt-dlp não ficou pronto')
    # Sem acento no padrão: o console do servidor de produção não sai em UTF-8 ("esta??o").
    opened = timed(server, r'\[AkashicFM\]: Relay: esta\S+ \d+ aberta para ipod:')
    print('logs: %d estação(ões) do iPod aberta(s)' % len(opened))
    if not any(marks['ipod1'] <= t <= marks['ipod1end'] for t, _ in opened):
        fails.append('a estação do iPod do SoundCloud não abriu')
    if not any(marks['ipod2'] <= t <= marks['stopall'] for t, _ in opened):
        fails.append('a estação do iPod do YouTube não abriu')
    # Ao tocar, a faixa do set/link ganha o título e o artista que vieram do yt-dlp.
    if not timed(server, r'iPod: Forss - Flickermood'):
        fails.append('/fm list portables não mostrou a faixa do SoundCloud')
    if not timed(server, r'iPod: Daft Punk - Get Lucky'):
        fails.append('/fm list portables não mostrou a faixa do YouTube')
    failed = timed(server, r'iPod ipod:\w+: .* falhou')
    for _, line in failed:
        fails.append('faixa falhou: ' + line.split('] ', 2)[-1])
    # Entrada: a resolução (o yt-dlp; com o espelho, também a busca) antes do relay.
    return [(marks['ipod1'], marks['ipod1end'], 25.0), (marks['ipod2'], marks['stopall'], 35.0)]


def check_ipodblock(server, marks, fails):
    if not timed(server, r'iPod: yt-dlp pronto'):
        fails.append('o yt-dlp não ficou pronto')
    # A chave da estação do bloco é a posição (dimensão, x, y, z).
    for key, a, b, what in (('ipod:b0_16_4_2', 'blk1', 'blk1end', 'SoundCloud'), ('ipod:b0_16_4_6', 'blk2', 'stop',
                                                                                   'YouTube')):
        opened = timed(server, r'\[AkashicFM\]: Relay: esta\S+ \d+ aberta para ' + re.escape(key) + r'$')
        if not any(marks[a] <= t <= marks[b] for t, _ in opened):
            fails.append('a estação do iPod Player do %s (%s) não abriu' % (what, key))
    # /fm list radios: "dim 0 (16, 4, 2) · iPod: faixa (1 queued) · RELAY · ..." (o console fala inglês e troca o
    # ponto do meio por "?").
    for pattern, a, b, what in ((r'iPod: Forss - Flickermood \(1 queued\) \S+ RELAY', 'blk1', 'blk1end', 'SoundCloud'),
                                (r'iPod: Daft Punk - Get Lucky.* \(1 queued\) \S+ RELAY', 'blk2', 'stop', 'YouTube')):
        if not any(marks[a] <= t <= marks[b] for t, _ in timed(server, pattern)):
            fails.append('/fm list não mostrou o iPod Player do %s tocando' % what)
    # /fm info: tocando, com o alcance curto e o alto-falante ligado; parado depois da redstone e do /fm stop.
    info = timed(server, r'playing=(true|false) volume=\d+ range=\d+ speakers=\d+')
    def seen(a, b, playing):
        text = 'playing=%s volume=100 range=8 speakers=1' % playing
        return any(a <= t <= b and text in line for t, line in info)
    if not seen(marks['blk1'], marks['blk1end'], 'true'):
        fails.append('/fm info não mostrou o iPod Player do teto tocando com o alto-falante ligado')
    if not seen(marks['blk1end'], marks['blk2'], 'false'):
        fails.append('/fm info não mostrou o iPod Player do teto parado depois de desligar a redstone')
    if not seen(marks['blk2'], marks['stop'], 'true'):
        fails.append('/fm info não mostrou o iPod Player da parede tocando com o alto-falante ligado')
    if not seen(marks['stop'], marks['stop'] + 30, 'false'):
        fails.append('/fm info não mostrou o iPod Player da parede parado depois do /fm stop')
    for _, line in timed(server, r'iPod ipod:\S+: .* falhou'):
        fails.append('faixa falhou: ' + line.split('] ', 2)[-1])
    # Entrada: a resolução (o yt-dlp; com o espelho, também a busca) antes do relay.
    return [(marks['blk1'], marks['blk1end'], 25.0), (marks['blk2'], marks['stop'], 35.0)]


def main(out, scen='radio'):
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
    check = {'ipod': check_ipod, 'ipodblock': check_ipodblock}.get(scen, check_radio)
    exp = check(server, marks, fails)
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
        print('  esperado: ' + ', '.join('%.1f (+%.0f) a %.1f s' % (a - t0, d, b - t0) for a, b, d in exp))
        long_regs = [r for r in regs if r[1] - r[0] >= 3.0]
        if len(long_regs) != 2:
            fails.append('esperava 2 trechos com som, achei %d' % len(long_regs))
        else:
            for (ea, eb, delay), (ra, rb, peak) in zip(exp, long_regs):
                if not ea - TOL <= ra <= ea + TOL + delay:
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
    sys.exit(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'radio'))
