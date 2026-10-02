#!/usr/bin/env bash
# Regressão E2E: servidor + cliente "main+peer" + cliente "peer" (Player2, não-op). Sai com 1 se algum
# passo falhar. Pré-requisitos: ./gradlew jar e tools/e2e/capture.sh runServer21 / runClient21.
# Para Java 8: tools/e2e/run-all.sh runServer runClient
set -uo pipefail
STASK=${1:-runServer21}; CTASK=${2:-runClient21}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LOGS="$ROOT/build/e2e/logs"; mkdir -p "$LOGS"
OFFLINE_UUID=$(python3 -c 'import hashlib,uuid; h=bytearray(hashlib.md5(b"OfflinePlayer:Developer").digest()); h[6]=(h[6]&15)|48; h[8]=(h[8]&63)|128; print(uuid.UUID(bytes=bytes(h)))')
mkdir -p "$ROOT/run/server"
# Mundo limpo a cada rodada: rádios e posições salvas de rodadas anteriores mudariam o resultado.
rm -rf "$ROOT/run/server/world"
printf '[{"uuid":"%s","name":"Developer","level":4}]\n' "$OFFLINE_UUID" > "$ROOT/run/server/ops.json"

setsid "$ROOT/tools/e2e/server.sh" "$STASK" > "$LOGS/server.log" 2>&1 &
for _ in $(seq 1 180); do grep -q 'Done (' "$LOGS/server.log" && break; sleep 1; done
setsid "$ROOT/tools/e2e/client.sh" Developer "$ROOT/run/client" main+peer "$CTASK" > "$LOGS/main.log" 2>&1 &
sleep 20
setsid "$ROOT/tools/e2e/client.sh" Player2 "$ROOT/run/client2" peer "$CTASK" > "$LOGS/peer.log" 2>&1 &
for _ in $(seq 1 900); do
  [[ $(cat "$LOGS/main.log" "$LOGS/peer.log" | grep -c '\[E2E\] DONE') -ge 2 ]] && break
  sleep 1
done
sleep 5
for pid in $(pgrep -x java || true); do
  tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -qx GradleStartServer && kill -TERM "$pid"
done
grep -h '\[E2E\] \(PASS\|FAIL\|DONE\)' "$LOGS/main.log" "$LOGS/peer.log" | sed 's/.*\[E2E\]/[E2E]/'
grep -h 'chunk do bloco' "$LOGS/server.log" | sed 's/.*\[E2E\]/[E2E] servidor:/'
FAILS=$(cat "$LOGS/main.log" "$LOGS/peer.log" | grep -c '\[E2E\] FAIL')
DONES=$(cat "$LOGS/main.log" "$LOGS/peer.log" | grep -c '\[E2E\] DONE')
if [[ $FAILS -gt 0 || $DONES -lt 2 ]]; then echo "E2E: FALHOU (falhas=$FAILS, cenários concluídos=$DONES)"; exit 1; fi
echo "E2E: OK"
