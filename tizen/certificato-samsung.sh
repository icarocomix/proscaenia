#!/usr/bin/env bash
# PASSO 3 — Profilo di firma Samsung TV (certificato autore + distributore con il DUID della TV).
#
# Uso:
#   ./certificato-samsung.sh                      # profilo "proscaenia" (quello usato da build-wgt.sh)
#   ./certificato-samsung.sh --profile altroNome  # profilo con un altro nome
#
# Prima eseguire ./tv-connetti.sh <IP TV> lasciando la TV accesa: così il Certificate Manager
# propone da solo il DUID. Lo script controlla i prerequisiti, mette da parte l'eventuale profilo
# Tizen generico con lo stesso nome (con backup), apre il Certificate Manager con la guida dei clic
# da fare e, alla chiusura, verifica che il profilo creato sia davvero Samsung e contenga il DUID.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tv-comune.sh"

PROFILO="proscaenia"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --profile) PROFILO="$2"; shift 2 ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) errore "Opzione sconosciuta: $1"; exit 2 ;;
    esac
done

titolo "1/5 Prerequisiti"
richiedi_strumenti_tizen
if [[ ! -x "$CERT_MANAGER" ]]; then
    errore "Certificate Manager non trovato: $CERT_MANAGER"
    exit 1
fi
if [[ ! -d "$TIZEN_HOME/.info/cert-add-on" ]]; then
    errore "Samsung Certificate Extension non installata. Installarla con:"
    echo "    $TIZEN_HOME/package-manager/package-manager-cli.bin install cert-add-on" >&2
    exit 1
fi
ok "Samsung Certificate Extension installata"
if pgrep -f "tools/certificate-manager" >/dev/null 2>&1; then
    errore "Il Certificate Manager è già aperto: chiuderlo e rilanciare lo script."
    exit 1
fi
# Il login Samsung apre il browser di sistema e riceve la risposta su localhost:4794.
if ! command -v xdg-open >/dev/null 2>&1; then
    errore "xdg-open assente: serve per aprire la pagina di login Samsung nel browser."
    exit 1
fi
if ss -ltn 2>/dev/null | grep -q ':4794 '; then
    errore "La porta locale 4794 (ritorno del login Samsung) è già occupata da un altro processo:"
    ss -ltnp 2>/dev/null | grep ':4794 ' >&2 || true
    exit 1
fi
ok "Browser di sistema e porta di ritorno 4794 disponibili"

DUID=""
[[ -f "$TV_STATO_DIR/duid" ]] && DUID="$(cat "$TV_STATO_DIR/duid")"
TV_IP=""
[[ -f "$TV_STATO_DIR/ip" ]] && TV_IP="$(cat "$TV_STATO_DIR/ip")"
if [[ -z "$DUID" ]]; then
    avviso "DUID della TV non ancora letto: conviene eseguire prima ./tv-connetti.sh <IP TV>."
    conferma "Proseguire comunque inserendo il DUID a mano nel Certificate Manager?" || exit 1
else
    ok "DUID della TV: $DUID"
    if [[ "$(stato_sdb "$TV_IP:$TV_PORTA_SDB")" == "device" ]]; then
        ok "TV collegata ($TV_IP): il Certificate Manager proporrà il DUID automaticamente"
    else
        avviso "TV non collegata in questo momento: il DUID andrà incollato a mano (è qui sopra)."
    fi
fi

titolo "2/5 Profilo esistente \"$PROFILO\""
INFO="$(info_profilo "$PROFILO")"
if [[ "$(campo esiste "$INFO")" == "si" ]]; then
    TIPO="$(campo tipo "$INFO")"
    if [[ "$TIPO" == "samsung" ]]; then
        avviso "Esiste già un profilo Samsung \"$PROFILO\" (distributore: $(campo distributore "$INFO"))."
        conferma "Sostituirlo con uno nuovo (es. se la TV non è tra i DUID)?" || { echo "Nessuna modifica."; exit 0; }
    else
        avviso "Il profilo \"$PROFILO\" è di tipo $TIPO: non installabile su una TV Samsung in commercio."
    fi
    # Il Certificate Manager non accetta un nome già usato: tolgo la voce da profiles.xml dopo un
    # backup. I file dei certificati restano dove sono, viene rimosso solo il riferimento.
    BACKUP="$PROFILI_XML.bak-$(date +%Y%m%d-%H%M%S)"
    if conferma "Rimuovo il profilo \"$PROFILO\" da profiles.xml (backup in $(basename "$BACKUP"))?"; then
        cp -p "$PROFILI_XML" "$BACKUP"
        python3 -I - "$PROFILI_XML" "$PROFILO" <<'PY'
import re, sys
percorso, nome = sys.argv[1], sys.argv[2]
testo = open(percorso, encoding="utf-8").read()
blocco = re.compile(r'<profile name="%s">.*?</profile>\s*' % re.escape(nome), re.S)
nuovo, n = blocco.subn("", testo, count=1)
if n != 1:
    sys.exit("profilo non trovato nel file")
# Se era il profilo attivo, lascio l'attivo vuoto: lo imposterà il Certificate Manager.
nuovo = nuovo.replace('active="%s"' % nome, 'active=""', 1)
open(percorso, "w", encoding="utf-8").write(nuovo)
PY
        ok "Profilo rimosso; backup: $BACKUP"
    else
        avviso "Profilo lasciato: nel Certificate Manager andrà eliminato a mano (icona cestino) prima di crearne uno con lo stesso nome."
    fi
else
    ok "Nessun profilo \"$PROFILO\": verrà creato da zero"
fi

titolo "3/5 Clic da fare nel Certificate Manager"
cat <<EOF
  1. Pulsante "+" (Create certificate profile)
  2. Tipo: Samsung → Next
  3. Device type: TV → Next
  4. Certificate profile name: $PROFILO  (opzione "Create a new certificate profile") → Next
  5. Author certificate: "Create a new author certificate" → Next
       Author name: a piacere (es. Proscaenia); Password e conferma: a piacere (annotarla)
       → Next
  6. Login Samsung: si apre il browser di sistema → accedere con l'account Samsung →
       la pagina conferma il successo; tornare al Certificate Manager (se chiede ancora
       il login, premere di nuovo "Sign in")
  7. Author certificate creato → Next
  8. Distributor certificate: "Create a new distributor certificate" → Next
       Privilege: Public
       DUID: deve comparire ${DUID:-quello della TV} (se manca: "Add" / "+" e incollarlo)
       → Next → Finish
  9. Chiudere il Certificate Manager: lo script riprende con la verifica.
EOF
echo
read -r -p "  Premere Invio per aprire il Certificate Manager..." _ || true

titolo "4/5 Certificate Manager in esecuzione (chiuderlo al termine)"
# L'SWT del Certificate Manager (2017) funziona meglio via XWayland che in Wayland nativo.
# Lo avvio dalla cartella dati di Tizen Studio: Eclipse crea il proprio "workspace/" nella
# directory corrente e non deve finire nel progetto.
CM_DIR="$(tizen_data_dir)/certificate-manager"
mkdir -p "$CM_DIR"
( cd "$CM_DIR" && GDK_BACKEND=x11 "$CERT_MANAGER" >"$CM_DIR/ultimo-avvio.log" 2>&1 ) || true

titolo "5/5 Verifica del profilo"
INFO="$(info_profilo "$PROFILO")"
if [[ "$(campo esiste "$INFO")" != "si" ]]; then
    errore "Profilo \"$PROFILO\" non trovato in $PROFILI_XML: la procedura non è stata completata."
    echo "  Rilanciare ./certificato-samsung.sh" >&2
    exit 1
fi
TIPO="$(campo tipo "$INFO")"
AUTORE="$(campo autore "$INFO")"
DISTR="$(campo distributore "$INFO")"
if [[ "$TIPO" != "samsung" ]]; then
    errore "Il profilo \"$PROFILO\" è di tipo $TIPO, non Samsung (distributore: ${DISTR:-assente})."
    echo "  Al passo 2 della procedura va scelto \"Samsung\", non \"Tizen\". Rilanciare lo script." >&2
    exit 1
fi
for f in "$AUTORE" "$DISTR"; do
    if [[ ! -f "$f" ]]; then
        errore "File del certificato mancante: $f"
        exit 1
    fi
done
ok "Profilo Samsung \"$PROFILO\" presente"
ok "Autore:       $AUTORE"
ok "Distributore: $DISTR"

# Il distributore Samsung è accompagnato da device-profile.xml con l'elenco dei DUID autorizzati.
DEVPROF="$(dirname "$DISTR")/device-profile.xml"
if [[ -n "$DUID" && -f "$DEVPROF" ]]; then
    if grep -q -- "$DUID" "$DEVPROF"; then
        ok "Il DUID $DUID è nel certificato distributore"
    else
        errore "Il DUID $DUID NON compare in $DEVPROF: la TV rifiuterebbe l'installazione."
        echo "  Rilanciare lo script e, nel distributore, aggiungere il DUID della TV." >&2
        exit 1
    fi
elif [[ -n "$DUID" ]]; then
    avviso "device-profile.xml non trovato accanto al distributore: verifica del DUID non possibile."
fi

cat <<EOF

Fatto. Ultimo passo (TV accesa, Developer Mode attiva):
  ./build-wgt.sh --tv ${TV_IP:-<IP della TV>}$( [[ "$PROFILO" != "proscaenia" ]] && echo " --profile $PROFILO" )
EOF
