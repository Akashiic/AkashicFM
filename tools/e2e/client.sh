#!/usr/bin/env bash
# Cliente dev direto sob Xvfb. Uso: tools/e2e/client.sh <usuário> <gameDir> <cenário> [runClient21|runClient]
# Cenários: main, main+peer, peer, listen, soak, acoustic (ver com.akashiic.fm.dev.E2EClient).
set -euo pipefail
USER_NAME=${1:?usuario}; GAMEDIR=${2:?gameDir}; SCEN=${3:?cenario}; TASK=${4:-runClient21}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CMDFILE="$ROOT/build/e2e/$TASK.cmd"
[[ -f $CMDFILE ]] || { echo "rode antes: tools/e2e/capture.sh $TASK" >&2; exit 1; }
JAR=$(ls -t "$ROOT"/build/libs/*-dev-preshadow.jar | head -1)
mapfile -t CMD < <(sed -E "s#[^:]*/build/libs/[^:]*-dev-preshadow\.jar#$JAR#" "$CMDFILE")
HAS_SERVER=0
for i in "${!CMD[@]}"; do
  case "${CMD[$i]}" in
    --username) CMD[$((i+1))]=$USER_NAME ;;
    --gameDir) CMD[$((i+1))]=$GAMEDIR ;;
    --uuid) CMD[$((i+1))]=$(cat /proc/sys/kernel/random/uuid) ;;
    --server) HAS_SERVER=1 ;;
  esac
done
[[ $HAS_SERVER == 1 ]] || CMD+=(--server 127.0.0.1 --port 25565)
mkdir -p "$GAMEDIR"; cd "$GAMEDIR"
# Sem placa de som: backend "null" do OpenAL Soft. E2E_ALSOFT_DRIVERS=wave (com ALSOFT_CONF) grava a mixagem.
exec env AKASHICFM_E2E="$SCEN" ALSOFT_DRIVERS="${E2E_ALSOFT_DRIVERS:-null}" LIBGL_ALWAYS_SOFTWARE=1 \
  xvfb-run -a -s "-screen 0 1280x720x24" "${CMD[@]}" < /dev/null
