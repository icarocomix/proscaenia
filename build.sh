#!/usr/bin/env bash
#
# Compilazione di produzione di Proscaenia Player.
#
# Produce l'APK release minificato (R8 + rimozione risorse), con versione incrementata
# automaticamente da Gradle a ogni compilazione, e lo copia in dist/ con la versione nel nome e
# il relativo SHA-256. Prima di partire verifico che la macchina abbia memoria libera sufficiente e
# che non ci sia un'altra build Gradle in corso, perché questo PC ha risorse limitate e altre
# sessioni potrebbero già occuparle.
#
# Firma: se sono definite le variabili d'ambiente PROSCAENIA_KEYSTORE_FILE,
# PROSCAENIA_KEYSTORE_PASSWORD, PROSCAENIA_KEY_ALIAS e PROSCAENIA_KEY_PASSWORD uso il keystore di
# produzione, altrimenti Gradle firma con la chiave di debug locale (va bene per l'installazione
# diretta, non per la pubblicazione su uno store). A fine build indico quale firma è stata usata.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$PROJECT_DIR/dist"
MIN_FREE_MB=2500

RUN_TESTS=false
BUILD_BUNDLE=false
FORCE=false

usage() {
    cat <<EOF
Uso: ./build.sh [opzioni]

  -t, --test      esegue i test unitari JVM prima della compilazione
  -b, --bundle    produce anche l'Android App Bundle (.aab) per la pubblicazione su Play Store
  -f, --force     compila anche con poca memoria libera o un'altra build Gradle in corso
  -h, --help      mostra questo aiuto

L'APK firmato viene copiato in dist/proscaenia-<versione>.apk
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -t|--test) RUN_TESTS=true ;;
        -b|--bundle) BUILD_BUNDLE=true ;;
        -f|--force) FORCE=true ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opzione sconosciuta: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

log() { printf '\033[1;33m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mErrore:\033[0m %s\n' "$*" >&2; exit 1; }

# ----------------------------------------------------------------------------- controllo risorse

check_resources() {
    local available load cores other_builds
    available=$(free -m | awk '/^Mem:/ {print $7}')
    load=$(cut -d' ' -f1 /proc/loadavg)
    cores=$(nproc)
    log "Memoria disponibile: ${available} MB · carico medio: ${load} su ${cores} core"

    # Cerco altre build Gradle (wrapper o daemon) escludendo questo stesso script.
    other_builds=$(pgrep -f 'org.gradle.wrapper.GradleWrapperMain|org.gradle.launcher.daemon' || true)
    if [[ -n "$other_builds" ]]; then
        if [[ "$FORCE" == true ]]; then
            log "Attenzione: c'è già una build Gradle in corso (PID: $(echo "$other_builds" | tr '\n' ' ')), proseguo per --force"
        else
            fail "c'è già una build Gradle in corso (PID: $(echo "$other_builds" | tr '\n' ' ')). Attendi che termini o usa --force."
        fi
    fi

    if (( available < MIN_FREE_MB )); then
        if [[ "$FORCE" == true ]]; then
            log "Attenzione: meno di ${MIN_FREE_MB} MB liberi, proseguo per --force"
        else
            fail "memoria disponibile insufficiente (${available} MB, servono almeno ${MIN_FREE_MB} MB). Chiudi qualche applicazione o usa --force."
        fi
    fi

    if awk -v l="$load" -v c="$cores" 'BEGIN { exit !(l > c) }'; then
        log "Attenzione: il sistema è già molto carico, la build sarà lenta"
    fi
}

# ----------------------------------------------------------------------------- strumenti SDK

find_apksigner() {
    local sdk_dir
    sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "$sdk_dir" && -f "$PROJECT_DIR/local.properties" ]]; then
        sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$PROJECT_DIR/local.properties")
    fi
    [[ -n "$sdk_dir" ]] || return 1
    # Prendo la versione più recente dei build-tools installati.
    find "$sdk_dir/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -1
}

# ----------------------------------------------------------------------------- build

cd "$PROJECT_DIR"
check_resources

tasks=()
[[ "$RUN_TESTS" == true ]] && tasks+=("testDebugUnitTest")  # AGP 9 crea i test unitari solo per la variante debug; sono test JVM indipendenti dalla variante
tasks+=("assembleRelease")
[[ "$BUILD_BUNDLE" == true ]] && tasks+=("bundleRelease")

if [[ -n "${PROSCAENIA_KEYSTORE_FILE:-}" ]]; then
    log "Firma di produzione con il keystore indicato in PROSCAENIA_KEYSTORE_FILE"
else
    log "Variabili di firma non impostate: verrà usata la chiave di debug locale"
fi

log "Avvio Gradle: ${tasks[*]}"
./gradlew --console=plain "${tasks[@]}"

# ----------------------------------------------------------------------------- raccolta artefatti

APK_DIR="$PROJECT_DIR/app/build/outputs/apk/release"
METADATA="$APK_DIR/output-metadata.json"
[[ -f "$METADATA" ]] || fail "metadati della build non trovati in $METADATA"

version_name=$(sed -n 's/.*"versionName": *"\([^"]*\)".*/\1/p' "$METADATA" | head -1)
version_code=$(sed -n 's/.*"versionCode": *\([0-9]*\).*/\1/p' "$METADATA" | head -1)
apk_source=$(find "$APK_DIR" -maxdepth 1 -name '*.apk' | head -1)
[[ -n "$version_name" && -n "$apk_source" ]] || fail "APK o versione non trovati in $APK_DIR"

mkdir -p "$DIST_DIR"
apk_target="$DIST_DIR/proscaenia-${version_name}.apk"
cp "$apk_source" "$apk_target"

if [[ "$BUILD_BUNDLE" == true ]]; then
    aab_source=$(find "$PROJECT_DIR/app/build/outputs/bundle/release" -maxdepth 1 -name '*.aab' | head -1)
    [[ -n "$aab_source" ]] && cp "$aab_source" "$DIST_DIR/proscaenia-${version_name}.aab"
fi

# Archivio la mappa di offuscamento di R8 accanto all'APK: gli stack trace nel file di log degli
# errori di una build release hanno nomi di classi e metodi offuscati, e solo la mappa della stessa
# identica versione permette di ricostruirli (retrace). Senza di essa il log sarebbe illeggibile.
mapping_source="$PROJECT_DIR/app/build/outputs/mapping/release/mapping.txt"
if [[ -f "$mapping_source" ]]; then
    gzip -c "$mapping_source" > "$DIST_DIR/proscaenia-${version_name}-mapping.txt.gz"
else
    log "Attenzione: mapping.txt non trovato, gli stack trace di questa versione non saranno decodificabili"
fi

( cd "$DIST_DIR" && sha256sum "proscaenia-${version_name}.apk" $( [[ -f "proscaenia-${version_name}.aab" ]] && echo "proscaenia-${version_name}.aab" ) > "proscaenia-${version_name}.sha256" )

# ----------------------------------------------------------------------------- verifica firma

signer_info="(apksigner non trovato: firma non verificata)"
if apksigner=$(find_apksigner) && [[ -n "$apksigner" ]]; then
    "$apksigner" verify "$apk_target" || fail "la firma dell'APK non è valida"
    signer_info=$("$apksigner" verify --print-certs "$apk_target" | sed -n 's/^Signer #1 certificate DN: //p')
    if [[ "$signer_info" == *"Android Debug"* ]]; then
        signer_info="$signer_info  [chiave di DEBUG: adatta all'installazione diretta, non agli store]"
    fi
fi

echo
log "Build completata"
echo "    Versione : $version_name (versionCode $version_code)"
echo "    APK      : $apk_target ($(du -h "$apk_target" | cut -f1))"
if [[ "$BUILD_BUNDLE" == true ]]; then echo "    Bundle   : $DIST_DIR/proscaenia-${version_name}.aab"; fi
echo "    Mapping  : $DIST_DIR/proscaenia-${version_name}-mapping.txt.gz"
echo "    Firma    : $signer_info"
echo "    SHA-256  : $(cut -d' ' -f1 < "$DIST_DIR/proscaenia-${version_name}.sha256" | head -1)"
