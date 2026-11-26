# App Tizen: dati di accesso inseriti una sola volta

## Obiettivo

Sull'app per Samsung Tizen i dati di accesso (email e password dell'abbonamento) devono essere
digitati una sola volta, al primo avvio, e salvati sulla TV. Da quel momento ogni volta che il
servizio richiede un'autenticazione deve essere usato quanto salvato, senza mai riproporre la
schermata di login. È il requisito principale dell'app, ed è lo stesso comportamento già adottato
dall'app Android (`docs/client-android-proscaenia.md`, §8 e §9).

## Situazione di partenza

- Veniva salvata solo l'email (per prevalorizzare il campo) e il token Bearer; la password no.
- All'uscita l'app libera il posto dispositivo (`DELETE user/device/{DeviceId}`), operazione che
  invalida il token e lo cancella. Al riavvio, senza token, l'app mostrava di nuovo il login: in
  pratica i dati venivano chiesti a ogni avvio.
- Un 401 su una chiamata autenticata riportava alla schermata di login ("Sessione scaduta").
- Nessun ritentativo automatico: un errore di rete momentaneo arrivava subito all'utente.

## Decisioni

- **Login solo come schermata di primo avvio**: compare solo se sulla TV non ci sono dati di
  accesso salvati. Un rifiuto della piattaforma con dati già salvati diventa un errore con
  "Riprova", mai un rimando al login (come su Android, dove un rifiuto di dati già verificati è con
  buona probabilità momentaneo).
- **Salvataggio solo dopo un login riuscito**: salvare prima significherebbe non chiedere mai più
  una password digitata male. Un "MAX Device" al primo accesso non salva nulla, perché non è
  documentato che il backend verifichi la password prima del limite di dispositivi.
- **Rilascio del posto all'uscita invariato**: cancella solo il token. Il login successivo avviene
  da solo alla prima riproduzione; sfogliare il catalogo, che è pubblico, non apre sessioni e non
  occupa posti.
- **Custodia cifrata**: Tizen non offre alle app web un equivalente dell'Android Keystore. Scelta:
  WebCrypto AES-GCM 256 con chiave generata sulla TV e creata non estraibile (il materiale della
  chiave non è leggibile da JavaScript, che può solo usarla), conservata insieme ai dati cifrati in
  un unico record IndexedDB, così chiave e dati non possono disallinearsi.
  - Alternativa scartata: la Key Manager API di Tizen, deprecata nelle versioni recenti della
    piattaforma e quindi un rischio per la funzione più importante dell'app.
  - Alternativa scartata: salvataggio in chiaro come unico meccanismo. Resta solo come ripiego
    quando WebCrypto o IndexedDB mancano, perché la perdita della funzione (dati richiesti a ogni
    avvio) è considerata peggiore; il ripiego viene sempre registrato nel log.
- **Ritentativi**: ogni chiamata autenticata e ogni login automatico vengono ripetuti sugli errori
  transitori (rete/CORS/timeout, HTTP 5xx, 408, 429) con attese di 1, 2, 4 e 8 secondi. Il rifiuto
  dei dati salvati viene ripetuto una sola volta, per non far scattare la protezione anti forza
  bruta del backend. Non ripetuti: abbonamento non attivo, limite di dispositivi, risposta senza
  token.
- **Cambio account esplicito**: pulsante "Cambia account (dimentica i dati di accesso)" nella
  Diagnostica, con doppia pressione di conferma entro 5 secondi. È l'unica via, voluta, per far
  richiedere i dati (cambio account o cambio password dal sito); senza, l'unica alternativa sulla
  TV sarebbe reinstallare l'app. Prima di dimenticare i dati libera il posto dispositivo.

## Architettura

- **Custodia** (`tizen/js/secure-store.js`): caricamento all'avvio, salvataggio e cancellazione,
  tutti asincroni e mai in errore verso il chiamante. Un record cifrato illeggibile viene eliminato,
  così l'app chiede i dati una volta invece di fallire a ogni avvio. Un ripiego in chiaro rimasto da
  un salvataggio precedente viene usato se manca il record cifrato, ed eliminato al primo
  salvataggio cifrato riuscito.
- **Sessione** (`tizen/js/api.js`):
  - `ensureSession` garantisce un token prima di ogni chiamata autenticata: se manca esegue il login
    con i dati salvati. Le chiamate che arrivano durante un login in corso lo attendono invece di
    avviarne un secondo.
  - A ogni token è associata una generazione: chi riceve un 401 chiede il rinnovo indicando la
    generazione usata, e se un'altra chiamata ha già rinnovato riparte con il token nuovo senza
    rifare il login. Dopo il rinnovo la chiamata è ripetuta una sola volta.
  - `signIn` è il percorso del primo avvio: login reale, ritentativi solo sugli errori transitori,
    salvataggio dei dati solo in caso di successo.
  - Il rilascio del posto attende un eventuale login automatico in corso, altrimenti il token
    arrivato dopo la rimozione terrebbe occupato il posto.
- **Controller** (`tizen/js/app.js`): all'avvio carica i dati salvati prima di scegliere la prima
  schermata (catalogo se presenti, login altrimenti). L'avvio della riproduzione non controlla più
  il token: delega alla sessione e distingue "nessun dato salvato" (login, una volta) da "sessione
  non aperta" (errore con Riprova e motivo: rete, limite dispositivi, abbonamento, rifiuto).
- **Compatibilità con le installazioni precedenti**: chi ha solo il token di una versione precedente
  continua a sfogliare il catalogo e a riprodurre finché il token è valido; i dati vengono chiesti
  una sola volta quando il token non basta più, e da lì salvati.

## Scoperte durante sviluppo e test

- Il flusso è stato provato con un banco di prova in Node (contesto isolato, server simulato,
  WebCrypto reale di Node e un IndexedDB minimale in memoria), sia sul percorso cifrato sia sul
  ripiego. La prova ha rivelato un riferimento a `TextEncoder`/`TextDecoder` non qualificato
  rispetto al controllo di disponibilità eseguito sull'oggetto globale: corretto usando sempre il
  riferimento verificato.

## Riepilogo finale

Consegnato:

- login solo al primo avvio, salvataggio dei dati dopo il login riuscito, login automatico a ogni
  richiesta di autenticazione del servizio, rinnovo trasparente su 401 con login unico anche in
  presenza di chiamate concorrenti, ritentativi automatici sugli errori transitori;
- nessun rimando al login quando i dati salvati vengono rifiutati;
- pulsante "Cambia account" con doppia conferma nella Diagnostica.

Esito delle verifiche: sintassi ES5 verificata su tutti i file modificati; banco di prova superato
su entrambi i percorsi (cifrato e ripiego) per: rifiuto al primo accesso senza salvataggio,
salvataggio dopo login riuscito senza copie in chiaro, conservazione dei dati dopo il rilascio del
posto, ritrovamento al riavvio, login automatico alla prima scheda, rinnovo su 401, login unico con
due richieste parallele, password cambiata (errore, un solo ritentativo, dati conservati), cambio
account. Non ancora verificato sulla TV reale.

Limiti noti:

- La chiave di cifratura risiede nella stessa sandbox dei dati: la cifratura protegge da una lettura
  diretta dell'archivio, non da chi esegue codice all'interno dell'app.
- Se la password viene cambiata dal sito, l'app mostra un errore finché non si usa "Cambia account".
- Il token resta conservato in chiaro nell'archivio locale dell'app, come prima: serve in modo
  sincrono all'uscita per liberare il posto e la sua compromissione è revocabile dal sito.

File toccati:

- `tizen/js/secure-store.js` (nuovo)
- `tizen/js/api.js`
- `tizen/js/app.js`
- `tizen/js/nav.js`
- `tizen/index.html`
- `tizen/README.md`
- `TODO.md`
- `docs/tizen-credenziali-salvate.md`
