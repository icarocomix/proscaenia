# Client Android nativo per Proscaenia

## 1. Obiettivo

Applicazione Android nativa (Kotlin, Jetpack Compose, minSdk 26, targetSdk 36) che funge da client
alternativo e lettore avanzato per la piattaforma di streaming teatrale in abbonamento Proscaenia:
navigazione del catalogo per categorie, scheda dello spettacolo, riproduzione con Media3/ExoPlayer
1.10, elaborazione audio "Amplifica voce", velocità variabile, sottotitoli WebVTT, Chromecast,
riproduzione in background con MediaSession e ripresa automatica della posizione. Richiesto in
aggiunta: un file di log degli errori consultabile dall'esplora risorse del telefono, da inoltrare
per la diagnosi.

## 2. Decisioni prese

| Tema | Scelta | Alternative scartate e motivo |
|---|---|---|
| Toolchain | AGP 9.1 con Kotlin integrato 2.2.10, Compose BOM 2026.06.01, Gradle 9.3.1 | Versioni più recenti: non verificate su questa macchina; queste sono già usate con successo da altri progetti locali e presenti in cache |
| Serializzazione JSON | `org.json` della piattaforma con mappatura esplicita | kotlinx-serialization 1.10 è compilata per Kotlin 2.3, non leggibile dal compilatore 2.2; inoltre il contratto API è da allineare e una mappatura esplicita tollera meglio i campi mancanti |
| Dependency injection | Contenitore manuale (`AppContainer`) | Hilt/Koin: grafo piccolo e statico, nessun beneficio a fronte di elaborazione annotazioni in build |
| Credenziali | EncryptedSharedPreferences con chiave AES-256-GCM nel Keystore; salvataggio solo dopo login riuscito | Salvataggio preventivo: una password errata non verrebbe mai più richiesta, dato che il login compare solo al primo avvio |
| Sessione | Cookie solo in memoria + token Bearer opzionale; rinnovo trasparente con contatore di "generazione" | Persistenza dei cookie: superflua (un login costa una richiesta) e meno sicura; `Authenticator` di OkHttp: gestisce solo il 401, non il 403 |
| Comunicazione UI ⇄ servizio | MediaController/MediaSession standard; preferenze condivise osservate dal servizio | Binder locale con API proprietaria: accoppiamento più forte e nessun supporto a controller esterni |
| Risoluzione dell'URL | Nel servizio (callback `onSetMediaItems`), la UI invia solo ID e metadati | Risoluzione in UI: la rigenerazione dopo scadenza deve funzionare anche a schermo spento |
| Cast | `CastPlayer.Builder` + `RemoteCastPlayer` di Media3 1.10 con callback di trasferimento | Gestione manuale di due player e `session.setPlayer`: la 1.10 offre già il cambio automatico |
| Limite 2x sul Chromecast | `ForwardingPlayer` (`SpeedLimitingPlayer`) che limita l'effettivo ma salva la scelta dell'utente | Limitare la preferenza salvata: al rientro sul telefono si perderebbe la velocità scelta (es. 2,5x) |
| Errori 403/410 | Politica di caricamento che non ritenta e non cambia variante per 403/410 | Politica predefinita: ritentativi e cambi di variante inutili con firme scadute, ritardano la rigenerazione |
| Persistenza posizioni e preferenze | SharedPreferences (apply, commit alla distruzione del servizio) | DataStore: lettura sincrona necessaria alla creazione del player; Room: sproporzionato per due valori per spettacolo |
| File di log | Android 10+: `Download/Proscaenia/proscaenia-errori.txt` via MediaStore; Android 8–9: cartella esterna dell'app | `WRITE_EXTERNAL_STORAGE`: permesso da chiedere solo per i log; cartella privata interna: non raggiungibile dall'utente |
| Tema | Sempre scuro, colori "sala teatrale", nessun colore dinamico | Material You: identità visiva variabile tra dispositivi |

## 3. Architettura

Struttura a livelli in un unico modulo `app`, package `com.icarocomix.proscaenia`:

- `domain`: modelli (categoria, spettacolo, sorgente, traccia sottotitoli, posizione salvata),
  interfacce dei repository, eccezioni di dominio (tutte `IOException`, perché nascono anche dentro
  gli interceptor OkHttp), regole pure di ripresa e velocità.
- `data`: endpoint, client API, mapper JSON, archivio credenziali cifrato, gestione sessione,
  repository, preferenze e posizioni.
- `core`: rete condivisa (User-Agent unico, CookieJar in memoria, ponte OkHttp-coroutine) e logging.
- `playback`: fabbrica di ExoPlayer, catena audio, politica errori, limitatore di velocità per il
  Cast, supporto Cast tollerante all'assenza di Play Services, servizio `PlaybackService`.
- `ui`: activity unica, navigazione Compose (login, catalogo, dettaglio, player), ViewModel MVVM.

Topologia di rete: un client OkHttp base (CookieJar, User-Agent, timeout) da cui derivano il client
API (con interceptor di sessione) e il client media (senza, timeout di lettura più lungo). Il login
usa il client base, quindi non può innescare login ricorsivi. Player, miniature (Coil) e copertina
della notifica usano il client media: stessi cookie e User-Agent della richiesta che ha generato il
link, condizione per evitare i 403 delle CDN.

Contratto API reale (descritto in `ProscaeniaEndpoints` e `ProscaeniaJsonMapper`; URL base in
`gradle.properties`, proprietà `proscaenia.baseUrl`, valore `https://proscaenia.com/backoffice/api/`).
La versione iniziale, basata su un contratto ipotetico, è stata sostituita: vedi §7.

| Endpoint | Metodo | Risposta usata |
|---|---|---|
| `auth/login` | POST JSON `email`, `password`, `remember` | `access_token` (Bearer, validità circa un anno), `refresh_token`, `expires_in`; `errors[0] = "Not active subscription"` = abbonamento non attivo |
| `catalog/snapshot` | GET, pubblico | Documento unico: `shows[]` (`id`, `title`, `content` HTML, `img_preview`, `img_header`, `category_id`), `rows[]` (`id`, `title`, `show_ids[]`) nell'ordine del sito, `categories[]` |
| `show/{id}?preview=false` | GET, Bearer | `data.video[]`: parti con `urls.dash`/`urls.hls` firmati, `drm` (0/1), `type` (`ads` = sigla, `video` = spettacolo), `duration.ms`; `data.user_data.progress` = posizione salvata sul server |

Header richiesti su ogni chiamata alle API: `Current-Build`, `DeviceId`, `DeviceData` (JSON con la
struttura che il sito ricava da FingerprintJS), `Content-Language`, `Origin`, `Referer`;
`Authorization: Bearer` su tutte tranne login e snapshot. `DeviceId` è generato una sola volta e
conservato, perché il backend registra i dispositivi dell'account.

Le righe editoriali dello snapshot (Novità, TOP 10, generi tematici) sono le "categorie" mostrate nel
catalogo; le schede del dettaglio vengono dallo stesso snapshot, tenuto in memoria per la vita del
processo, quindi aprire una scheda non costa richieste. Solo la riproduzione interroga `show/{id}`,
sempre senza cache, perché gli URL sono firmati e a scadenza.

Riproduzione:

- Buffer in RAM: minimo 15 s, massimo 40 s, avvio con 1,5 s, ripartenza con 3 s; nessuna cache su
  disco.
- "Amplifica voce": passa-alto Butterworth del 4° ordine a 100 Hz (due biquad, Q 0,541 e 1,307),
  due peaking a 1,3 e 3 kHz (Q 1, +4,5 dB), compressore a canali collegati (−20 dBFS, 3:1, 5/150 ms,
  +3 dB) con smussamento della riduzione nel dominio logaritmico, limitatore tanh sopra 0,8,
  dissolvenza di 30 ms. Processore sempre presente nella catena del `DefaultAudioSink` (prima di
  Sonic); a effetto spento i byte vengono copiati senza conversione.
- URL scaduto (403/410): nuova risoluzione e ripartenza dalla stessa posizione e stato play/pausa,
  al massimo 2 tentativi consecutivi (contatore azzerato a ogni READY).
- Posizione: salvata ogni 10 s, a ogni arresto, al cambio spettacolo, a rimozione del task e alla
  distruzione del servizio; ripresa se oltre 5 s e non negli ultimi 15 s.
- Notifica e schermata di blocco: Play/Pausa, ±10 s, titolo e miniatura; il tocco riapre il player
  dello spettacolo in corso.

## 4. Scoperte durante sviluppo e test

- Media3 1.10 ha reso deprecati il costruttore di `DataSourceBitmapLoader` (sostituito dal Builder),
  `DefaultAudioSink.Builder.setEnableAudioTrackPlaybackParams` e `BaseAudioProcessor.onFlush()`
  senza argomenti. Verificato sul bytecode che `flush()` delega a `flush(StreamMetadata)` e questo a
  `onFlush(StreamMetadata)`: il DSP viene quindi ricreato su ogni percorso di flush anche
  sovrascrivendo solo la nuova firma. Rimuovendo l'opzione sull'AudioTrack il cambio di velocità
  resta a Sonic, che garantisce l'ordine "prima la voce, poi la velocità".
- Il test iniziale del limitatore pretendeva un'uscita strettamente minore di 1 per ingressi molto
  grandi: in doppia precisione tanh(21) vale esattamente 1, quindi la curva tocca il fondo scala senza
  superarlo. Corretto il test (strettamente sotto 1 per eccedenze realistiche, mai oltre 1 in
  assoluto); l'implementazione era corretta.
- Le tracce WebVTT aggiunte come configurazioni esterne non hanno flag di default: riabilitare il
  tipo testo non basta, all'attivazione serve un override esplicito del gruppo (preferendo la lingua
  del dispositivo).
- Gli errori del player arrivano alla UI tramite la sessione senza la catena delle cause: la UI
  classifica solo per codice errore, mentre l'analisi del codice HTTP avviene nel servizio.

## 5. Riepilogo finale

Consegnato: progetto Android completo e compilabile nella cartella `proscenia`, con tutte le funzioni
richieste e il log degli errori su file (avvisi, errori e crash con stack trace, intestazione con
dispositivo e versione, URL privati della query string, rotazione a 1 MB).

Esito: `assembleDebug` riuscito senza warning del compilatore; `testDebugUnitTest` con 16 test JVM
superati (DSP, limitatore, regola di ripresa, velocità, ripulitura dei log).

Limiti noti:

- Contratto API e DRM: superati dall'allineamento descritto al §7, che ne riporta i limiti aggiornati.
- Il Chromecast riceve solo l'URL: se la CDN richiede cookie o header di sessione, il Cast fallisce
  (il Default Media Receiver non li può inviare); "Amplifica voce" non si applica all'audio del Chromecast.
- Non è stato possibile un collaudo su dispositivo reale né contro il servizio reale: verificati solo
  compilazione e test JVM.
- Dopo una reinstallazione dell'app, su Android 10+ viene creato un nuovo file di log (MediaStore
  assegna i file all'installazione che li ha creati).

File toccati:

- `TODO.md`, `docs/client-android-proscaenia.md`
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `.gitignore`,
  `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`
- `app/build.gradle.kts`, `app/proguard-rules.pro`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/{strings,colors,themes}.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`,
  `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/java/com/icarocomix/proscaenia/ProscaeniaApp.kt`
- `.../core/logging/{AppLog,LogFileSink}.kt`
- `.../core/network/{UserAgent,UserAgentInterceptor,SessionCookieJar,CallAwait}.kt`
- `.../data/auth/{Credentials,CredentialStore,AuthApi,SessionManager,SessionInterceptor}.kt`
- `.../data/remote/{ProscaeniaEndpoints,ProscaeniaApi,ProscaeniaJsonMapper,JsonExtensions}.kt`
- `.../data/repository/{AuthRepositoryImpl,CatalogRepositoryImpl,StreamRepositoryImpl}.kt`
- `.../data/local/{PlaybackPreferences,PlaybackPositionStore}.kt`
- `.../di/AppContainer.kt`
- `.../domain/error/ProscaeniaExceptions.kt`, `.../domain/model/{Catalog,Playback}.kt`,
  `.../domain/repository/Repositories.kt`, `.../domain/policy/{ResumePolicy,SpeedPolicy}.kt`
- `.../playback/{PlaybackService,PlayerFactory,PlaybackRequests,ResolvedShow,PlaybackErrors,ExpiredUrlLoadErrorPolicy,SpeedLimitingPlayer}.kt`
- `.../playback/audio/{BiquadFilter,LinkedCompressor,SoftLimiter,VoiceBoostDsp,VoiceBoostAudioProcessor}.kt`
- `.../playback/cast/CastSupport.kt`
- `.../ui/MainActivity.kt`, `.../ui/navigation/AppNavHost.kt`, `.../ui/theme/Theme.kt`
- `.../ui/common/{UiMessage,Formatters,ViewModelSupport,StateViews}.kt`
- `.../ui/login/{LoginViewModel,LoginScreen}.kt`, `.../ui/catalog/{CatalogViewModel,CatalogScreen}.kt`,
  `.../ui/detail/{DetailViewModel,DetailScreen}.kt`
- `.../ui/player/{PlayerViewModel,PlayerScreen,CastButton,SpeedSheet}.kt`
- `app/src/test/java/com/icarocomix/proscaenia/{playback/audio/VoiceBoostDspTest,domain/policy/PolicyTest,core/logging/AppLogSanitizeTest}.kt`

## 6. Build di produzione e versionamento

Obiettivo: APK release compresso e non debuggabile, versione crescente a ogni compilazione, script
per le compilazioni successive.

Decisioni:

- Versione in `version.properties` (MAJOR, MINOR, PATCH manuali; BUILD automatico). Il build number
  viene incrementato in fase di configurazione Gradle solo se tra i task richiesti c'è un task che
  produce un pacchetto (assemble, bundle, install, build, package), non durante sync dell'IDE, clean o
  soli test. È anche il versionCode, quindi ogni APK si installa sopra il precedente. Una build fallita
  consuma comunque il numero: scelta voluta, per non avere mai due APK diversi con lo stesso codice.
- Release con R8 (minificazione e offuscamento), rimozione delle risorse non usate, esclusione dei
  metadati di libreria inutili a runtime, `debuggable` disattivato.
- Firma: keystore di produzione da variabili d'ambiente (`PROSCAENIA_KEYSTORE_FILE`,
  `PROSCAENIA_KEYSTORE_PASSWORD`, `PROSCAENIA_KEY_ALIAS`, `PROSCAENIA_KEY_PASSWORD`), mai da file
  versionati; in loro assenza firma con la chiave di debug locale, adatta all'installazione diretta ma
  non alla pubblicazione su uno store. Alternativa scartata: file di proprietà con le chiavi nel
  progetto, per il rischio di versionare segreti.
- Script `build.sh`: verifica memoria disponibile (almeno 2,5 GB) e assenza di altre build Gradle,
  esegue opzionalmente i test (`--test`) e il bundle AAB (`--bundle`), copia in `dist/` APK, SHA-256 e
  mappa di offuscamento R8 compressa, verifica la firma con `apksigner` e segnala se è di debug.

Scoperte e correzioni:

- Nel Kotlin DSL di Gradle il nome qualificato `java.util.Properties` non si risolve (`java` viene
  interpretato come l'estensione omonima): serve l'import esplicito.
- AGP 9 crea i test unitari solo per la variante debug: lo script esegue `testDebugUnitTest`, valido
  perché i test sono JVM puri e indipendenti dalla variante.
- Il task `collectReleaseDependencies` falliva in modo riproducibile con "Problems reading data from
  Binary store". Il task serve solo a incorporare nell'APK il blocco cifrato delle dipendenze destinato
  a Google Play: disattivato (`dependenciesInfo`), con il vantaggio di un pacchetto più leggero.
- Gli stack trace scritti nel file di log da una build release sono offuscati: per decodificarli serve
  la mappa R8 della stessa versione, che lo script ora archivia (`dist/proscaenia-<versione>-mapping.txt.gz`).

Esito: release 1.0.0.5 (versionCode 5), 4,7 MB contro i 28 MB della build di debug; 16 test superati;
manifest verificato (non debuggabile) e classi referenziate dal manifest mantenute da R8; firma con
chiave di debug, non essendo impostate le variabili del keystore di produzione. Non collaudata su
dispositivo.

File toccati: `version.properties`, `build.sh`, `app/build.gradle.kts`, `.gitignore`, `TODO.md`,
`docs/client-android-proscaenia.md`; prodotti in `dist/`.

## 7. Allineamento alle API reali della piattaforma

Obiettivo: sostituire il contratto ipotetico con quello effettivamente usato dal sito, a seguito
dell'errore "formato non atteso" mostrato dall'app al primo uso reale.

Diagnosi e metodo:

- Il dominio e i percorsi ipotizzati non esistevano: il server rispondeva con la home HTML e stato
  200, che il vecchio login considerava un successo; l'errore emergeva solo più tardi, in modo
  fuorviante, sul catalogo. Da qui la regola attuale: login riuscito solo se la risposta è JSON e
  contiene `access_token`.
- Il contratto è stato ricavato dal client web e verificato con una sonda a riga di comando
  (`tools/probe_api.py`), da eseguire a mano nel proprio terminale: chiede le credenziali in modo
  interattivo, non le salva e produce in `probe-output/` un report con la sola struttura delle
  risposte (token, dati personali e query string degli URL firmati vengono rimossi).

Scoperte empiriche:

- Senza l'header `Current-Build` il login risponde HTTP 411 "Current Build not found."; senza
  `DeviceId` e `DeviceData` valorizzati risponde 401 anche con credenziali corrette.
- Il login chiede il campo `email` (non `username`) e restituisce un token Bearer valido circa un
  anno: viene conservato cifrato e il login si ripete solo su un 401.
- Il catalogo completo è un unico documento pubblico (`catalog/snapshot`, oltre 200 spettacoli, righe
  editoriali e generi), quindi non serve paginare né autenticarsi per mostrarlo.
- Ogni spettacolo è servito come sequenza di parti: una sigla di 6 secondi (`type: ads`) e lo
  spettacolo vero e proprio. Tutte le parti sono DASH (con alternativa HLS) protette da DRM; il
  manifest dichiara sia Widevine sia PlayReady. Nei manifest esaminati non sono presenti tracce di
  sottotitoli.
- Il file di log risultava vuoto: su Android 10+ l'apertura in lettura di un file MediaStore appena
  registrato ma non ancora scritto falliva con ENOENT, interrompendo la scrittura. Corretto anche il
  nome del file, che compariva con doppia estensione: ora è `proscaenia-errori.txt`.

Decisioni:

- Riproduzione della sorgente DASH con Widevine, licenze dal server ufficiale della piattaforma
  (`drm.proscaenia.com`, integrazione KeyOS), con gli stessi header di dispositivo delle API.
  Alternativa scartata: HLS, che sul sito serve i dispositivi Apple e su Android non porterebbe vantaggi.
- Le richieste di licenza passano dallo stesso client OkHttp dei segmenti (User-Agent e header di
  piattaforma coerenti); senza configurazione esplicita Media3 userebbe un proprio client HTTP.
- Sigla iniziale saltata: accodarla come primo elemento renderebbe ambigua la posizione da salvare e
  riprendere, che deve riferirsi allo spettacolo.
- Ripresa: prevale la posizione locale (salvata ogni 10 s); in sua assenza si usa il progresso
  conservato dal server, così si riprende anche dopo aver guardato dal sito o da un altro dispositivo.
- Errori DRM mostrati con un messaggio dedicato, distinto da quelli di rete e di formato.
- Il `refresh_token` non viene usato: con un token di durata annuale un nuovo login con i dati di
  accesso salvati è più semplice ed è già gestito dal meccanismo di rinnovo a generazioni.

Esito: release 1.0.0.8 (versionCode 8, 4,7 MB) compilata con test e R8; 16 test JVM superati.

Limiti noti (il collaudo su dispositivo è descritto al §8):

- Chromecast: il Default Media Receiver non può inviare gli header di dispositivo al server licenze,
  quindi la trasmissione dei contenuti protetti verosimilmente non funziona.
- Il progresso di visione non viene inviato al server: la posizione salvata dall'app resta locale.

File toccati: `gradle.properties`, `.../core/network/{DeviceIdentity,PlatformHeadersInterceptor,UserAgent}.kt`,
`.../data/auth/{AuthApi,SessionManager,SessionInterceptor,CredentialStore}.kt`,
`.../data/remote/{ProscaeniaEndpoints,ProscaeniaApi,ProscaeniaJsonMapper}.kt`,
`.../data/repository/CatalogRepositoryImpl.kt`, `.../di/AppContainer.kt`,
`.../domain/error/ProscaeniaExceptions.kt`, `.../domain/model/Playback.kt`,
`.../playback/{PlaybackService,PlayerFactory,ResolvedShow,PlaybackErrors}.kt`,
`.../core/logging/LogFileSink.kt`, `.../ui/common/UiMessage.kt`, `app/src/main/res/values/strings.xml`,
`tools/probe_api.py`, `TODO.md`, `docs/client-android-proscaenia.md`.

## 8. Collaudo su dispositivo e limite di dispositivi dell'account

Obiettivo: verificare l'app sul dispositivo reale (Pixel 7, Android 17) e, emerso il limite di
dispositivi dell'account, fare in modo che l'app ne occupi uno solo e lo liberi quando viene lasciata.

Esito del collaudo:

- Catalogo reale, schede e riproduzione funzionanti: licenza Widevine ottenuta dal server ufficiale,
  decodifica tramite il decoder video protetto del dispositivo, stato di riproduzione regolare,
  buffer di circa 40 secondi come configurato, titolo e pulsanti ±10 s nella sessione multimediale.
- La ripresa dal progresso conservato sul server è confermata: il primo audio decodificato
  corrispondeva esattamente a `user_data.progress`, che è quindi espresso in millisecondi.

Difetti emersi e corretti:

- Crash al tocco su "Riproduci": nel ViewModel del player i listener erano dichiarati dopo il blocco
  di inizializzazione. Lo scope dei ViewModel esegue subito la coroutine sul thread principale e,
  con il catalogo già in memoria, la coroutine non sospendeva mai: i listener venivano letti prima di
  essere inizializzati e Media3 riceveva un riferimento nullo. Dichiarazioni spostate prima
  dell'inizializzazione.
- File di log ancora con il vecchio nome dopo l'aggiornamento: l'app memorizza il riferimento al file
  e un aggiornamento senza disinstallazione conserva i dati. La chiave del riferimento è ora versionata.
- Riproduzione in errore dopo uscita e rientro nello stesso processo: il fornitore del gestore DRM era
  condiviso tra player successivi e conservava il legame con il thread del primo. Ora ne viene creato
  uno per ogni player.
- Sinossi con entità HTML non decodificate: la decodifica è ora affidata al parser HTML di sistema.

Limite di dispositivi:

- Il backend consente 4 dispositivi per account e, raggiunto il limite, rifiuta il login con HTTP 401
  e codice `MAX Device`, lo stesso stato delle credenziali errate. L'app lo mostrava come "credenziali
  rifiutate"; ora lo riconosce e mostra un messaggio dedicato, senza rimandare alla schermata di login.
  La diagnosi è stata possibile registrando nel file di log il messaggio d'errore del server (mai il
  corpo intero né token o credenziali).
- Concausa: la sonda generava un identificativo di dispositivo casuale a ogni esecuzione, occupando
  ogni volta un posto. Ora lo genera una volta sola e lo riusa.
- Dal client web: `GET user/device` elenca i dispositivi, `DELETE user/device/{device_id}` ne rimuove
  uno, e il `device_id` coincide con l'header `DeviceId`. Con il limite raggiunto nessun nuovo
  dispositivo ottiene un token, quindi un posto si può liberare solo da un dispositivo già collegato.

Rilascio automatico del posto all'uscita:

- Decisione: rimuovere il proprio dispositivo (`DELETE user/device/{DeviceId}`) quando l'app viene
  lasciata, cancellare il token locale e conservare le credenziali cifrate. Il login successivo
  avviene da solo alla prima riproduzione; sfogliare il catalogo, che è pubblico, non occupa posti.
- Alternativa scartata: eliminare in automatico altri dispositivi dell'account (per esempio il più
  vecchio) quando il limite è raggiunto. Potrebbe scollegare un dispositivo in uso e annullerebbe di
  fatto il limite dell'abbonamento.
- Definizione di "app lasciata": interfaccia non visibile e nessuna riproduzione attiva (spettacolo in
  riproduzione, in buffering o in pausa). Android non notifica la chiusura dalle app recenti quando
  non ci sono servizi attivi, ma l'uscita dalla schermata precede sempre la chiusura e copre quel caso.
  La pausa mantiene la sessione, per poter riprendere dalla notifica.
- Scoperta: il primo criterio, basato sulla chiusura del servizio di riproduzione, non scattava mai,
  perché i controlli multimediali di sistema tengono in vita il servizio anche a riproduzione ferma.
- Scoperta: la rimozione riuscita del dispositivo invalida anche il token; il logout viene quindi
  eseguito solo come ripiego quando la rimozione fallisce.
- Un rilascio non riuscito (per esempio senza rete) non ha conseguenze: il login successivo usa lo
  stesso `DeviceId` e riprende lo stesso posto invece di occuparne uno nuovo.

Verifica: due cicli consecutivi riproduzione → pausa → chiusura dalle app recenti → nuova
riproduzione, il secondo nello stesso processo: rimozione del dispositivo con HTTP 200 a ogni uscita,
login automatico al rientro, riproduzione regolare. Release 1.0.0.15, 16 test JVM superati.

Limiti noti:

- Durante una chiamata telefonica Android non accetta i tasti multimediali iniettati da strumenti di
  test; i tasti di cuffie e dispositivi Bluetooth, inviati dal sistema, non sono stati provati.
- Se il limite è raggiunto da altri dispositivi mentre l'app non è in uso, la riproduzione successiva
  fallisce con il messaggio dedicato finché non si libera un posto dal sito.

File toccati: `.../ui/player/PlayerViewModel.kt`, `.../core/logging/LogFileSink.kt`,
`.../data/auth/{AuthApi,SessionManager,SessionInterceptor,SessionAutoRelease}.kt`,
`.../data/remote/{ProscaeniaEndpoints,ProscaeniaJsonMapper}.kt`, `.../di/AppContainer.kt`,
`.../domain/error/ProscaeniaExceptions.kt`, `.../playback/{PlaybackService,PlaybackErrors,PlayerFactory}.kt`,
`.../ui/MainActivity.kt`, `.../ui/common/UiMessage.kt`, `app/src/main/res/values/strings.xml`,
`tools/probe_api.py`, `TODO.md`, `docs/client-android-proscaenia.md`.

## 9. Credenziali solo al primo avvio e ritentativi automatici

Obiettivo: dopo il primo accesso, in cui le credenziali vengono verificate con un login reale prima
di essere salvate, l'app non deve mai più chiederle; ogni interruzione di connessione o rifiuto
momentaneo deve risolversi con ritentativi automatici, senza intervento dell'utente.

Situazione di partenza (dalla revisione del codice):

- Catalogo, scheda e player mostravano il pulsante "Aggiorna credenziali" quando l'accesso
  automatico con le credenziali salvate veniva rifiutato, e il pulsante riportava alla schermata di
  login. Era l'unica via, oltre al primo avvio, da cui le credenziali venivano richieste.
- Nessuna chiamata API veniva ripetuta: un errore di rete durante il login automatico (eseguito
  nell'interceptor di sessione) o durante il caricamento del catalogo arrivava subito all'utente.
- Il servizio di riproduzione recuperava da solo solo gli URL scaduti (403/410); una caduta di rete
  lasciava il player fermo sull'errore fino a un "Riprova" manuale.

Decisioni:

- Il login resta raggiungibile solo come destinazione iniziale, quando non esistono credenziali
  salvate. Rimossi pulsante, navigazione e stato "richiede credenziali" da tutte le schermate.
  Alternativa scartata: mantenere il pulsante solo per il rifiuto persistente; contraddice il
  requisito e un rifiuto delle credenziali già verificate è con buona probabilità momentaneo.
- Ritentativo a due livelli:
  1. ogni chiamata alle API (e quindi anche il login automatico che avviene al suo interno) viene
     ripetuta sugli errori transitori con attese crescenti di 1, 2, 4 e 8 secondi (5 tentativi in
     circa 15 secondi). Transitori: errori di trasporto, HTTP 5xx, 408, 429 e il 401 residuo dopo un
     rinnovo del token. Il rifiuto dell'accesso automatico è ripetuto una sola volta, per non far
     scattare la protezione anti forza bruta del backend. Non ripetuti: abbonamento non attivo,
     limite di dispositivi, contenuto inesistente, risposta malformata;
  2. le schermate ferme su un errore recuperabile riprovano da sole al ritorno della rete, al
     ritorno dell'app in primo piano e, con l'app visibile, una volta al minuto.
- I ritentativi delle schermate sono sospesi con l'app in background: un nuovo tentativo potrebbe
  rifare il login e rioccupare il posto dispositivo appena liberato all'uscita (§8).
- Il primo login usa lo stesso meccanismo per rete e server, ma mostra subito il rifiuto delle
  credenziali, che in quel momento significa davvero password errata. Le credenziali continuano a
  essere salvate solo dopo un login riuscito; un "MAX Device" al primo accesso non le salva, perché
  non è documentato che il backend verifichi la password prima del limite di dispositivi.
- Servizio di riproduzione: oltre agli URL scaduti recupera gli errori di rete del player. Se il
  dispositivo è in linea ricarica dopo 3 secondi, altrimenti attende il ritorno della rete; in
  entrambi i casi richiede un link nuovo e riparte dalla stessa posizione con lo stesso stato
  play/pausa. Limite di 3 tentativi consecutivi, azzerato quando la riproduzione torna pronta o
  quando la rete ritorna. La UI non ritenta gli errori del player, per non causare due ricaricamenti
  in parallelo con il servizio.
- La rete è osservata con il callback della rete predefinita di sistema, considerando "in linea"
  una rete con capacità INTERNET senza attendere la validazione, che su alcune reti arriva in ritardo:
  un tentativo prematuro fallisce in fretta ed è comunque ripetuto dal primo livello.

Bug corretti durante la revisione:

- Player: "Riprova" (e ora il ritentativo automatico) senza controller già collegato poteva avviare
  un secondo collegamento al servizio mentre il primo era in corso, con due controller che inviavano
  lo stesso spettacolo; ora un collegamento in corso blocca i successivi e il future di un
  collegamento fallito viene rilasciato prima di crearne uno nuovo.
- Player: un errore restava a schermo anche dopo che il servizio aveva ripristinato la riproduzione;
  ora sparisce quando lo spettacolo della schermata torna pronto senza errori.
- `PlaybackErrors`: il commento di documentazione di `toSessionError` era agganciato alla funzione
  sbagliata.

Consegnato:

- Nuovi componenti: `AutomaticRetry` (politica di ritentativo e classificazione degli errori),
  `NetworkMonitor` (stato della rete e riconnessioni), `AutoRetryTriggers` (eventi di ritentativo
  delle schermate). Il contenitore delle dipendenze espone ora un unico punto per la visibilità
  dell'interfaccia e per lo stato della riproduzione, usati sia dal rilascio del posto dispositivo
  sia dai ritentativi.
- Nuovo messaggio per l'accesso automatico rifiutato, che non invita a reinserire le credenziali.
- Esito: `testDebugUnitTest` e `assembleDebug` riusciti; 23 test JVM superati, di cui 7 nuovi su
  `AutomaticRetry` (sequenza delle attese, numero massimo di tentativi, trattamento del rifiuto
  delle credenziali al primo accesso e in automatico, classificazione degli errori). Il
  comportamento su dispositivo (caduta e ritorno della rete durante la riproduzione e sul catalogo)
  non è ancora stato collaudato.

Limiti noti:

- Se la password viene cambiata dal sito, l'app continua a riprovare l'accesso automatico (una volta
  al minuto con l'app visibile) senza poterlo completare: per inserire la nuova password bisogna
  cancellare i dati dell'app, che riporta al primo avvio.
- Se l'archivio cifrato diventa illeggibile (chiave del Keystore invalidata), le credenziali sono
  perse per definizione e al successivo avvio compare di nuovo il login.
- Dopo 3 recuperi falliti del player con rete presente (per esempio server dei video in avaria) la
  riproduzione resta ferma fino a un "Riprova" o al successivo cambio di rete.

File toccati: `.../core/network/{AutomaticRetry,NetworkMonitor}.kt` (nuovi),
`.../ui/common/AutoRetryTriggers.kt` (nuovo), `.../ui/common/{StateViews,UiMessage}.kt`,
`.../ui/navigation/AppNavHost.kt`, `.../ui/MainActivity.kt`,
`.../ui/catalog/{CatalogScreen,CatalogViewModel}.kt`, `.../ui/detail/{DetailScreen,DetailViewModel}.kt`,
`.../ui/player/{PlayerScreen,PlayerViewModel}.kt`, `.../ui/login/LoginViewModel.kt`,
`.../data/remote/ProscaeniaApi.kt`, `.../data/repository/AuthRepositoryImpl.kt`,
`.../di/AppContainer.kt`, `.../playback/{PlaybackService,PlaybackErrors}.kt`,
`app/src/main/res/values/strings.xml`,
`app/src/test/java/com/icarocomix/proscaenia/core/network/AutomaticRetryTest.kt` (nuovo),
`TODO.md`, `docs/client-android-proscaenia.md`.
