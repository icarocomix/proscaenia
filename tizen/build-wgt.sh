#!/usr/bin/env bash
# Pacchetto e installazione della prova tecnica Proscaenia su Samsung TV (Tizen).
#
# Uso:
#   ./build-wgt.sh                        # crea solo il pacchetto firmato .wgt
#   ./build-wgt.sh --tv 192.168.1.50      # crea, installa e avvia sulla TV (Developer Mode attiva)
#   ./build-wgt.sh --profile mioProfilo   # profilo di firma del Certificate Manager (default: proscaenia)
#
# Prerequisiti (vedi README.md): Tizen Studio con estensione TV, profilo di certificato Samsung
# creato nel Certificate Manager con il DUID della TV, Developer Mode attiva sulla TV.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ID="PrScTizen0.ProscaeniaProva"
PROFILE="proscaenia"
TV_IP=""
TIZEN_HOME="${TIZEN_HOME:-$HOME/tizen-studio}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tv) TV_IP="$2"; shift 2 ;;
        --profile) PROFILE="$2"; shift 2 ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "Opzione sconosciuta: $1" >&2; exit 2 ;;
    esac
done

TIZEN="$TIZEN_HOME/tools/ide/bin/tizen"
SDB="$TIZEN_HOME/tools/sdb"
for tool in "$TIZEN" "$SDB"; do
    if [[ ! -x "$tool" ]]; then
        echo "Strumento non trovato: $tool (impostare TIZEN_HOME sulla cartella di Tizen Studio)" >&2
        exit 1
    fi
done

# Controllo leggero delle risorse: il PC è condiviso con altre sessioni e il CLI Tizen avvia una JVM.
echo "== Risorse =="
free -m | awk 'NR<=2'
uptime

# Impacchetto solo i file dell'app, non script e documentazione, da una cartella di appoggio pulita.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$SCRIPT_DIR"/config.xml "$SCRIPT_DIR"/index.html "$SCRIPT_DIR"/icon.png "$STAGE"/
cp -r "$SCRIPT_DIR"/css "$SCRIPT_DIR"/js "$SCRIPT_DIR"/lib "$STAGE"/

echo "== Pacchetto (profilo di firma: $PROFILE) =="
"$TIZEN" package -t wgt -s "$PROFILE" -- "$STAGE"
WGT="$(ls -1 "$STAGE"/*.wgt | head -n 1)"
cp "$WGT" "$SCRIPT_DIR"/
WGT_OUT="$SCRIPT_DIR/$(basename "$WGT")"
echo "Pacchetto creato: $WGT_OUT"

if [[ -z "$TV_IP" ]]; then
    exit 0
fi

echo "== Installazione su $TV_IP =="
"$SDB" connect "$TV_IP:26101"
TARGET="$("$SDB" devices | awk -v ip="$TV_IP" '$1 ~ ip {print $3}' | head -n 1)"
if [[ -z "$TARGET" ]]; then
    echo "TV non collegata: verificare Developer Mode (IP del PC impostato) e riavvio della TV" >&2
    "$SDB" devices >&2
    exit 1
fi
"$TIZEN" install -n "$WGT_OUT" -t "$TARGET"
"$TIZEN" run -p "$APP_ID" -t "$TARGET"
echo "App avviata sulla TV ($TARGET)."
