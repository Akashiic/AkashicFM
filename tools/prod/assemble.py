#!/usr/bin/env python3
"""Monta o classpath de um cliente Forge 1.7.10 de produção: Forge universal + bibliotecas dele, bibliotecas da
Mojang (do cache do Gradle ou de libraries.minecraft.net), nativos do LWJGL 2.9.1 e o client.jar vanilla
(obfuscado) do cache do RetroFuturaGradle.

Uso: assemble.py DIR  (DIR com server/ instalado pelo Forge e forge-installer.jar; escreve DIR/client-classpath.txt)
"""
import glob
import json
import os
import shutil
import sys
import urllib.request
import zipfile

W = os.path.abspath(sys.argv[1])
SERVER = os.path.join(W, 'server')
CACHE = os.path.expanduser('~/.gradle/caches/modules-2/files-2.1')
RFG = os.path.expanduser('~/.gradle/caches/retro_futura_gradle')
LIBS = os.path.join(W, 'client-libs')
NAT = os.path.join(W, 'natives')
FORGE = 'forge-1.7.10-10.13.4.1614-1.7.10-universal.jar'
os.makedirs(LIBS, exist_ok=True)
os.makedirs(NAT, exist_ok=True)


def fetch(group, art, ver, cls=None):
    name = '%s-%s%s.jar' % (art, ver, '-' + cls if cls else '')
    hits = glob.glob(os.path.join(CACHE, group, art, ver, '*', name))
    if hits:
        return hits[0]
    dst = os.path.join(LIBS, name)
    if not os.path.exists(dst):
        url = 'https://libraries.minecraft.net/%s/%s/%s/%s' % (group.replace('.', '/'), art, ver, name)
        urllib.request.urlretrieve(url, dst + '.part')
        os.rename(dst + '.part', dst)
    return dst


cp = []
# Uma cópia fora da pasta do servidor: o manifest do jar universal tem Class-Path para o minecraft_server.1.7.10.jar
# ao lado dele, e a JVM carregaria as classes do servidor antes das do cliente (o FML acusa "binary discrepancy").
forge = os.path.join(LIBS, FORGE)
shutil.copy(os.path.join(SERVER, FORGE), forge)
cp.append(forge)
profile = json.load(zipfile.ZipFile(os.path.join(W, 'forge-installer.jar')).open('install_profile.json'))
forge_arts = set()
for lib in profile['versionInfo']['libraries']:
    g, a, v = lib['name'].split(':')
    if a == 'forge':
        continue
    forge_arts.add((g, a))
    jar = os.path.join(SERVER, 'libraries', g.replace('.', '/'), a, v, '%s-%s.jar' % (a, v))
    cp.append(jar if os.path.exists(jar) else fetch(g, a, v))
vanilla = json.load(open(os.path.join(RFG, 'mc-vanilla', 'manifest_1.7.10.json')))
for lib in vanilla['libraries']:
    g, a, v = lib['name'].split(':')
    if (g, a) in forge_arts or lib.get('rules'):  # versões do Forge ganham; nativos do twitch só osx/windows
        continue
    natives = lib.get('natives')
    if natives:
        with zipfile.ZipFile(fetch(g, a, v, natives['linux'])) as z:
            for n in z.namelist():
                if not n.startswith('META-INF') and not n.endswith('/'):
                    z.extract(n, NAT)
        continue
    cp.append(fetch(g, a, v))
cp.append(os.path.join(RFG, 'mc-vanilla', '1.7.10', 'client.jar'))
missing = [p for p in cp if not os.path.exists(p)]
if missing:
    sys.exit('faltando: %s' % missing)
open(os.path.join(W, 'client-classpath.txt'), 'w').write(':'.join(os.path.abspath(p) for p in cp))
print('cliente: %d jars, nativos %s' % (len(cp), sorted(os.listdir(NAT))))
