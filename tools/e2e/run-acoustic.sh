#!/usr/bin/env bash
# Prova acústica (~2 min): o OpenAL Soft do cliente grava a mixagem num WAV (backend "wave") enquanto o cenário
# "acoustic" toca segmentos (aberto, lã, vidro, aberto, sala de pedra com parada, parada no aberto); depois
# tools/e2e/analyze_acoustic.py mede nível, agudos e cauda de reverb de cada um. Sai com 1 se falhar.
# A fonte é chuva forte (estável; AKASHICFM_E2E_URL troca) e o WAV é float32 (sem dither: silêncio é zero).
# Uso: tools/e2e/run-acoustic.sh [runServer21 runClient21 | runServer runClient]  (AKASHICFM_E2E_NO_EFX=1: sem EFX)
set -uo pipefail
STASK=${1:-runServer21}; CTASK=${2:-runClient21}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LOGS="$ROOT/build/e2e/logs"; mkdir -p "$LOGS"
WAV="$ROOT/build/e2e/acoustic-$CTASK${AKASHICFM_E2E_NO_EFX:+-noefx}.wav"
CONF="$ROOT/build/e2e/alsoft-acoustic.conf"
rm -f "$WAV"
printf '[general]\ndrivers = wave\nchannels = stereo\nsample-type = float32\nfrequency = 48000\n\n[wave]\nfile = %s\n' "$WAV" > "$CONF"
OFFLINE_UUID=$(python3 -c 'import hashlib,uuid; h=bytearray(hashlib.md5(b"OfflinePlayer:Developer").digest()); h[6]=(h[6]&15)|48; h[8]=(h[8]&63)|128; print(uuid.UUID(bytes=bytes(h)))')
mkdir -p "$ROOT/run/server"; rm -rf "$ROOT/run/server/world"
printf '[{"uuid":"%s","name":"Developer","level":4}]\n' "$OFFLINE_UUID" > "$ROOT/run/server/ops.json"
setsid "$ROOT/tools/e2e/server.sh" "$STASK" > "$LOGS/acoustic-server.log" 2>&1 &
for _ in $(seq 1 180); do grep -q 'Done (' "$LOGS/acoustic-server.log" && break; sleep 1; done
E2E_ALSOFT_DRIVERS=wave ALSOFT_CONF="$CONF" \
  setsid "$ROOT/tools/e2e/client.sh" Developer "$ROOT/run/client" acoustic "$CTASK" > "$LOGS/acoustic.log" 2>&1 &
for _ in $(seq 1 600); do grep -q '\[E2E\] DONE' "$LOGS/acoustic.log" && break; sleep 1; done
# O cliente fecha sozinho (o OpenAL finaliza o cabeçalho do WAV ao fechar o dispositivo).
for _ in $(seq 1 60); do pgrep -f 'GradleStart$|GradleStart ' > /dev/null || break; sleep 1; done
for pid in $(pgrep -x java || true); do
  tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -qx GradleStartServer && kill -TERM "$pid"
done
grep -h '\[E2E\] \(PASS\|FAIL\|DONE\|acoustic-state\)' "$LOGS/acoustic.log" | sed 's/.*\[E2E\]/[E2E]/'
if grep -q '\[E2E\] FAIL' "$LOGS/acoustic.log" || ! grep -q '\[E2E\] DONE' "$LOGS/acoustic.log"; then
  echo "ACÚSTICA: roteiro falhou"; exit 1
fi
[[ -s $WAV ]] || { echo "ACÚSTICA: o OpenAL não gravou $WAV (backend wave indisponível?)"; exit 1; }
python3 "$ROOT/tools/e2e/analyze_acoustic.py" "$WAV" "$LOGS/acoustic.log" ${AKASHICFM_E2E_NO_EFX:+--no-efx}
