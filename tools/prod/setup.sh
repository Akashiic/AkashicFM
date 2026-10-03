#!/usr/bin/env bash
# Prepara o teste com jars de produção em build/prod (uma vez; ~120 MB de downloads):
#  - Forge 1.7.10-10.13.4.1614 instalado como servidor dedicado (o instalador precisa do Java 8);
#  - os mods de produção dos conjuntos de tools/prod/packs.sh (GTNH 2.7, 2.8 e 2.9, o mínimo e a recusa);
#  - o classpath de um cliente Forge de produção (client.jar vanilla, bibliotecas da Mojang e assets do cache do
#    RetroFuturaGradle, preenchido por qualquer build do projeto).
# Uso: tools/prod/setup.sh   (JAVA8=/caminho/do/java para escolher o Java 8)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
W=$ROOT/build/prod; mkdir -p "$W/server" "$W/mods"
. "$ROOT/tools/prod/java8.sh"
. "$ROOT/tools/prod/packs.sh"
fetch() { [[ -s $2 ]] || { echo "baixando $(basename "$2")"; curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"; }; }

fetch https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-installer.jar \
  "$W/forge-installer.jar"
if [[ ! -f $W/server/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar ]]; then
  (cd "$W/server" && "$JAVA8" -jar ../forge-installer.jar --installServer > ../forge-install.log 2>&1) \
    || { echo "instalação do Forge falhou: $W/forge-install.log" >&2; exit 1; }
fi
for pack in gtnh-2.9 gtnh-2.8 gtnh-2.7 min refuse; do
  for mod in $(pack_mods "$pack"); do fetch "$(mod_url "$mod")" "$W/mods/$(mod_file "$mod")"; done
done
python3 "$ROOT/tools/prod/assemble.py" "$W"
echo "pronto: GTNHLib $GTNHLIB (build), $MIN (mínimo declarado), $BELOW (abaixo do mínimo)"
