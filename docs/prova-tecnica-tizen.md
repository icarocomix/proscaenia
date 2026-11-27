# Prova tecnica Proscaenia su Samsung Tizen (UE43RU7400)

## 1. Obiettivo

Stabilire se Proscaenia può avere un client nativo sulle smart TV Samsung senza Google Cast, a
partire dalla UE43RU7400 (modello 2019, Tizen 5.0, motore web basato su Chromium 63). La prova deve
sciogliere i rischi tecnici prima di investire in un'interfaccia completa. Vincolo: intervento
puramente additivo, l'app Android e il percorso Cast restano intatti.

Richiesto inoltre: esportare il log della prova come file, compresso in ZIP, con la condivisione
verso altre app o social quando la piattaforma la consente.

## 2. Decisioni

- **App web Tizen (`.wgt`) in una cartella separata `tizen/`.** Tizen non esegue APK: del client
  Android si riusano il contratto API e le regole verificate (header di dispositivo, limite di 4
  dispositivi, rilascio del posto all'uscita, scelta della parte non "ads", ripresa dal progresso
  del server), non il codice.
- **Due motori di riproduzione a confronto.** Shaka Player 4.16.58 (EME/MSE, supporto ufficiale
  Tizen 2017+) e AVPlay, il player nativo Samsung. Il primo è più portabile, il secondo potenzialmente
  più leggero su un hardware modesto. Per ciascuno si provano Widevine e PlayReady: i manifest li
  dichiarano entrambi e il client web ufficiale configura per tutti e due un URL di licenza
  (`drm.proscaenia.com?drm-type=widevine|playready`, integrazione KeyOS).
- **AVPlay in modalità "GetChallenge".** La richiesta di licenza la invia l'app, con gli header
  `DeviceId`/`DeviceData` come su Android, invece di lasciarla al firmware senza controllo sugli
  header. I nomi delle proprietà per installare la licenza variano tra firmware: si provano le
  varianti note in sequenza e il log registra quale viene accettata.
- **XMLHttpRequest al posto di fetch.** Chromium 63 non ha AbortController, mentre XHR offre timeout
  e distingue un blocco di rete/CORS (stato 0) da una risposta HTTP: è proprio l'informazione
  cercata.
- **Librerie incluse localmente** (Shaka Player Apache-2.0, JSZip 3.10.1 usato con licenza MIT),
  senza CDN in esecuzione, con i file di licenza a fianco.
- **Esportazione del log.** Tizen 5.0 non ha né Web Share API né un meccanismo di condivisione tra
  app, quindi la condivisione verso i social non è realizzabile su questa TV. Uscite previste: ZIP
  su chiavetta USB (o nella cartella Download), invio dello ZIP a un ricevitore sul PC e condivisione
  nativa solo se rilevata, per firmware futuri. Alternativa scartata: un codice QR con il contenuto
  del log, perché la capacità di un QR (circa 3 KB) è insufficiente.
- **Log sanificato all'origine.** Ogni riga perde query string firmate, token Bearer, access_token e
  password prima di essere conservata, mostrata, inviata o compressa.
- **Token conservato in archivio locale**, a differenza dell'app Android. Serve a poter liberare il
  posto dispositivo anche dopo un'uscita brusca. Accettabile per una prova su un dispositivo
  personale.

## 3. Piano / architettura

- `config.xml`: profilo `tv-samsung`, accesso di rete aperto, privilegi per rete, tasti del
  telecomando, archiviazione, AVPlay, DRM e informazioni di prodotto.
- Moduli JavaScript senza dipendenze reciproche oltre l'ordine di caricamento: log (schermo, memoria,
  invio al PC), API (identità del dispositivo, header, login, catalogo, scheda, rilascio del posto),
  diagnostica (ambiente, EME per sistema DRM e livello di robustezza, CORS), i due motori di
  riproduzione, l'esportazione e il controller dell'interfaccia.
- Interfaccia a pannello con focus gestito dal telecomando. Azioni serializzate, una alla volta.
  Il tasto Indietro libera il posto dispositivo ed esce. All'evento `unload` parte un ultimo
  tentativo sincrono.
- Ricevitore Python sul PC (`tools/tizen_log_receiver.py`, solo libreria standard): righe di log in
  tempo reale e ZIP esportati.
- Installazione in tre script, eseguiti in sequenza e rilanciabili senza effetti collaterali:
  `tizen/tv-connetti.sh` collega la TV e ne legge il DUID, `tizen/certificato-samsung.sh` guida la
  creazione del profilo di firma Samsung e `tizen/build-wgt.sh` crea il pacchetto, lo installa e
  avvia l'app. Le funzioni comuni stanno in `tizen/tv-comune.sh`. IP e DUID della TV vengono salvati
  in `tizen/.tv-locale/`, esclusa da git perché legata all'apparecchio. La sequenza con le azioni
  da fare sulla TV è in `tizen/README.md`.

### Firma e installazione sulla TV

- **Serve un profilo Samsung, non Tizen.** Una TV Samsung in commercio accetta solo pacchetti
  firmati da un certificato distributore Samsung che elenca il DUID dell'apparecchio. Il profilo
  Tizen generico usa il distributore pubblico `tizen-distributor-signer` e viene rifiutato. Per
  questo il DUID va letto prima di creare il certificato, con la Developer Mode già attiva.
- **Creazione del profilo con il Certificate Manager grafico, guidata da uno script.** La creazione
  da riga di comando (`tizen certificate`/`security-profiles`) produce solo profili Tizen e non può
  ottenere il distributore Samsung, che richiede il login con l'account Samsung. Lo script controlla
  i prerequisiti, mostra i clic da fare e alla chiusura verifica il risultato: tipo del profilo,
  file dei certificati, presenza del DUID in `device-profile.xml`.
- **Profilo omonimo preesistente rimosso solo da `profiles.xml`, con conferma e backup.** Il
  Certificate Manager non accetta un nome già usato e `build-wgt.sh` usa per default `proscaenia`.
  I file dei certificati vecchi restano dove sono. Alternativa scartata: un profilo con un altro
  nome, che avrebbe obbligato a passare `--profile` a ogni installazione.
- **Lettura del DUID con la stessa sequenza del Certificate Manager** (libreria sdblib): campo `duid`
  di `sdb capability`, poi `shell 0 getduid`, `shell 0 getduidgadget` e `/opt/etc/duid-gadget`.
  Se nessuno risponde, il DUID si legge sulla TV in Impostazioni → Supporto → Informazioni su questa
  TV.
- **Controlli preventivi in `build-wgt.sh`.** Con `--tv` lo script si ferma prima di impacchettare
  se il profilo non è Samsung, e prima di installare se il DUID della TV collegata non è nel
  certificato. L'esito di `tizen install` si ricava dall'output, perché il CLI può terminare con
  codice 0 anche su rifiuto della TV.

## 4. Scoperte durante lo sviluppo

- Il client web ufficiale usa THEOplayer con integrazione KeyOS e configura licenze Widevine,
  PlayReady e FairPlay. L'URL PlayReady, prima ipotizzato, è risultato confermato.
- Anteprima su Chromium desktop con pagina caricata da file (origine `null`, simile a un'app
  impacchettata): catalogo pubblico raggiungibile sia come richiesta semplice sia con gli header di
  piattaforma (quindi il preflight CORS viene accettato) e server licenze raggiungibile. Il rischio
  principale, il controllo di `Origin` lato server, si ridimensiona. Va comunque confermato sul
  runtime della TV, che ha regole di accesso proprie.
- Chromium 63 non supporta la proprietà `gap` nei contenitori flex: sostituita con margini.
- Ambiente di firma (Tizen Studio 6.1 su Fedora): il profilo `proscaenia` creato inizialmente
  era Tizen generico, quindi non installabile sulla TV. La Samsung Certificate Extension
  (`cert-add-on` 2.0.75) è presente sia nell'IDE sia nel Certificate Manager autonomo.
- Il login Samsung del Certificate Manager non dipende dal browser incorporato di SWT (2017), che
  richiederebbe WebKitGTK 4.0, assente su Fedora recenti. Apre il browser di sistema
  (`Desktop.browse` o `xdg-open`) e riceve il token su `http://localhost:4794/signin/callback`. Serve
  quindi la porta locale 4794 libera.
- Il Certificate Manager (SWT GTK3 del 2017, JDK 8 incorporato) si avvia sotto Wayland via XWayland
  (`GDK_BACKEND=x11`), con soli avvisi GTK innocui.

## 5. Riepilogo

Consegnato:

- l'app di prova completa in `tizen/`, con diagnostica, login, scelta dello spettacolo, quattro
  varianti di riproduzione, rilascio del posto dispositivo ed esportazione del log in ZIP;
- il ricevitore dei log per il PC;
- lo script di pacchetto/installazione e la guida operativa (`tizen/README.md`).

Verifiche eseguite:

- sintassi di tutti i moduli JavaScript, dello script shell, del ricevitore Python e di `config.xml`;
- caricamento della pagina in Chromium headless senza errori;
- test CORS e JSZip da origine file;
- ricevitore provato con log, ZIP e un nome di file malevolo (scartato).

Non verificato:

- esecuzione sulla TV reale: Tizen Studio è pronto, mentre Developer Mode, profilo Samsung con il
  DUID e installazione sono da eseguire seguendo la sequenza di `tizen/README.md`. Degli script di
  installazione sono stati provati i controlli e i percorsi d'errore che non richiedono la TV: TV
  irraggiungibile, profilo Tizen rifiutato, rimozione del profilo su una copia di `profiles.xml`,
  prerequisiti del Certificate Manager e suo avvio.

Limiti noti:

- condivisione verso i social non disponibile su Tizen 5.0;
- "Amplifica voce" non trasferibile, perché l'audio protetto da DRM non è accessibile da JavaScript;
- i nomi delle proprietà DRM di AVPlay vanno confermati dal log;
- alcuni privilegi potrebbero essere rifiutati da firmware restrittivi (la guida spiega come
  rimuoverli).

File toccati:

- `tizen/config.xml`, `tizen/index.html`, `tizen/icon.png`, `tizen/css/style.css`
- `tizen/js/log.js`, `tizen/js/api.js`, `tizen/js/diagnostics.js`, `tizen/js/player-shaka.js`,
  `tizen/js/player-avplay.js`, `tizen/js/export.js`, `tizen/js/app.js`
- `tizen/lib/shaka-player.compiled.js`, `tizen/lib/SHAKA-LICENSE.txt`, `tizen/lib/jszip.min.js`,
  `tizen/lib/JSZIP-LICENSE.markdown`
- `tizen/build-wgt.sh`, `tizen/tv-connetti.sh`, `tizen/certificato-samsung.sh`, `tizen/tv-comune.sh`,
  `tizen/README.md`
- `tools/tizen_log_receiver.py`
- `.gitignore`, `TODO.md`
