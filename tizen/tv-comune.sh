#!/usr/bin/env bash
# Funzioni comuni agli script di installazione su Samsung TV (da includere con `source`).
# Non va eseguito direttamente e non entra nel pacchetto .wgt (build-wgt.sh copia solo i file dell'app).

TV_COMUNE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Cartella con i dati locali della TV (IP e DUID): esclusa da git, perché legata all'apparecchio.
TV_STATO_DIR="$TV_COMUNE_DIR/.tv-locale"
TV_PORTA_SDB=26101

TIZEN_HOME="${TIZEN_HOME:-$HOME/tizen-studio}"
TIZEN="$TIZEN_HOME/tools/ide/bin/tizen"
SDB="$TIZEN_HOME/tools/sdb"
CERT_MANAGER="$TIZEN_HOME/tools/certificate-manager/certificate-manager"

# Ricavo la cartella dati dal file sdk.info dell'installazione, perché può non essere quella predefinita.
tizen_data_dir() {
    local dir=""
    if [[ -f "$TIZEN_HOME/sdk.info" ]]; then
        dir="$(sed -n 's/^TIZEN_SDK_DATA_PATH=//p' "$TIZEN_HOME/sdk.info" | head -n 1)"
    fi
    echo "${dir:-$HOME/tizen-studio-data}"
}

PROFILI_XML="$(tizen_data_dir)/profile/profiles.xml"

ok()     { printf '  \033[32m✔\033[0m %s\n' "$*"; }
avviso() { printf '  \033[33m!\033[0m %s\n' "$*"; }
errore() { printf '  \033[31m✘\033[0m %s\n' "$*" >&2; }
titolo() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

# Chiedo una conferma s/N; in assenza di terminale interattivo la risposta è "no".
conferma() {
    local risposta=""
    if [[ ! -t 0 ]]; then
        return 1
    fi
    read -r -p "  $1 [s/N] " risposta
    [[ "$risposta" =~ ^[sSyY]$ ]]
}

richiedi_strumenti_tizen() {
    local tool
    for tool in "$TIZEN" "$SDB"; do
        if [[ ! -x "$tool" ]]; then
            errore "Strumento non trovato: $tool (impostare TIZEN_HOME sulla cartella di Tizen Studio)"
            exit 1
        fi
    done
}

# Descrivo un profilo di firma leggendo profiles.xml senza mai stampare le password.
# Output (righe chiave=valore): esiste, attivo, autore, distributore, tipo (samsung|tizen|incompleto).
# Il profilo Tizen generico usa il distributore pubblico tizen-distributor-signer, che una TV
# Samsung in commercio rifiuta; quello Samsung punta a un distributore emesso per i DUID scelti.
info_profilo() {
    python3 -I - "$PROFILI_XML" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
percorso, nome = sys.argv[1], sys.argv[2]
try:
    radice = ET.parse(percorso).getroot()
except (OSError, ET.ParseError):
    print("esiste=no"); sys.exit(0)
print("attivo=" + radice.get("active", ""))
for prof in radice.findall("profile"):
    if prof.get("name") != nome:
        continue
    voci = {p.get("distributor"): p for p in prof.findall("profileitem")}
    autore = voci.get("0").get("key", "") if voci.get("0") is not None else ""
    distr = voci.get("1").get("key", "") if voci.get("1") is not None else ""
    if not autore or not distr:
        tipo = "incompleto"
    elif "tizen-distributor-signer" in distr or "/certificate-generator/" in distr:
        tipo = "tizen"
    else:
        tipo = "samsung"
    print("esiste=si"); print("autore=" + autore); print("distributore=" + distr); print("tipo=" + tipo)
    sys.exit(0)
print("esiste=no")
PY
}

# Legge un campo dall'output di info_profilo.
campo() { sed -n "s/^$1=//p" <<<"$2" | head -n 1; }

# Leggo il DUID con la stessa sequenza del Certificate Manager (sdblib): prima la capability,
# poi i comandi di shell riservati dei firmware Samsung.
leggi_duid() {
    local seriale="$1" duid=""
    duid="$("$SDB" -s "$seriale" capability 2>/dev/null | tr -d '\r' | sed -n 's/^duid://p' | head -n 1)"
    if [[ -z "$duid" || "$duid" == "unknown" ]]; then
        local cmd
        for cmd in "0 getduid" "0 getduidgadget" "/opt/etc/duid-gadget 2> /dev/null"; do
            duid="$("$SDB" -s "$seriale" shell $cmd 2>/dev/null | tr -d '\r' | grep -v '^[[:space:]]*$' | tail -n 1 | xargs)"
            # Scarto risposte d'errore della shell: un DUID è una stringa alfanumerica senza spazi.
            if [[ -n "$duid" && "$duid" =~ ^[A-Za-z0-9._-]+$ ]]; then
                break
            fi
            duid=""
        done
    fi
    [[ "$duid" == "unknown" ]] && duid=""
    echo "$duid"
}

# Stato della TV in `sdb devices` (device, offline, unauthorized...) per il seriale IP:porta.
stato_sdb() {
    "$SDB" devices 2>/dev/null | tr -d '\r' | awk -v s="$1" '$1 == s {print $2}' | head -n 1
}

# Nome del target come lo vuole il CLI `tizen` (terza colonna di `sdb devices`).
nome_target_sdb() {
    "$SDB" devices 2>/dev/null | tr -d '\r' | awk -v s="$1" '$1 == s {print $3}' | head -n 1
}
