#!/usr/bin/env bash
# O servidor de produção com um GTNHLib abaixo do mínimo declarado no @Mod: o Forge tem que recusar com a
# mensagem de dependência faltando, citando a versão mínima (a faixa do @Mod vale de verdade).
# Uso: tools/prod/refuse.sh   (antes, uma vez: tools/prod/setup.sh)
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
W=$ROOT/build/prod
. "$ROOT/tools/prod/java8.sh"
. "$ROOT/tools/prod/packs.sh"
JAR=${AKASHICFM_JAR:-$(ls -t "$ROOT"/build/libs/*.jar | grep -vE -- '-(dev|sources|dev-preshadow)\.jar$' | head -1)}
OUT=$W/out-refuse; rm -rf "$OUT"; mkdir -p "$OUT/mods"
ln -s "$W/server/libraries" "$OUT/libraries"
cp "$W/server/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar" "$W/server/minecraft_server.1.7.10.jar" "$OUT/"
for mod in $(pack_mods refuse); do cp "$W/mods/$(mod_file "$mod")" "$OUT/mods/"; done
cp "$JAR" "$OUT/mods/"
echo "eula=true" > "$OUT/eula.txt"
printf 'online-mode=false\nserver-port=25571\nlevel-type=FLAT\n' > "$OUT/server.properties"
(cd "$OUT" && timeout 240 "$JAVA8" -Xmx1G -jar forge-1.7.10-10.13.4.1614-1.7.10-universal.jar nogui \
  < /dev/null > server.out 2>&1)
code=$?
LOG=$(cat "$OUT/server.out" "$OUT"/crash-reports/*.txt 2>/dev/null)
if grep -q 'Done (' <<< "$LOG"; then
  echo "RECUSA: FALHA (o servidor subiu com o GTNHLib $BELOW)"; exit 1
fi
if grep -qE 'NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError' <<< "$LOG"; then
  echo "RECUSA: FALHA (caiu com erro de linkagem em vez da tela de dependência)"; exit 1
fi
if ! grep -qE "gtnhlib.*$MIN|$MIN.*gtnhlib" <<< "$LOG"; then
  echo "RECUSA: FALHA (sem a mensagem de dependência citando a $MIN; saída $code)"; exit 1
fi
grep -hE "gtnhlib.*$MIN|$MIN.*gtnhlib" <<< "$LOG" | head -3
echo "RECUSA: OK (GTNHLib $BELOW recusado pelo Forge, saída $code)"
