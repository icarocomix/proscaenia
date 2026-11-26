# TODO — Proscaenia Player (client Android)

Stato di avanzamento: riprendere dal primo punto non spuntato.

- [x] 1. Struttura Gradle (settings, version catalog, wrapper, gradle.properties a basso consumo)
- [x] 2. Manifest, risorse (tema scuro AppCompat, stringhe, icona adattiva)
- [x] 3. Rete e autenticazione (User-Agent, CookieJar, SessionManager, interceptor, CredentialStore cifrato)
- [x] 4. Dati e dominio (modelli, endpoint, parser JSON, repository, preferenze, posizioni, policy)
- [x] 5. Audio "Amplifica voce" (biquad, compressore, limitatore, DSP, AudioProcessor)
- [x] 6. Motore di riproduzione (PlayerFactory, policy errori, SpeedLimitingPlayer, Cast, PlaybackService)
- [x] 7. UI Compose (tema, navigazione, login, catalogo, dettaglio, player, pulsante Cast)
- [x] 8. Test unitari JVM (DSP, ResumePolicy, SpeedPolicy)
- [x] 9. Build `assembleDebug` + `testDebugUnitTest` (sequenziale, dopo controllo risorse)
- [x] 11. Log degli errori su file accessibile dall'esplora risorse (Download/Proscaenia), crash inclusi
- [x] 12. Build + test dopo il log su file
- [x] 10. Documento `docs/client-android-proscaenia.md`

## Build di produzione

- [x] 13. `version.properties` con build number incrementato a ogni compilazione
- [x] 14. Firma release da variabili d'ambiente (fallback chiave di debug)
- [x] 15. Script `build.sh` (controllo risorse, test opzionali, release, copia in `dist/`)
- [x] 16. Prima build release minificata + verifica firma/versione
- [x] 17. Aggiornamento documentazione

## Allineamento alle API reali (proscaenia.com/backoffice/api)

- [x] 18. Diagnosi errore "formato non atteso": dominio e API ipotizzati inesistenti (HTML 200)
- [x] 19. Sonda `tools/probe_api.py` e contratto reale (login, header dispositivo, snapshot, show, DRM)
- [x] 20. Fix file di log vuoto (ENOENT su apertura in lettura di file MediaStore non ancora creato)
- [x] 21. Nome file di log senza doppia estensione (`proscaenia-errori.txt`)
- [x] 22. Rete: base URL reale, header Current-Build/DeviceId/DeviceData/Origin/Referer, DeviceId persistente
- [x] 23. Login con token Bearer (access/refresh), rifiuto di risposte non JSON
- [x] 24. Catalogo da `/catalog/snapshot` (categorie, righe, spettacoli) e scheda da `/show/{id}`
- [x] 25. Riproduzione: sorgente da `video[]`, DRM Widevine con server licenze ufficiale, intro iniziale
- [x] 26. Build, installazione sul Pixel 7 via ADB e verifica con il file di log
      - [x] 26a. Installata 1.0.0.8: login e catalogo reale OK; crash al tocco su "Riproduci" (NPE listener null in PlayerViewModel)
      - [x] 26b. Fix ordine di inizializzazione in PlayerViewModel; fix file di log ancora con il vecchio nome (chiave Uri versionata)
      - [x] 26c. Build 1.0.0.9 installata: player OK, ma login rifiutato → diagnostica (1.0.0.10): 401 "MAX Device" (limite dispositivi dell'account, non credenziali)
      - [x] 26e. 1.0.0.11: DeviceLimitException con messaggio dedicato; sonda con DeviceId stabile (le esecuzioni precedenti registravano dispositivi nuovi)
      - [x] 26g. Client web analizzato: GET /user/device (data[].device_id), DELETE /user/device/{device_id}, GET /auth/logout; limite dichiarato 4 dispositivi; con limite pieno nessun token → gestione possibile solo da un dispositivo già collegato
      - [x] 26h. Sonda: lettura elenco dispositivi (sola lettura) + messaggio esplicito su "MAX Device"
      - [x] 26f. Posto liberato dal sito
      - [x] 26i. Uscita automatica: all'uscita dell'app DELETE /user/device/{DeviceId} + GET /auth/logout, token locale cancellato
            (device_id del sito == header DeviceId, verificato nel client web)
          - [x] endpoint + AuthApi.releaseDevice
          - [x] SessionManager.releaseSession (sotto lock, best effort, cancella sempre il token locale)
          - [x] rilevamento uscita: UI non visibile e servizio di riproduzione assente
          - [x] criterio corretto: "riproduzione attiva" (READY/BUFFERING) invece di "servizio vivo" (i controlli di sistema tengono vivo il servizio)
          - [x] sinossi: entità HTML decodificate con HtmlCompat (&rsquo; visibile nella notifica)
          - [x] 1.0.0.14 con log diagnostici di sessione (login, ripresa token, rilascio, 401)
          - [x] test su Pixel 7 sbloccato: 2 cicli riproduzione → pausa → swipe → rientro, rimozione HTTP 200, login automatico
          - [x] fix DRM al rientro nello stesso processo (DrmSessionManagerProvider per player); logout solo se la rimozione fallisce (1.0.0.15)
      - [x] 26j. Verifica riproduzione DRM sul Pixel 7: Widevine + decoder sicuro OK, PLAYING, ripresa da user_data.progress (ms confermati)
      - [x] 26d. Documentazione aggiornata (§7 e nuovo §8)
- [x] 27. Aggiornamento documentazione

## Credenziali solo al primo avvio + ritentativi automatici

- [x] 28. Rimozione del percorso "Aggiorna credenziali" (ErrorState, schermate, navigazione, ViewModel, stringa)
- [x] 29. `AutomaticRetry` (backoff 1-2-4-8 s) su tutte le chiamate API e sul primo login (solo errori transitori)
- [x] 30. `NetworkMonitor`: ritentativo automatico al ritorno della rete (catalogo, scheda, player)
- [x] 31. PlaybackService: recupero automatico anche degli errori di rete (subito se online, al ritorno della rete altrimenti)
- [x] 32. Player UI: errore rimosso da solo quando la riproduzione riparte; fix KDoc PlaybackErrors
- [x] 33. Test unitari AutomaticRetry + build/test
- [x] 34. Documentazione

## Lag in riproduzione: licenza offline, buffer, download offline

- [x] 35. Sonda licenza Widevine offline (solo build debug, pulsante nella scheda): manifest DASH → OfflineLicenseHelper → esito nel log
- [x] 36. Build debug + installazione Pixel 7 + esecuzione sonda e lettura log
- [ ] 37. Diagnosi lag e regolazione buffer (LoadControl, ABR)
- [x] 38. Download offline: NON FATTIBILE — KeyOS risponde 200 + JSON "Widevine license generation failed (MISSING_RENEWAL_SIGNING_KEY)" alla richiesta offline
- [ ] 39. Documentazione
- [x] 37a. Diagnostica riproduzione (PlaybackDiagnostics) + buffer 30–120 s / 96 MB / ripartenza 8 s: sul Pixel 7 0 stalli in 5 min; lag segnalato solo in Cast alla TV
- [x] 40. (testato dall utente sulla TV; APK pronto: dist/proscaenia-tv-prova-debug.apk, LEANBACK_LAUNCHER + banner + log DeviceCapabilities; ADB TV "unauthorized", installazione manuale) Prova app su TV "Smart TV Pro" (Cast integrato, 192.168.1.61): ADB in rete, installazione APK debug, livello Widevine, login, riproduzione, diagnostica
- [ ] 41. Decisione: app Android TV (UI telecomando + pre-scaricamento cifrato) vs server locale per Chromecast
- [x] 42. Build release minificata 1.0.0.22 (4,7 MB, 23 test OK) + avvio verificato sul Pixel 7

## Prova tecnica Samsung Tizen (UE43RU7400, Tizen 5.0) — cartella `tizen/`

- [x] 43. Raccolta contratto (API, header, licenze Widevine/PlayReady dal client web: `drm.proscaenia.com?drm-type=…`)
- [x] 44. Shaka Player 4.16.58 (Apache-2.0) incluso localmente in `tizen/lib/`
- [x] 45. Scheletro app web Tizen: `config.xml`, `index.html`, CSS, navigazione da telecomando
- [x] 46. Diagnostica: ambiente, EME (Widevine/PlayReady), CORS su catalogo, login, scheda, licenza
- [x] 47. Riproduzione: Shaka (EME) Widevine/PlayReady + AVPlay nativo con licenza gestita in JS
- [x] 48. Rilascio posto dispositivo all'uscita; log a schermo + invio opzionale al PC (`tools/tizen_log_receiver.py`)
- [x] 48b. Esportazione log: ZIP (JSZip 3.10.1, MIT) su USB/Download della TV, invio dello ZIP al PC, condivisione nativa se presente (assente su Tizen 5.0)
- [x] 49. Script di pacchetto/installazione (`tizen/build-wgt.sh`) e guida `tizen/README.md`
- [x] 50. Verifica sintassi JS + documento `docs/prova-tecnica-tizen.md`
- [ ] 51. Esecuzione sulla TV (a cura dell'utente) e lettura del log
      - [x] 51a. Installazione Tizen Studio 6.1 CLI in ~/tizen-studio (installer in ~/Scaricati/tizen-installer)
      - [x] 51b. Package Manager: TV Extensions + Samsung Certificate Extension
      - [x] 51c. Certificato autore + profilo di firma `proscaenia`
      - [ ] 51d. Developer Mode sulla TV (utente) e `sdb connect`
      - [ ] 51e. Pacchetto, installazione e avvio (`build-wgt.sh --tv`)

## Interfaccia TV: layout 10-foot, OSD player, audio in background, parità Tizen

Android (solo modalità TV; telefono e Cast invariati):
- [x] 52. Rilevamento TV + densità normalizzata a 960 dp di larghezza (canvas 10-foot)
- [x] 53. Componenti TV con focus visibile (bordo oro + ingrandimento) e margini di overscan
- [x] 54. Catalogo TV compatto; scheda in due colonne con "Riproduci" focalizzato all'apertura
- [x] 55. OSD Compose del player su TV: Indietro 10 s, Play/Pausa, Stop, Avanti 10 s, barra di avanzamento, funzioni accessorie; tasti multimediali; auto-nascondimento 5 s
- [x] 56. Stato "in riproduzione" condiviso (container) + barra "In riproduzione" con Torna al player / Stop in catalogo e scheda
- [x] 57. Audio all'uscita dall'app su TV: stop del player quando l'interfaccia non è visibile; ripresa in pausa al rientro; schermo acceso durante l'audio in sottofondo
- [x] 58. Build debug + test unitari (dopo controllo risorse)

Tizen:
- [x] 59. Navigazione spaziale generica (nav.js)
- [x] 60. Catalogo/scheda/login/player/diagnostica come schermate, stile e etichette allineati ad Android
- [x] 61. OSD player e barra "In riproduzione"; stop su visibilitychange/uscita
- [x] 62. Verifica sintassi JS + pacchetto
- [x] 63. Documento docs/interfaccia-tv-osd-audio.md
- [x] 64. Tizen: tentativi automatici su tutti i player (preferito per primo, timeout 45 s), errore solo se falliscono tutti; documentazione
- [x] 65. Tizen: scheda dettaglio spostata in alto dopo l'errore del player (scroll di pagina programmatico azzerato in nav.js)

## Tizen: credenziali inserite una sola volta (parità con Android §8-§9)

- [x] 66. `tizen/js/secure-store.js`: custodia cifrata (AES-GCM, chiave non estraibile in IndexedDB) con ripiego su localStorage
- [x] 67. `api.js`: signIn (salva solo dopo login riuscito), ensureSession con login automatico, rinnovo su 401, ritentativi transitori
- [x] 68. `app.js`/`index.html`: login solo senza credenziali salvate, nessun rimando al login su rifiuto, "Cambia account" in Diagnostica
- [x] 69. Verifica sintassi JS + banco di prova Node (cifrato e ripiego) + documentazione (`docs/tizen-credenziali-salvate.md`, README)
- [ ] 70. Verifica sulla TV reale (a cura dell'utente): primo login, uscita con Indietro, riavvio senza login, riproduzione con login automatico
