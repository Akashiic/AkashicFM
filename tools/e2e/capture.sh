#!/usr/bin/env bash
# Captura a linha de comando java de uma task de execução do Gradle (runServer21, runClient21, runServer,
# runClient) para os outros scripts poderem subir servidor e clientes direto, vários ao mesmo tempo.
# Uso: tools/e2e/capture.sh runServer21|runClient21|runServer|runClient
set -euo pipefail
TASK=${1:?task}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/build/e2e"; mkdir -p "$OUT"
MAIN=GradleStartServer; [[ $TASK == runClient* ]] && MAIN=GradleStart
cd "$ROOT"
if [[ $TASK == runClient* ]]; then
  ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1 setsid xvfb-run -a -s "-screen 0 1280x720x24" \
    ./gradlew "$TASK" --no-daemon --no-configuration-cache > "$OUT/capture-$TASK.log" 2>&1 &
else
  setsid ./gradlew "$TASK" --no-daemon --no-configuration-cache > "$OUT/capture-$TASK.log" 2>&1 &
fi
for _ in $(seq 1 300); do
  for pid in $(pgrep -x java || true); do
    args=$(tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null || true)
    if grep -qx "$MAIN" <<< "$args"; then
      printf '%s\n' "$args" > "$OUT/$TASK.cmd"
      kill "$pid"
      echo "capturado: $OUT/$TASK.cmd"
      exit 0
    fi
  done
  sleep 1
done
echo "não achei o processo java de $TASK (veja $OUT/capture-$TASK.log)" >&2
exit 1
