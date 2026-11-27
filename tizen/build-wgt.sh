#!/usr/bin/env bash
# PASSO 4 — Pacchetto e installazione della prova tecnica Proscaenia su Samsung TV (Tizen).
#
# Uso:
#   ./build-wgt.sh                        # crea solo il pacchetto firmato .wgt
#   ./build-wgt.sh --tv 192.168.1.50      # crea, installa e avvia sulla TV (Developer Mode attiva)
#   ./build-wgt.sh --tv                   # come sopra, con l'IP salvato da tv-connetti.sh
#   ./build-wgt.sh --profile mioProfilo   # profilo di firma del Certificate Manager (default: proscaenia)
#
# Prerequisiti (vedi README.md): ./tv-connetti.sh <IP TV> e ./certificato-samsung.sh eseguiti.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/tv-comune.sh"

APP_ID="PrScTizen0.ProscaeniaProva"
PROFILE="proscaenia"
TV_IP=""
INSTALLA=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tv)
            INSTALLA=1
            # L'IP è facoltativo: senza, uso quello salvato da tv-connetti.sh.
            if [[ $# -ge 2 && "$2" != --* ]]; then TV_IP="$2"; shift 2; else shift; fi ;;
        --profile) PROFILE="$2"; shift 2 ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "Opzione sconosciuta: $1" >&2; exit 2 ;;
    esac
done
if [[ "$INSTALLA" == 1 && -z "$TV_IP" ]]; then
    TV_IP="$(cat "$TV_STATO_DIR/ip" 2>/dev/null || true)"
    if [[ -z "$TV_IP" ]]; then
        errore "IP della TV non indicato e non salvato: ./build-wgt.sh --tv <IP TV> (o prima ./tv-connetti.sh)."
        exit 2
    fi
fi

richiedi_strumenti_tizen

# Controllo il profilo prima di impacchettare: un profilo Tizen generico produce un pacchetto
# che la TV rifiuta solo a installazione in corso, con un messaggio poco chiaro.
INFO="$(info_profilo "$PROFILE")"
if [[ "$(campo esiste "$INFO")" != "si" ]]; then
    errore "Profilo di firma \"$PROFILE\" inesistente: eseguire ./certificato-samsung.sh"
    exit 1
fi
TIPO="$(campo tipo "$INFO")"
if [[ "$TIPO" != "samsung" ]]; then
    if [[ "$INSTALLA" == 1 ]]; then
        errore "Il profilo \"$PROFILE\" è di tipo $TIPO: la TV rifiuterebbe il pacchetto. Eseguire ./certificato-samsung.sh"
        exit 1
    fi
    avviso "Profilo \"$PROFILE\" di tipo $TIPO: il pacchetto non sarà installabile su una TV Samsung."
fi

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

if [[ "$INSTALLA" != 1 ]]; then
    exit 0
fi

SERIALE="$TV_IP:$TV_PORTA_SDB"
echo "== Installazione su $TV_IP =="
if [[ "$(stato_sdb "$SERIALE")" != "device" ]]; then
    "$SDB" connect "$SERIALE" | tr -d '\r'
    sleep 1
fi
TARGET="$(nome_target_sdb "$SERIALE")"
if [[ "$(stato_sdb "$SERIALE")" != "device" || -z "$TARGET" ]]; then
    errore "TV non collegata: eseguire ./tv-connetti.sh $TV_IP (indica cosa controllare sulla TV)."
    "$SDB" devices >&2
    exit 1
fi

# Il distributore Samsung vale solo per i DUID che contiene: verifico prima di installare.
DEVPROF="$(dirname "$(campo distributore "$INFO")")/device-profile.xml"
DUID="$(leggi_duid "$SERIALE")"
if [[ -n "$DUID" && -f "$DEVPROF" ]] && ! grep -q -- "$DUID" "$DEVPROF"; then
    errore "Il DUID della TV collegata ($DUID) non è nel certificato distributore del profilo \"$PROFILE\"."
    echo "  Rieseguire ./tv-connetti.sh $TV_IP e ./certificato-samsung.sh" >&2
    exit 1
fi

# Il CLI `tizen install` può terminare con codice 0 anche se la TV rifiuta il pacchetto: leggo
# l'output per riconoscere l'esito e dare un'indicazione mirata.
ESITO="$("$TIZEN" install -n "$WGT_OUT" -t "$TARGET" 2>&1 | tee /dev/stderr || true)"
if grep -qiE "install failed|processing failed" <<<"$ESITO" \
    || ! grep -qiE "successfully installed|Installed the package" <<<"$ESITO"; then
    errore "Installazione non riuscita."
    if grep -qiE "certificate|signature|author|distributor" <<<"$ESITO"; then
        echo "  Problema di firma: rieseguire ./certificato-samsung.sh (profilo Samsung con il DUID della TV)." >&2
    elif grep -qi "privilege" <<<"$ESITO"; then
        echo "  Privilegio non consentito dal firmware: togliere da config.xml la riga indicata e ripetere." >&2
    fi
    exit 1
fi
"$TIZEN" run -p "$APP_ID" -t "$TARGET"
echo "App avviata sulla TV ($TARGET)."
