#!/usr/bin/env bash
# Teste com os jars de produção (~4 min): Forge 1.7.10-1614 dedicado + um cliente Forge de verdade (Java 8, LWJGL
# 2.9.1 e o OpenAL Soft original, que grava a mixagem num WAV). Pega o que o E2E do ambiente de dev não vê: o jar
# reobfuscado, os mixins nele, o lançador do Forge e a convivência com os mods de produção.
#
# Roteiro: o jogador "Prod" entra; a rádio entra pelo console (setblock com NBT) e liga por um bloco de redstone,
# caminho real de jogo. Toca ~45 s pelo relay, para pela redstone, o /fm reload com uma allowlist que recusa a URL
# impede de ligar de novo, outro /fm reload volta ao normal, toca ~25 s e o /fm stopall para. Depois
# tools/prod/analyze.py confere o WAV contra as marcas e os logs (exceção do mod, mixin do cliente, /fm info).
#
# Roteiro "ipod" (~5 min, precisa de internet): o iPod com o yt-dlp de verdade. Liga no arquivo de config e no
# /fm reload (o caminho do Pterodactyl, sem reiniciar), espera o mod baixar o yt-dlp oficial do GitHub (SHA-256
# conferido) e dá ao jogador, pelo console, um iPod com um link do SoundCloud (toca direto) e depois outro com um do
# YouTube (toca a mesma música achada no SoundCloud); o /fm stopall desliga o segundo.
#
# Uso: tools/prod/run.sh gtnh-2.9|gtnh-2.8|gtnh-2.7|min [radio|ipod]   (antes, uma vez: tools/prod/setup.sh)
#   gtnh-*: GTNHLib, UniMixins, Hodgepodge e OpenComputers nas versões daquele pack (tools/prod/packs.sh)
#   min:    só o obrigatório, com o GTNHLib mínimo declarado no @Mod
# AKASHICFM_JAR escolhe o jar (padrão: o mais novo de build/libs); PROD_URL, o stream.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
W=$ROOT/build/prod; SET=${1:-gtnh-2.9}; SCEN=${2:-radio}
[[ $SCEN == radio || $SCEN == ipod ]] || { echo "roteiro desconhecido: $SCEN (radio ou ipod)" >&2; exit 2; }
. "$ROOT/tools/prod/java8.sh"
. "$ROOT/tools/prod/packs.sh"
[[ -f $W/client-classpath.txt ]] || { echo "rode antes: tools/prod/setup.sh" >&2; exit 1; }
JAR=${AKASHICFM_JAR:-$(ls -t "$ROOT"/build/libs/*.jar | grep -vE -- '-(dev|sources|dev-preshadow)\.jar$' | head -1)}
[[ $SET != refuse ]] && PACK=$(pack_mods "$SET") || { echo "uso: $0 gtnh-2.9|gtnh-2.8|gtnh-2.7|min" >&2; exit 2; }
MODS=""; for mod in $PACK; do MODS="$MODS $(mod_file "$mod")"; done
URL=${PROD_URL:-https://stream.radioparadise.com/mp3-128}
RFG=$HOME/.gradle/caches/retro_futura_gradle
OUT=$W/out-$SET; [[ $SCEN == ipod ]] && OUT=$OUT-ipod; rm -rf "$OUT"; mkdir -p "$OUT/server/mods" "$OUT/client/mods" "$OUT/client/config"
STEPS=$OUT/steps.log
log() { echo "[$(date +%T.%3N)] $*" | tee -a "$STEPS"; }
log "jar $(basename "$JAR"); roteiro $SCEN; mods: $MODS"

# ---- servidor ----
ln -s "$W/server/libraries" "$OUT/server/libraries"
cp "$W/server/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar" "$W/server/minecraft_server.1.7.10.jar" "$OUT/server/"
for m in $MODS; do cp "$W/mods/$m" "$OUT/server/mods/"; cp "$W/mods/$m" "$OUT/client/mods/"; done
cp "$JAR" "$OUT/server/mods/"; cp "$JAR" "$OUT/client/mods/"
cat > "$OUT/server/server.properties" <<EOF
online-mode=false
server-port=25570
level-type=FLAT
generate-structures=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
spawn-protection=0
view-distance=4
difficulty=0
gamemode=1
EOF
echo "eula=true" > "$OUT/server/eula.txt"
# Atrás de um proxy que re-assina o TLS (como o ambiente de CI/agente), a CA dele só está no truststore apontado por
# javax.net.ssl.trustStore. O LetsEncryptHelper do FalsePatternLib (no GTNHLib dos packs novos) troca o SSLContext
# padrão por um feito do cacerts do próprio JDK, sem essa CA, e o download do yt-dlp falha com PKIX. Com proxy, ele
# fica desligado; a verificação de TLS continua, com o truststore configurado. Sem proxy (um servidor de verdade), nada
# muda.
if [[ -n ${HTTPS_PROXY:-${https_proxy:-}} ]]; then
  mkdir -p "$OUT/server/config"
  printf '{\n  "enableLibraryDownloads": true,\n  "enableLetsEncryptRoot": false\n}\n' \
    > "$OUT/server/config/falsepatternlib-early.json"
fi
mkfifo "$OUT/server/in"
(cd "$OUT/server" && exec "$JAVA8" -Xms1G -Xmx2G -jar forge-1.7.10-10.13.4.1614-1.7.10-universal.jar nogui \
  < in > server.out 2>&1) &
SPID=$!
exec 3> "$OUT/server/in"
cmd() { log "> $*"; echo "$*" >&3; }
wait_for() { # arquivo padrão segundos
  for _ in $(seq 1 "$3"); do grep -qE "$2" "$1" 2>/dev/null && return 0; sleep 1; done
  log "TIMEOUT esperando /$2/ em $1"; return 1
}
# Só o java do cliente (pelo /proc, nunca pgrep -f: casaria com o próprio shell).
client_pids() { for pid in $(pgrep -x java || true); do
  tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -qx 'net.minecraft.launchwrapper.Launch' && echo "$pid"; done; }
finish() {
  for pid in $(client_pids); do kill -TERM "$pid"; done
  for _ in $(seq 1 30); do [[ -z $(client_pids) ]] && break; sleep 1; done
  for pid in $(client_pids); do kill -KILL "$pid"; done
  sleep 2
  cmd "stop"
  for _ in $(seq 1 90); do kill -0 $SPID 2>/dev/null || break; sleep 1; done
  kill -0 $SPID 2>/dev/null && { log "o servidor não parou; TERM"; kill -TERM $SPID; }
  exec 3>&-
}
wait_for "$OUT/server/server.out" 'Done \(' 300 || { finish; exit 1; }
log "servidor pronto"

# ---- cliente ----
cat > "$OUT/client/options.txt" <<EOF
pauseOnLostFocus:false
renderDistance:4
fancyGraphics:false
soundCategory_master:1.0
soundCategory_music:0.0
soundCategory_record:1.0
soundCategory_weather:0.0
soundCategory_block:0.0
soundCategory_hostile:0.0
soundCategory_neutral:0.0
soundCategory_player:0.0
soundCategory_ambient:0.0
lang:en_US
EOF
printf '[general]\ndrivers = wave\nchannels = stereo\nsample-type = int16\nfrequency = 48000\n\n[wave]\nfile = %s\n' \
  "$OUT/client.wav" > "$OUT/alsoft.conf"
(cd "$OUT/client" && ALSOFT_CONF="$OUT/alsoft.conf" ALSOFT_DRIVERS=wave LIBGL_ALWAYS_SOFTWARE=1 exec setsid \
  xvfb-run -a -s "-screen 0 1280x720x24" "$JAVA8" -Xmx2G -Djava.library.path="$W/natives" \
  -cp "$(cat "$W/client-classpath.txt")" net.minecraft.launchwrapper.Launch \
  --username Prod --version 1.7.10 --gameDir "$OUT/client" --assetsDir "$RFG/assets" --assetIndex 1.7.10 \
  --uuid df61de6a36c43777814f94bc1deb2e30 --accessToken 0 --userProperties '{}' --userType legacy \
  --tweakClass cpw.mods.fml.common.launcher.FMLTweaker --server 127.0.0.1 --port 25570 \
  < /dev/null > client.out 2>&1) &
log "cliente lançado"
wait_for "$OUT/server/server.out" 'Prod\[.*logged in' 400 || { finish; exit 1; }
log "Prod entrou"
sleep 10
cmd "tp Prod 0.5 10 0.5"
sleep 8
if [[ $SCEN == ipod ]]; then
  SC=https://soundcloud.com/forss/flickermood
  YT=https://www.youtube.com/watch?v=5NV6Rdv1a3I
  # A fila como fica depois de adicionar o vídeo: título e canal do YouTube e a duração, que o espelho usa.
  YT_TITLE="Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers"
  CFG=$OUT/server/config/akashicfm.cfg
  python3 - "$CFG" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
s2 = re.sub(r'(ipod \{[^}]*?B:enabled=)false', r'\1true', s, flags=re.S)
assert s2 != s, 'ipod.enabled não encontrado'
open(p, 'w').write(s2)
PY
  cmd "fm reload"
  wait_for "$OUT/server/server.out" 'iPod: yt-dlp pronto' 240 || { finish; exit 1; }
  log "MARK ipod1"
  cmd "give Prod akashicfm:ipod 1 0 {ipod:{on:1b,index:0,session:1,volume:100b,q:[{s:0b,l:\"$SC\"}]}}"
  sleep 35
  cmd "fm list portables"
  sleep 1
  log "MARK ipod1end"
  cmd "clear Prod akashicfm:ipod"
  sleep 8
  log "MARK ipod2"
  cmd "give Prod akashicfm:ipod 1 0 {ipod:{on:1b,index:0,session:1,volume:100b,q:[{s:1b,l:\"$YT\",t:\"$YT_TITLE\",a:\"Daft Punk\",d:249}]}}"
  sleep 45
  cmd "fm list portables"
  sleep 1
  log "MARK stopall"
  cmd "fm stopall"
  sleep 8
  cmd "fm list portables"
  sleep 2
  log "fechando"
  finish
  log "FIM"
  python3 "$ROOT/tools/prod/analyze.py" "$OUT" ipod
  exit $?
fi
# O estado do TileRadio fica na tag "radio"; redstoneMode 1 = tocar enquanto ligada, access 1 = pública.
cmd "setblock 2 4 2 akashicfm:radio 0 replace {radio:{url:\"$URL\",redstoneMode:1b,access:1b,volume:100b,range:48s}}"
sleep 3
log "MARK play1"
cmd "setblock 3 4 2 minecraft:redstone_block"
sleep 4
cmd "fm list"
cmd "fm info 2 4 2"
# Transmissor com antena e os itens do portátil e do fone: instanciados e em uso sem IC2, RF nem Baubles
# (as interfaces opcionais cortadas pelo @Optional).
cmd "setblock 6 4 6 akashicfm:transmitter"
cmd "setblock 6 5 6 akashicfm:antenna"
cmd "give Prod akashicfm:portable_radio"
cmd "give Prod akashicfm:headphones"
sleep 5
cmd "fm info 6 4 6"
cmd "fm list transmitters"
cmd "fm list portables"
sleep 35
log "MARK stop1"
cmd "setblock 3 4 2 minecraft:air"
sleep 8
CFG=$OUT/server/config/akashicfm.cfg
cp "$CFG" "$OUT/akashicfm.cfg.orig"
python3 - "$CFG" <<'EOF'
import re, sys
p = sys.argv[1]; s = open(p).read()
s2 = re.sub(r'(S:allowedHosts <)\s*(>)', r'\1\n            example.com\n         \2', s)
assert s2 != s, 'allowedHosts não encontrado'
open(p, 'w').write(s2)
EOF
cmd "fm reload"
sleep 2
log "MARK refused"
cmd "setblock 3 4 2 minecraft:redstone_block"
sleep 5
cmd "fm info 2 4 2"
cmd "setblock 3 4 2 minecraft:air"
cp "$OUT/akashicfm.cfg.orig" "$CFG"
cmd "fm reload"
sleep 3
log "MARK play2"
cmd "setblock 3 4 2 minecraft:redstone_block"
sleep 25
log "MARK stopall"
cmd "fm stopall"
sleep 8
cmd "fm list"
sleep 2
log "fechando"
finish
log "FIM"
python3 "$ROOT/tools/prod/analyze.py" "$OUT"
