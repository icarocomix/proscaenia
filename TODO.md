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
