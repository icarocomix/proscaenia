#!/usr/bin/env bash
# PASSO 2 — Collegamento del PC alla Samsung TV in Developer Mode e lettura del DUID.
#
# Uso:
#   ./tv-connetti.sh <IP della TV>     # es. ./tv-connetti.sh 192.168.1.50
#   ./tv-connetti.sh                   # riusa l'ultimo IP salvato
#
# Cosa fa: verifica rete e porta di sviluppo della TV, esegue `sdb connect`, legge il DUID e lo
# salva in tizen/.tv-locale/ (insieme all'IP) per i passi successivi. Può essere rilanciato
# senza effetti collaterali tutte le volte che serve.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tv-comune.sh"

case "${1:-}" in
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
esac

TV_IP="${1:-}"
if [[ -z "$TV_IP" && -f "$TV_STATO_DIR/ip" ]]; then
    TV_IP="$(cat "$TV_STATO_DIR/ip")"
    echo "IP della TV non indicato: uso l'ultimo salvato ($TV_IP)."
fi
if [[ ! "$TV_IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
    errore "Indicare l'IP della TV (es. ./tv-connetti.sh 192.168.1.50)."
    echo "  Sulla TV: Impostazioni → Generali → Rete → Stato rete → Impostazioni IP." >&2
    exit 2
fi
SERIALE="$TV_IP:$TV_PORTA_SDB"

richiedi_strumenti_tizen

titolo "1/4 Rete"
# Indico l'IP del PC che la TV vede, cioè quello da inserire nella Developer Mode.
PC_IP="$(ip -4 route get "$TV_IP" 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -n 1)"
if [[ -n "$PC_IP" ]]; then
    ok "IP del PC verso la TV: $PC_IP  ← è questo il valore da inserire in Developer Mode (\"Host PC IP\")"
else
    avviso "Non riesco a determinare l'IP del PC verso $TV_IP: verificare che PC e TV siano sulla stessa rete."
fi
if ping -c 1 -W 2 "$TV_IP" >/dev/null 2>&1; then
    ok "La TV $TV_IP risponde al ping"
else
    avviso "La TV $TV_IP non risponde al ping (può essere normale: proseguo con il controllo della porta)."
fi

titolo "2/4 Porta di sviluppo $TV_PORTA_SDB"
# La porta si apre solo con Developer Mode attiva e dopo il riavvio completo della TV.
if timeout 4 bash -c "exec 3<>/dev/tcp/$TV_IP/$TV_PORTA_SDB" 2>/dev/null; then
    ok "Porta $TV_PORTA_SDB aperta: Developer Mode attiva"
else
    errore "Porta $TV_PORTA_SDB chiusa su $TV_IP. Sulla TV:"
    cat >&2 <<EOF
      1. App (Applicazioni) → digitare 1 2 3 4 5 col telecomando (tastierino numerico o a schermo)
      2. Developer mode: On; Host PC IP: ${PC_IP:-<IP del PC>} → OK
      3. Spegnere la TV del tutto (tenere premuto il tasto di accensione fino al riavvio,
         oppure staccare la spina per 30 s) e riaccenderla
      4. Rilanciare questo script
EOF
    exit 1
fi

titolo "3/4 Collegamento sdb"
"$SDB" start-server >/dev/null 2>&1 || true
# Un collegamento rimasto appeso da una sessione precedente impedisce il nuovo: lo chiudo prima.
"$SDB" disconnect "$SERIALE" >/dev/null 2>&1 || true
"$SDB" connect "$SERIALE" | tr -d '\r' | sed 's/^/  /'
STATO=""
for _ in 1 2 3 4 5; do
    STATO="$(stato_sdb "$SERIALE")"
    [[ "$STATO" == "device" ]] && break
    sleep 1
done
if [[ "$STATO" != "device" ]]; then
    errore "La TV non risulta collegata (stato: ${STATO:-assente})."
    "$SDB" devices >&2
    cat >&2 <<EOF
  Cause tipiche: in Developer Mode è impostato un IP del PC diverso da ${PC_IP:-quello attuale}
  (il PC ha cambiato indirizzo via DHCP), oppure la TV non è stata spenta del tutto dopo
  l'attivazione. Correggere sulla TV e rilanciare lo script.
EOF
    exit 1
fi
TARGET="$(nome_target_sdb "$SERIALE")"
ok "Collegata: $SERIALE (target: ${TARGET:-?})"
VERSIONE="$("$SDB" -s "$SERIALE" capability 2>/dev/null | tr -d '\r' | sed -n 's/^platform_version://p' | head -n 1)"
[[ -n "$VERSIONE" ]] && ok "Versione piattaforma Tizen: $VERSIONE"

titolo "4/4 DUID"
DUID="$(leggi_duid "$SERIALE")"
if [[ -z "$DUID" ]]; then
    errore "DUID non leggibile via sdb."
    echo "  Lo si può leggere sulla TV in: Impostazioni → Supporto → Informazioni su questa TV" >&2
    echo "  (voce \"DUID\" o \"Unique Device ID\") e inserirlo a mano nel Certificate Manager." >&2
    exit 1
fi
mkdir -p "$TV_STATO_DIR"
echo "$TV_IP" >"$TV_STATO_DIR/ip"
echo "$DUID" >"$TV_STATO_DIR/duid"
echo "${TARGET:-}" >"$TV_STATO_DIR/target"
ok "DUID: $DUID"
ok "Salvato in $TV_STATO_DIR/ (usato da certificato-samsung.sh e build-wgt.sh)"

cat <<EOF

Fatto. Prossimo passo (lasciando la TV accesa e collegata):
  ./certificato-samsung.sh
EOF
