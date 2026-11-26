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

## Installazione sulla TV (una tantum)

1. **Tizen Studio** (versione CLI o IDE) da developer.samsung.com/smarttv. Dal Package Manager vanno
   installati *Samsung Certificate Extension* e *TV Extensions* (anche una versione 5.x va bene).
2. **Developer Mode sulla TV**: App → digitare `1 2 3 4 5` con il telecomando → Developer mode **On**,
   inserire l'IP del PC → riavviare la TV (spegnimento completo).
3. **Certificato Samsung**: nel Certificate Manager va creato un profilo di tipo *Samsung* (TV)
   chiamato `proscaenia`, accedendo con l'account Samsung (gratuito). Per il certificato di
   distribuzione serve il **DUID della TV**: si legge collegando la TV da Device Manager
   (`sdb connect <IP TV>:26101`), che lo propone in automatico.
4. **Pacchetto, installazione e avvio**:

   ```
   cd tizen
   ./build-wgt.sh --tv <IP della TV>
   ```

   Senza `--tv` crea solo il `.wgt`. Se Tizen Studio non è in `~/tizen-studio`, va impostato
   `TIZEN_HOME`.

Se l'installazione fallisce per un privilegio non consentito, si toglie da `config.xml` la riga del
privilegio indicato nel messaggio d'errore (sono tutti di livello "public", ma alcuni firmware sono
più restrittivi) e si ripete.

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
- `js/nav.js`: navigazione spaziale da telecomando; `js/app.js`: schermate, OSD e ciclo di vita.
- `js/log.js`: log, sanificazione e invio al PC. `js/api.js`: contratto API e sessione (login
  automatico, rinnovo su 401, ritentativi). `js/secure-store.js`: custodia dei dati di accesso.
  `js/diagnostics.js`: controlli. `js/player-shaka.js`, `js/player-avplay.js`: i due motori.
  `js/export.js`: ZIP ed esportazione. `js/app.js`: interfaccia e flusso.
- `lib/`: Shaka Player 4.16.58 (Apache-2.0) e JSZip 3.10.1 (MIT/GPLv3, usato con licenza MIT),
  inclusi localmente con le rispettive licenze.
