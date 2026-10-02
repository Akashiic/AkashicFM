#!/usr/bin/env bash
# Soak E2E (~11 min): uma rádio tocando com uma caixa ligando/desligando a cada 30 s e o sistema de som
# recarregado a cada 2,5 min; confere vazamento de objetos AL, threads e heap. Sai com 1 se falhar.
# Uso: tools/e2e/run-soak.sh [runServer21 runClient21 | runServer runClient]
set -uo pipefail
STASK=${1:-runServer21}; CTASK=${2:-runClient21}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LOGS="$ROOT/build/e2e/logs"; mkdir -p "$LOGS"
OFFLINE_UUID=$(python3 -c 'import hashlib,uuid; h=bytearray(hashlib.md5(b"OfflinePlayer:Developer").digest()); h[6]=(h[6]&15)|48; h[8]=(h[8]&63)|128; print(uuid.UUID(bytes=bytes(h)))')
mkdir -p "$ROOT/run/server"; rm -rf "$ROOT/run/server/world"
printf '[{"uuid":"%s","name":"Developer","level":4}]\n' "$OFFLINE_UUID" > "$ROOT/run/server/ops.json"
setsid "$ROOT/tools/e2e/server.sh" "$STASK" > "$LOGS/soak-server.log" 2>&1 &
for _ in $(seq 1 180); do grep -q 'Done (' "$LOGS/soak-server.log" && break; sleep 1; done
setsid "$ROOT/tools/e2e/client.sh" Developer "$ROOT/run/client" soak "$CTASK" > "$LOGS/soak.log" 2>&1 &
for _ in $(seq 1 1200); do grep -q '\[E2E\] DONE' "$LOGS/soak.log" && break; sleep 1; done
sleep 5
for pid in $(pgrep -x java || true); do
  tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -qx GradleStartServer && kill -TERM "$pid"
done
grep -h '\[E2E\] \(PASS\|FAIL\|DONE\|soak\)' "$LOGS/soak.log" | sed 's/.*\[E2E\]/[E2E]/'
if grep -q '\[E2E\] FAIL' "$LOGS/soak.log" || ! grep -q '\[E2E\] DONE' "$LOGS/soak.log"; then echo "SOAK: FALHOU"; exit 1; fi
echo "SOAK: OK"
