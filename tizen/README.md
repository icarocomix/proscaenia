# Proscaenia — prova tecnica per Samsung Tizen

App web Tizen (`.wgt`) che verifica sulla TV reale (UE43RU7400, Tizen 5.0) se un client Proscaenia
nativo è realizzabile. Non tocca l'app Android né il percorso Cast.

Dalla versione 0.2.0 l'interfaccia ricalca l'app Android TV (login, catalogo a righe, scheda con
"Riproduci", player con OSD, barra "In riproduzione"); le verifiche tecniche elencate qui sotto sono
nella schermata **Diagnostica**, raggiungibile dal login e dall'intestazione del catalogo.

Scelta del player automatica: a ogni avvio l'app prova in sequenza Shaka · Widevine, Shaka ·
PlayReady, AVPlay · PlayReady e AVPlay · Widevine, partendo da quello che ha funzionato l'ultima
volta. Un player viene scartato se rifiuta l'avvio, se segnala un errore prima di partire o se non
parte entro 45 s; il primo che parte diventa il preferito per i prossimi avvii. Solo se falliscono
tutti compare un messaggio d'errore. Nella Diagnostica si può comunque scegliere il player da
provare per primo; l'esito di ogni tentativo è nel log.

Cosa verifica:

1. **Ambiente**: versione Tizen, motore web, codec MSE, sistemi DRM disponibili via EME
   (Widevine con i vari livelli di robustezza, PlayReady), supporto di Shaka Player.
2. **API e CORS**: catalogo con e senza header di piattaforma (preflight), raggiungibilità del
   server licenze. È il rischio principale: dalla TV non si possono inviare `Origin`/`Referer` del sito.
3. **Login** con il limite di 4 dispositivi dell'account.
4. **Riproduzione** dello spettacolo scelto, con ripresa dal progresso del server, in quattro varianti:
   Shaka (EME) con Widevine o PlayReady, AVPlay (player nativo Samsung) con PlayReady o Widevine.
5. **Rilascio del posto dispositivo** all'uscita (tasto Indietro o pulsante dedicato).

## Log ed esportazione

Il log è a schermo (pannello destro) ed è già sanificato: niente token, password o URL firmati.
Per portarlo fuori dalla TV:

- **Esporta ZIP (USB)**: salva `proscaenia-tizen-log-<data>.zip` sulla chiavetta USB inserita nella
  TV, oppure, se non c'è, nella cartella Download interna.
- **Invia ZIP al PC**: invia lo ZIP al ricevitore sul PC (vedi sotto).
- **Condividi ZIP**: usa la condivisione nativa del sistema, ma solo se il firmware la offre. Su
  Tizen 5.0 non esiste (né Web Share API né condivisione tra app), quindi sulla RU7400 il pulsante
  risulta "non disponibile". Per mandare il log a un'app di messaggistica o a un social conviene
  passare dalla chiavetta USB o dal PC.

Ricevitore sul PC (solo libreria standard Python), da avviare dalla radice del progetto:

```
python3 tools/tizen_log_receiver.py --port 8765
```

Sulla TV, nel campo "Ricevitore PC", va inserito `http://<IP del PC>:8765`, poi si preme
"Attiva invio al PC". Da quel momento ogni riga arriva in tempo reale in `tizen-log/tizen-live-<data>.txt`,
e gli ZIP inviati vengono salvati in `tizen-log/`. Serve la porta 8765 aperta nel firewall del PC
(su Fedora: `sudo firewall-cmd --add-port=8765/tcp`, temporaneo fino al riavvio).

## Installazione sulla TV

Tizen Studio 6.1 CLI con *TV Extensions* e *Samsung Certificate Extension* è già installato in
`~/tizen-studio`. Se si trova altrove, va impostato `TIZEN_HOME`. Tutti i comandi si lanciano dalla
cartella `tizen/`. Ogni script controlla i propri prerequisiti, dice cosa manca e può essere
rilanciato senza danni.

| Script | Passo | Cosa fa |
|--------|-------|---------|
| `tv-connetti.sh <IP TV>` | 2 | Verifica la rete e la porta di sviluppo, esegue `sdb connect`, legge e salva il DUID. |
| `certificato-samsung.sh` | 3 | Controlla i prerequisiti, mette da parte un profilo Tizen con lo stesso nome, apre il Certificate Manager e verifica il risultato. |
| `build-wgt.sh --tv` | 4 | Pacchetto firmato, controllo di profilo e DUID, installazione e avvio. |

### Perché serve un certificato Samsung

Una TV Samsung in commercio installa solo pacchetti firmati da un **profilo Samsung**, formato da:

- **certificato autore**: identifica chi firma, protetto da una password scelta al momento della
  creazione;
- **certificato distributore**: emesso da Samsung dopo il login con un account Samsung (gratuito) e
  valido solo per le TV elencate per **DUID**, l'identificativo univoco dell'apparecchio.

Per questo il DUID va letto dalla TV *prima* di creare il certificato, e la TV deve già essere in
Developer Mode. Un profilo di tipo *Tizen* (generico) viene rifiutato dalla TV al momento
dell'installazione.

### Sequenza della prima installazione

| # | Dove | Azione |
|---|------|--------|
| 1 | TV | Annotare l'IP della TV: Impostazioni → Generali → Rete → Stato rete → Impostazioni IP. |
| 2 | PC | `./tv-connetti.sh <IP TV>`: la porta risulterà ancora chiusa, ma lo script stampa l'**IP del PC** da usare al passo 3. |
| 3 | TV | Aprire **App** e digitare `1 2 3 4 5` col telecomando (tastierino numerico o a schermo). Impostare **Developer mode: On** e **Host PC IP** con l'IP del passo 2, poi OK. |
| 4 | TV | Spegnimento **completo**: tenere premuto il tasto di accensione finché la TV si riavvia, oppure staccare la spina per 30 s, poi riaccendere. In alto compare la scritta "Developer Mode". |
| 5 | PC | `./tv-connetti.sh <IP TV>`: deve finire con "DUID: …". IP e DUID vengono salvati in `tizen/.tv-locale/`, esclusa da git. |
| 6 | PC | `./certificato-samsung.sh`, con la TV ancora accesa. Alla domanda sul vecchio profilo `proscaenia` di tipo Tizen rispondere **s**: viene rimosso dopo un backup di `profiles.xml`. Premere Invio per aprire il Certificate Manager. |
| 7 | PC | Nel Certificate Manager seguire i clic elencati sotto, che lo script ripete a video. Il login Samsung avviene nel browser che si apre. Al termine chiudere il Certificate Manager: lo script verifica che il profilo sia Samsung e contenga il DUID. |
| 8 | PC | `./build-wgt.sh --tv`: crea il pacchetto firmato, controlla che il DUID della TV sia nel certificato, installa e avvia l'app. Senza IP usa quello salvato. |
| 9 | TV | Si apre l'app. Al primo avvio si inseriscono i dati di accesso. |

Clic nel Certificate Manager (passo 7):

1. **+** → tipo **Samsung** (non Tizen) → device type **TV** → nome profilo `proscaenia`, opzione
   "Create a new certificate profile" → Next.
2. **Author**: "Create a new author certificate" → nome a piacere, password a piacere (annotarla)
   → login con l'account Samsung nel browser → Next.
3. **Distributor**: "Create a new distributor certificate" → privilegio **Public** → nell'elenco
   DUID deve comparire quello della TV, che lo script mostra a video. Se manca, aggiungerlo a mano.
   Poi Next → **Finish**.
4. Chiudere il Certificate Manager.

### Installazioni successive

Con la TV accesa basta `./build-wgt.sh --tv`. Senza `--tv` crea solo il `.wgt`. Se la TV non viene
trovata, `./tv-connetti.sh` indica cosa controllare. Il caso tipico è un IP del PC cambiato dal
router, da reimpostare nella Developer Mode: conviene riservare al PC un IP fisso nel router. La
Developer Mode resta attiva finché non la si disattiva.

### Possibili intoppi

- **Porta 26101 chiusa** dopo l'attivazione: la TV non è stata spenta del tutto, oppure l'Host PC IP
  non corrisponde all'IP attuale del PC.
- **Login Samsung**: il Certificate Manager apre il browser predefinito e attende la risposta su
  `localhost:4794`, porta che lo script verifica libera. Se la pagina conferma l'accesso ma il
  Certificate Manager resta in attesa, premere di nuovo "Sign in".
- **"Profilo di tipo tizen"** segnalato da `build-wgt.sh`: nel Certificate Manager era stato scelto
  *Tizen* invece di *Samsung*. Rilanciare `./certificato-samsung.sh`.
- **TV diversa o sostituita**: ripetere i passi 2–8, perché il certificato distributore va
  rigenerato con il nuovo DUID.
- **Privilegio non consentito** durante l'installazione: togliere da `config.xml` la riga del
  privilegio indicato nel messaggio d'errore e ripetere il passo 8. Sono tutti privilegi di livello
  "public", ma alcuni firmware sono più restrittivi.

## Uso con il telecomando

- **Frecce**: navigazione spaziale tra righe e schede (dentro i campi di testo ◀▶ muovono il
  cursore); **OK** esegue; il focus è sempre evidenziato da un bordo oro.
- **Catalogo → scheda**: OK su una scheda apre la scheda dello spettacolo con il focus già su
  *Riproduci* (o *Riprendi da m:ss*).
- **Player**: qualunque tasto mostra l'OSD (Indietro 10 s, Play/Pausa, Stop, Avanti 10 s, barra di
  avanzamento), che si nasconde dopo 5 s mentre lo spettacolo scorre. A OSD nascosto ◀▶ saltano di
  ±10 s. I tasti Play/Pausa, Stop, ⏪⏩ del telecomando funzionano sempre.
- **Indietro** nel player: con l'OSD visibile lo nasconde; altrimenti torna alla scheda lasciando
  l'audio in sottofondo, con la barra *In riproduzione* (Torna al player / Stop).
- **Home** o cambio di app/sorgente: la riproduzione si ferma sempre; rientrando sul player lo
  spettacolo viene ripreparato in pausa dallo stesso punto.
- **Indietro** dal catalogo (o dal login): ferma la riproduzione, libera il posto dispositivo ed esce.

Il posto dispositivo resta occupato finché non si esce con Indietro o con il pulsante dedicato nella
Diagnostica. Se l'app viene chiusa in modo brusco (spegnimento), il posto si libera con il pulsante
oppure dalla pagina dispositivi del sito.

**Dati di accesso**: si inseriscono una sola volta, al primo avvio, e vengono salvati sulla TV solo
dopo che la piattaforma li ha accettati (cifrati con WebCrypto, chiave non estraibile in IndexedDB;
ripiego sull'archivio locale dell'app se il motore web non li supporta). Da lì in poi il login non
compare più: ogni volta che il servizio richiede l'accesso (prima riproduzione dopo l'avvio, token
rifiutato, posto liberato all'uscita) il login avviene da solo con i dati salvati. L'unico modo per
farli richiedere è **Cambia account** nella Diagnostica (doppia pressione di conferma).

## Contenuto

- `config.xml`, `index.html`, `icon.png`, `css/`: struttura dell'app.
- `tv-connetti.sh`, `certificato-samsung.sh`, `build-wgt.sh`: installazione sulla TV (passi 2, 3 e 4);
  `tv-comune.sh`: funzioni condivise dai tre script. Nessuno di questi entra nel `.wgt`.
- `js/nav.js`: navigazione spaziale da telecomando; `js/app.js`: schermate, OSD e ciclo di vita.
- `js/log.js`: log, sanificazione e invio al PC. `js/api.js`: contratto API e sessione (login
  automatico, rinnovo su 401, ritentativi). `js/secure-store.js`: custodia dei dati di accesso.
  `js/diagnostics.js`: controlli. `js/player-shaka.js`, `js/player-avplay.js`: i due motori.
  `js/export.js`: ZIP ed esportazione. `js/app.js`: interfaccia e flusso.
- `lib/`: Shaka Player 4.16.58 (Apache-2.0) e JSZip 3.10.1 (MIT/GPLv3, usato con licenza MIT),
  inclusi localmente con le rispettive licenze.
