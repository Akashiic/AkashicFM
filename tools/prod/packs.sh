# Conjuntos de mods de produção do teste, com as versões dos manifestos de release do GTNH (DreamAssemblerXXL,
# releases/manifests/<versão>.json). Lido pelos outros scripts de tools/prod; ROOT já definido.
GTNHLIB=$(sed -nE 's/.*GTNHLib:([0-9.]+).*/\1/p' "$ROOT/dependencies.gradle" | head -1)
MIN=$(sed -nE 's/.*MIN_GTNHLIB = "([0-9.]+)".*/\1/p' "$ROOT/src/main/java/com/akashiic/fm/AkashicFM.java")
BELOW=0.5.22 # a anterior à mínima: o Forge tem que recusar

# pack_mods NOME -> "Mod:versão ..."
pack_mods() {
  case $1 in
    gtnh-2.9) echo "GTNHLib:$GTNHLIB UniMixins:0.3.1 Hodgepodge:2.7.206 OpenComputers:1.12.64-GTNH" ;; # ~2.9.0-RC-1
    gtnh-2.8) echo "GTNHLib:0.7.10 UniMixins:0.1.23 Hodgepodge:2.6.112 OpenComputers:1.11.20-GTNH" ;;  # 2.8.4
    gtnh-2.7) echo "GTNHLib:0.5.23 UniMixins:0.1.19 Hodgepodge:2.5.90 OpenComputers:1.10.30-GTNH" ;;   # 2.7.4
    min) echo "GTNHLib:$MIN UniMixins:0.1.19" ;;
    refuse) echo "GTNHLib:$BELOW UniMixins:0.1.19" ;;
    *) return 1 ;;
  esac
}

# mod_file Mod:versão -> nome do jar em build/prod/mods
mod_file() {
  local m=${1%%:*} v=${1#*:}
  case $m in
    UniMixins) echo "+unimixins-all-1.7.10-$v.jar" ;;
    *) echo "$m-$v.jar" ;;
  esac
}

# mod_url Mod:versão -> de onde baixar
mod_url() {
  local m=${1%%:*} v=${1#*:}
  case $m in
    UniMixins) echo "https://github.com/LegacyModdingMC/UniMixins/releases/download/$v/%2Bunimixins-all-1.7.10-$v.jar" ;;
    *) echo "https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/$m/$v/$m-$v.jar" ;;
  esac
}
