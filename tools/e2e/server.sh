#!/usr/bin/env bash
# Sobe o servidor dev direto, com o jar mais novo de build/libs. Uso: tools/e2e/server.sh [runServer21|runServer]
set -euo pipefail
TASK=${1:-runServer21}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CMDFILE="$ROOT/build/e2e/$TASK.cmd"
[[ -f $CMDFILE ]] || { echo "rode antes: tools/e2e/capture.sh $TASK" >&2; exit 1; }
JAR=$(ls -t "$ROOT"/build/libs/*-dev-preshadow.jar | head -1)
mkdir -p "$ROOT/run/server"; cd "$ROOT/run/server"
grep -q '^eula=true' eula.txt 2>/dev/null || echo 'eula=true' > eula.txt
[[ -f server.properties ]] || printf 'online-mode=false\nlevel-type=FLAT\nspawn-protection=0\nspawn-monsters=false\nspawn-animals=false\nview-distance=4\n' > server.properties
mapfile -t CMD < <(sed -E "s#[^:]*/build/libs/[^:]*-dev-preshadow\.jar#$JAR#" "$CMDFILE")
exec env AKASHICFM_E2E=server "${CMD[@]}" < /dev/null
