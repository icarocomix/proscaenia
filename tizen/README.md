# Proscaenia — prova tecnica per Samsung Tizen

App web Tizen (`.wgt`) che verifica sulla TV reale (UE43RU7400, Tizen 5.0) se un client Proscaenia
nativo è realizzabile. Non è il client definitivo e non tocca l'app Android né il percorso Cast.

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

- **▲▼** spostano il focus, **◀▶** pure (ma dentro i campi di testo muovono il cursore), **OK** esegue.
- Ordine consigliato: *Ambiente ed EME* → *Test API (CORS)* → email/password → *Accedi* →
  *Carica spettacoli* → scelta → una variante di riproduzione per volta.
- In riproduzione: **OK** o Play/Pausa mette in pausa, **◀▶** salta di ±10 s, **⏪⏩** di ±30 s,
  **Indietro** ferma e torna al pannello.
- **Indietro** dal pannello libera il posto dispositivo ed esce.

Il posto dispositivo resta occupato finché non si esce con Indietro o con il pulsante dedicato. Se
l'app viene chiusa in modo brusco (tasto Home, spegnimento), al riavvio successivo un avviso lo
segnala. Il posto si libera poi con il pulsante oppure dalla pagina dispositivi del sito.

## Contenuto

- `config.xml`, `index.html`, `icon.png`, `css/`: struttura dell'app.
- `js/log.js`: log, sanificazione e invio al PC. `js/api.js`: contratto API.
  `js/diagnostics.js`: controlli. `js/player-shaka.js`, `js/player-avplay.js`: i due motori.
  `js/export.js`: ZIP ed esportazione. `js/app.js`: interfaccia e flusso.
- `lib/`: Shaka Player 4.16.58 (Apache-2.0) e JSZip 3.10.1 (MIT/GPLv3, usato con licenza MIT),
  inclusi localmente con le rispettive licenze.
