#!/usr/bin/env bash
# yt-dlp falso para o E2E do iPod: a mesma linha de comando que o mod usa (opções, "--" e o alvo), respostas fixas e
# áudio de arquivos públicos curtos já usados no E2E. Determinístico e sem depender do SoundCloud/YouTube.
#   -J ... -- <link|scsearchN:texto>  metadados (set do SoundCloud, vídeo do YouTube, busca)
#   -j -f <formato> ... -- <página>   a mídia: JSON com a URL direta em "url"
# A primeira resolução da "faixa-a" devolve uma URL que dá 404 (como uma URL assinada que expirou): o mod tem que
# renovar. A "faixa-drm" falha como faixa protegida. A "faixa-longa" (160 s) serve para a pausa. Cada chamada fica registrada em $TMPDIR/fake-yt-dlp.log.
set -u
TRACK1="https://actions.google.com/sounds/v1/alarms/alarm_clock.ogg"
TRACK2="https://actions.google.com/sounds/v1/cartoon/clang_and_wobble.ogg"
LONG="https://actions.google.com/sounds/v1/ambiences/coffee_shop.ogg"
EXPIRED="https://actions.google.com/sounds/v1/alarms/akashicfm_e2e_expirada.ogg"
STATE="${TMPDIR:-/tmp}/fake-yt-dlp"
mkdir -p "$STATE"

mode=""; target=""; seen_dashdash=0
for a in "$@"; do
  if [[ $seen_dashdash == 1 ]]; then target="$a"; break; fi
  case "$a" in
    -J) mode="info" ;;
    -j) mode="media" ;;
    --) seen_dashdash=1 ;;
  esac
done
echo "$(date +%T) $mode $target" >> "$STATE/../fake-yt-dlp.log"
[[ $seen_dashdash == 1 && -n $target ]] || { echo "ERROR: alvo ausente (sem --)" >&2; exit 2; }

media() { # página url título duração
  printf '{"_type":"video","extractor_key":"Soundcloud","id":"%s","webpage_url":"%s","title":"%s","uploader":"E2E","duration":%s,"url":"%s","format_id":"http_mp3_0_0"}\n' \
    "$(basename "$1")" "$1" "$3" "$4" "$2"
}

if [[ $mode == info ]]; then
  case "$target" in
    https://soundcloud.com/e2e/sets/lista)
      cat <<'EOF'
{"_type":"playlist","extractor_key":"SoundcloudSet","title":"Lista E2E","uploader":"E2E","entries":[
 {"_type":"url","ie_key":"Soundcloud","url":"https://api.soundcloud.com/tracks/1","webpage_url":"https://soundcloud.com/e2e/faixa-a","title":"Faixa A","uploader":"E2E","duration":6.0},
 {"_type":"url","ie_key":"Soundcloud","url":"https://api.soundcloud.com/tracks/2","webpage_url":"https://soundcloud.com/e2e/faixa-drm","title":"Faixa Protegida","uploader":"E2E","duration":120.0},
 {"_type":"url","ie_key":"Soundcloud","url":"https://api.soundcloud.com/tracks/3","webpage_url":"https://soundcloud.com/e2e/faixa-c","title":"Faixa C","uploader":"E2E","duration":3.0}
]}
EOF
      ;;
    https://soundcloud.com/e2e/faixa-longa)
      echo '{"_type":"video","extractor_key":"Soundcloud","id":"4","webpage_url":"https://soundcloud.com/e2e/faixa-longa","title":"Cafe","uploader":"E2E","duration":160.2}'
      ;;
    https://www.youtube.com/watch\?v=e2eVideo001)
      echo '{"_type":"video","extractor_key":"Youtube","id":"e2eVideo001","title":"E2E Band - Song One (Official Video)","uploader":"E2E Band","channel":"E2E Band","duration":7,"live_status":"not_live"}'
      ;;
    scsearch*)
      cat <<'EOF'
{"_type":"playlist","extractor_key":"SoundcloudSearch","title":"busca","entries":[
 {"_type":"url","ie_key":"Soundcloud","webpage_url":"https://soundcloud.com/e2e/song-one-preview","title":"Song One","uploader":"E2E Band","duration":30.0},
 {"_type":"url","ie_key":"Soundcloud","webpage_url":"https://soundcloud.com/e2e/song-one-remix","title":"E2E Band - Song One (Remix)","uploader":"DJ","duration":6.0},
 {"_type":"url","ie_key":"Soundcloud","webpage_url":"https://soundcloud.com/e2e/song-one","title":"E2E Band - Song One","uploader":"E2E Band","duration":6.0}
]}
EOF
      ;;
    *)
      echo "ERROR: [generic] Unsupported URL: $target" >&2; exit 1 ;;
  esac
  exit 0
fi

if [[ $mode == media ]]; then
  case "$target" in
    https://soundcloud.com/e2e/faixa-a|https://api.soundcloud.com/tracks/1)
      n=$(( $(cat "$STATE/faixa-a" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$STATE/faixa-a"
      if [[ $n == 1 ]]; then media "$target" "$EXPIRED" "Faixa A" 6.0; else media "$target" "$TRACK1" "Faixa A" 6.0; fi ;;
    https://soundcloud.com/e2e/faixa-drm|https://api.soundcloud.com/tracks/2)
      echo "ERROR: [soundcloud] 2: This video is DRM protected" >&2; exit 1 ;;
    https://soundcloud.com/e2e/faixa-c|https://api.soundcloud.com/tracks/3)
      media "$target" "$TRACK2" "Faixa C" 3.0 ;;
    https://soundcloud.com/e2e/faixa-longa)
      media "$target" "$LONG" "Cafe" 160.2 ;;
    https://soundcloud.com/e2e/song-one)
      media "$target" "$TRACK2" "E2E Band - Song One" 6.0 ;;
    *)
      echo "ERROR: [generic] Unsupported URL: $target" >&2; exit 1 ;;
  esac
  exit 0
fi
echo "ERROR: modo desconhecido" >&2
exit 2
