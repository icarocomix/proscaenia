# Interfaccia TV: layout 10-foot, controlli del player, audio in background e parità Tizen

## 1. Obiettivo

Correggere tre difetti dell'app Android sulle TV e allineare l'app Tizen alla stessa interfaccia:

1. **Layout della home**: la pagina appariva ingrandita e per raggiungere il pulsante di
   riproduzione bisognava scorrere molto. Serve un layout 10-foot con azioni principali visibili
   senza scorrere e focus da telecomando sempre visibile e in ordine logico.
2. **Controlli del player**: durante la riproduzione Play, Pausa, Stop, Avanti e Indietro erano
   nascosti o irraggiungibili. Serve una barra di controllo (OSD) che compaia a ogni tasto, si
   nasconda dopo un timeout e non risulti mai tagliata fuori dallo schermo.
3. **Audio**: uscendo dal player verso il catalogo l'audio può proseguire (con uno Stop esplicito);
   uscendo dall'app (Home, cambio app, background, chiusura) l'audio deve fermarsi sempre.
4. **Tizen**: stessa struttura, gerarchia visiva, palette, etichette e comportamento dei controlli
   dell'app Android, nei limiti della piattaforma.

Vincolo preesistente: le modifiche per le TV devono essere additive; telefono e percorso Google Cast
restano invariati.

## 2. Decisioni

### Modalità TV attivata solo sulle TV
Lo stesso APK serve telefono e TV. Tutte le novità di layout, OSD e arresto dell'audio in background
si attivano solo quando il sistema dichiara la modalità televisione (UI mode "television" oppure
funzionalità leanback). Sul telefono restano il controller della PlayerView, la riproduzione in
background con notifica e il Cast esattamente come prima. Unica aggiunta visibile anche sul
telefono: la barra "In riproduzione" in catalogo e scheda, puramente additiva.

### Densità normalizzata a un canvas di 960 dp
Causa individuata del "tutto ingrandito": i televisori dichiarano densità molto diverse a parità di
risoluzione; un pannello 1080p a 480 dpi espone solo 640 dp di larghezza. Sulla TV la densità di
Compose viene ricavata dalla larghezza reale in pixel, così il canvas è sempre di 960×540 dp (il
riferimento 10-foot di Android TV), con la scala dei caratteri dell'utente preservata.
Alternativa scartata: ridurre a mano caratteri e misure, che avrebbe corretto un solo televisore e
peggiorato gli altri.

### Scheda in due colonne
Seconda causa del "pulsante in fondo": nella scheda la locandina 16:9 a tutta larghezza occupava da
sola l'intera altezza di uno schermo orizzontale. Sulla TV la locandina sta a sinistra (42%) e titolo,
genere, "Riproduci/Riprendi da" e sinossi a destra; il focus parte già sul pulsante principale.

### Catalogo compatto e focus evidente
Intestazione compatta al posto della barra Material, margini di overscan (48 dp ai lati, 27 dp in
alto e in basso), schede da 168 dp: restano visibili almeno due righe complete. Il focus è reso con
un bordo oro e un leggero ingrandimento (l'overlay standard di Material è invisibile a distanza);
le righe ricordano l'ultima scheda visitata e il catalogo ripristina il focus sulla scheda da cui si
era entrati nella scheda di dettaglio.

### OSD in Compose al posto del controller della PlayerView (solo TV)
Il controller della PlayerView riceve i tasti solo se la View ha il focus, condizione non garantita
dentro una UI Compose: da qui i controlli irraggiungibili. Sulla TV la PlayerView mostra solo video,
sottotitoli e buffering e non prende mai il focus; i tasti sono gestiti in un unico punto con queste
regole, identiche su Tizen:

- OSD nascosto: OK/Su/Giù lo mostrano; Sinistra/Destra saltano di 10 s e lo mostrano;
- OSD visibile: il D-pad si muove tra Indietro 10 s → Play/Pausa → Stop → Avanti 10 s → funzioni
  accessorie (sottotitoli, Amplifica voce, velocità); a ogni apertura il focus parte da Play/Pausa;
- tasti multimediali (Play/Pausa, Play, Pausa, Stop, avanti/indietro veloce) sempre attivi;
- Indietro con l'OSD visibile e lo spettacolo in corso lo nasconde, altrimenti esce dal player;
- chiusura automatica dopo 5 s senza tasti solo mentre lo spettacolo scorre (in pausa, buffering o
  errore resta visibile); con un errore il focus va su "Riprova".

Alternativa scartata: mantenere la PlayerView e forzarle il focus. È fragile (Compose può spostare il
focus in qualunque momento) e non permette di rendere l'OSD identico a quello Tizen.

### Stop, audio in sottofondo e uscita dall'app
- **Stop esplicito** (OSD o tasto Stop): pausa, salvataggio della posizione, arresto del player che
  conserva spettacolo e posizione, ritorno alla scheda con "Riprendi da".
- **Uscita dal player verso il catalogo**: l'audio prosegue; il servizio pubblica lo spettacolo
  attivo su un canale interno al processo e catalogo e scheda mostrano la barra "In riproduzione"
  con *Torna al player* e *Stop*. Scelto un canale interno invece di un MediaController perché un
  controller legherebbe e quindi creerebbe il servizio (con il suo player) anche senza riproduzione.
- **Uscita dall'app sulla TV** (Home, cambio app, salvaschermo, Indietro dal catalogo, chiusura dalle
  recenti): il servizio osserva la visibilità dell'interfaccia e, appena diventa non visibile, salva
  la posizione e ferma il player. Lo stato fermo conta come "nessuna riproduzione attiva", quindi il
  posto dispositivo viene liberato dal meccanismo esistente. Durante il Cast non interviene.
- **Rientro**: se il player era in primo piano lo spettacolo viene ripreparato dalla stessa posizione
  ma in pausa, con l'OSD visibile: nessun audio improvviso al ritorno.
- **Salvaschermo**: con l'audio in sottofondo lo schermo viene tenuto acceso finché lo spettacolo
  suona, altrimenti il salvaschermo porterebbe l'app fuori dal primo piano e fermerebbe l'audio.

### Tizen: da pannello di prova a client con la stessa interfaccia
La pagina Tizen è a 1920 px e il canvas Android a 960 dp: regola di parità **1 dp = 2 px**, applicata
a margini di overscan, schede, caratteri (scala tipografica Material) e raggi. Stessa palette del
tema Android (nero sala, rosso sipario, oro ribalta, avorio), titoli con grazie, stesse etichette
("Riproduci", "Riprendi da", "In riproduzione", "Torna al player", "Stop", "Indietro 10 s",
"Avanti 10 s", "Riprova", "Chiudi"). Schermate: login, catalogo a righe, scheda in due colonne,
player con OSD, Diagnostica (che conserva tutte le verifiche della prova tecnica).

**Scelta automatica del player su Tizen.** Non essendo ancora noto quale combinazione di motore e
DRM funzioni sulla RU7400, l'app non chiede all'utente di cambiare player: a ogni avvio prova in
sequenza Shaka · Widevine, Shaka · PlayReady, AVPlay · PlayReady, AVPlay · Widevine, partendo dal
preferito (l'ultimo che ha funzionato, o quello indicato nella Diagnostica). Un tentativo fallisce se
l'avvio viene rifiutato, se il player segnala un errore prima di partire (licenza, formato,
decodifica) o se non parte entro 45 s, soglia larga per non scartare un player valido su rete lenta;
il player fallito viene chiuso prima di provare il successivo, per non tenere due decoder aperti.
Il primo che parte diventa il preferito, così i tentativi falliti non si ripetono. Solo se falliscono
tutti compare l'errore, con Riprova e Chiudi. Gli errori che avvengono a riproduzione già avviata
(rete, link scaduto) non innescano il cambio di player: mostrano la scheda d'errore con Riprova, che
riparte dalla stessa posizione con la stessa sequenza. Alternativa scartata: chiedere all'utente di
scegliere il motore dalla Diagnostica, che scarica sull'utente una scelta tecnica. Navigazione spaziale geometrica con memoria
dell'ultima scheda per riga, equivalente a quella di Compose. Su `visibilitychange` la riproduzione
viene sempre fermata (non solo messa in pausa); il tasto Exit/chiusura ferma il player e libera il
posto in modo sincrono. Funzioni accessorie Android non disponibili su Tizen (Amplifica voce,
velocità, sottotitoli) non compaiono nell'OSD.

## 3. Piano / architettura

Android:
- rilevamento della forma del dispositivo (TV o no), memorizzato per la vita del processo;
- contenitore Compose per la TV che fornisce il flag "modo TV" e la densità normalizzata;
- modificatore di evidenziazione del focus attivo solo in modo TV;
- canale di stato interno al processo (spettacolo in corso, in pausa o meno, comando Stop) tra
  servizio di riproduzione e UI, esposto dal contenitore delle dipendenze insieme alla visibilità
  dell'interfaccia in sola lettura;
- servizio: pubblicazione dello stato a ogni evento del player, esecuzione dello Stop, arresto su
  TV quando l'interfaccia non è visibile e alla chiusura dalle recenti;
- ViewModel del player: Play/Pausa (con ripreparazione se fermo), salti, Stop, ripresa in pausa al
  rientro, riapertura di uno spettacolo fermato;
- schermate: catalogo e scheda con variante TV, OSD Compose per il player, barra "In riproduzione".

Tizen:
- modulo di navigazione spaziale; `index.html` riorganizzato in schermate; foglio di stile con le
  misure Android raddoppiate; motori Shaka e AVPlay estesi (avvio in pausa, notifica di buffering,
  pausa/ripresa, fine, durata); controller dell'app riscritto con cronologia delle schermate, OSD,
  barra "In riproduzione", posizioni di visione locali con la stessa regola di ripresa di Android
  (nessuna ripresa nei primi 5 s né negli ultimi 15 s), gestione di 401 con ritorno al login.

## 4. Scoperte e correzioni durante lo sviluppo

- La causa del layout "ingrandito" non era solo tipografica: la combinazione di densità dichiarata
  dal televisore e locandina a tutta larghezza nella scheda spingeva "Riproduci" sotto il bordo.
  Corrette entrambe (canvas normalizzato e scheda in due colonne).
- Verifica visiva dell'interfaccia Tizen in Chromium headless a 1920×1080 con dati e motore finti:
  è emerso uno spazio vuoto sotto l'intestazione del catalogo (contenitore dei messaggi vuoto ma con
  padding) e il bordo di "immagine rotta" sulle locandine non ancora caricate; entrambi corretti. Il
  pulsante Play/Pausa cambiava larghezza tra le due etichette spostando la riga dei pulsanti: fissata
  una larghezza minima.
- Simulazione della sequenza di tentativi con motori finti: primo player in errore, secondo che
  rifiuta l'avvio, terzo che parte (diventa il preferito, nessun messaggio all'utente); e il caso con
  tutti e quattro in errore (un solo messaggio finale). Corretto lo sfondo dietro il messaggio finale:
  il piano video dell'ultimo player tentato restava attivo e va rimosso.
- Il caricamento di tutte le locandine del catalogo insieme è rischioso per la memoria grafica della
  RU7400: su Tizen le immagini vengono caricate solo per le righe vicine a quella con il focus.

## 5. Riepilogo finale

Consegnato:
- Android TV: canvas 10-foot normalizzato, catalogo compatto, scheda in due colonne con focus su
  "Riproduci", focus evidenziato su schede e pulsanti (anche login, errori, velocità), OSD con
  Indietro 10 s / Play-Pausa / Stop / Avanti 10 s / avanzamento / funzioni accessorie e tasti
  multimediali, barra "In riproduzione" con Torna al player e Stop, arresto dell'audio all'uscita
  dall'app con ripresa in pausa al rientro, schermo acceso durante l'audio in sottofondo.
- Tizen 0.2.0: interfaccia allineata ad Android con le stesse regole di OSD e di audio, diagnostica
  conservata in una schermata dedicata.

Esito di build e test:
- Android: compilazione Kotlin, 23 test unitari JVM superati, `assembleDebug` riuscito (build 23),
  APK di prova copiato in `dist/proscaenia-tv-prova-debug.apk`.
- Tizen: sintassi di tutti i moduli verificata, `config.xml` valido, pacchetto firmato
  `tizen/Proscaenia.wgt` creato; interfaccia verificata in Chromium headless con dati finti (catalogo,
  scheda, OSD in pausa e in riproduzione, barra "In riproduzione").

Limiti noti:
- Nessuna prova ancora su TV reale (Android TV e Samsung): vanno verificati in particolare la
  ricezione dei tasti multimediali, il comportamento con il salvaschermo e, su Tizen, l'intera
  catena di riproduzione (motore e DRM da scegliere in Diagnostica).
- Su Tizen il login conserva solo il token: dopo l'uscita con rilascio del posto dispositivo, al
  successivo avvio vanno reinserite le credenziali (su Android sono salvate cifrate).
- Su Tizen mancano Amplifica voce, scelta della velocità e sottotitoli.
- Il pannello della velocità su TV resta il pannello inferiore Material, con il focus evidenziato
  ma senza un layout dedicato.

File toccati:
- `app/src/main/java/com/icarocomix/proscaenia/core/device/DeviceForm.kt` (nuovo)
- `app/src/main/java/com/icarocomix/proscaenia/ui/tv/TvMode.kt` (nuovo)
- `app/src/main/java/com/icarocomix/proscaenia/ui/tv/TvFocus.kt` (nuovo)
- `app/src/main/java/com/icarocomix/proscaenia/playback/PlaybackStatus.kt` (nuovo)
- `app/src/main/java/com/icarocomix/proscaenia/ui/common/NowPlayingBar.kt` (nuovo)
- `app/src/main/java/com/icarocomix/proscaenia/ui/player/TvPlayerOverlay.kt` (nuovo)
- `app/src/main/java/com/icarocomix/proscaenia/di/AppContainer.kt`
- `app/src/main/java/com/icarocomix/proscaenia/playback/PlaybackService.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/MainActivity.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/navigation/AppNavHost.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/catalog/CatalogScreen.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/detail/DetailScreen.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/player/PlayerScreen.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/player/PlayerViewModel.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/player/SpeedSheet.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/login/LoginScreen.kt`
- `app/src/main/java/com/icarocomix/proscaenia/ui/common/StateViews.kt`
- `app/src/main/res/values/strings.xml`
- `tizen/js/nav.js` (nuovo)
- `tizen/index.html`, `tizen/css/style.css`, `tizen/js/app.js`, `tizen/js/api.js`,
  `tizen/js/player-shaka.js`, `tizen/js/player-avplay.js`, `tizen/config.xml`, `tizen/README.md`
- `TODO.md`, `version.properties` (build number incrementato dalla compilazione)
