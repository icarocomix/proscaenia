# Audio: "Amplifica voce" (miglioramento dell'intelligibilità del parlato)

Documento di categoria per l'elaborazione audio che rende più comprensibile il parlato nelle
registrazioni di spettacoli dal vivo. È scritto per essere riusato anche in altri progetti: la catena
è indipendente dalla piattaforma (lavora su campioni float interlacciati) e i parametri sono
riportati per intero, con il motivo di ciascuno.

## 1. Obiettivo

Un pulsante che, acceso, rende la voce degli attori più chiara e più costante di livello in
registrazioni teatrali: microfoni lontani dal palco, sala riverberante, fondo di ventilazione e
pubblico, attori che passano dal sussurro all'urlo. Requisiti:

- nessuna regolazione da parte dell'utente: la catena si adatta da sola al materiale;
- costo di calcolo compatibile con box Android TV di fascia bassa;
- accensione e spegnimento durante la riproduzione senza click;
- a effetto spento, uscita identica bit per bit all'originale;
- nessuna latenza percepibile per il sincronismo labiale.

## 2. Analisi del materiale di riferimento

La taratura nasce dalla misura di due registrazioni reali di una recita (mono, 44,1 kHz, AAC; 14 s
e 26 s). Per ogni file sono stati misurati loudness integrata e intervallo di loudness (EBU R128),
spettro medio in terzi d'ottava rispetto a 1 kHz, spettro separato dei momenti di parlato e delle
pause, distribuzione del livello RMS su finestre da 50 ms (distacco tra 95° e 10° percentile, cioè
tra parlato e pause) e profondità di modulazione dell'inviluppo a 2–8 Hz per bande d'ottava (500 Hz,
1, 2, 4 kHz), un indicatore della distinzione tra sillabe vicino al principio dell'indice STI.

Difetti ricorrenti emersi:

- **crollo degli acuti**: da 1,6–2,5 kHz in su lo spettro scende di 9–15 dB rispetto a 1 kHz e
  arriva a −25/−29 dB a 6–8 kHz; è la banda delle consonanti, da cui l'effetto "ovattato";
- **fondo grave costante**: nel secondo file sotto i 400 Hz la differenza tra parlato e pause è
  solo di 1–3 dB (rumore di sala o ventilazione che non porta informazione);
- **eccesso intorno a 400–500 Hz** in entrambi i file (timbro "scatolato");
- **pause poco silenziose**: tra parlato e pause solo 10 dB (secondo file) o 18 dB (primo);
- i picchi di bassa frequenza a 200–400 Hz sono fondamentali di voci acute (probabilmente di
  bambini), non risonanze della sala: non vanno attenuati con filtri stretti.

Dalla misura della catena precedente (passa-alto 100 Hz, due campane +4,5 dB a 1,3 e 3 kHz,
compressore veloce −20 dBFS 3:1 con +3 dB di compensazione) è emerso perché l'effetto risultava poco
udibile e in parte controproducente: alzava la loudness di soli 0,4–0,9 LU, la campana a 1,3 kHz
rinforzava la banda già dominante, e il compressore veloce con compensazione fissa alzava le pause
(riverbero e rumore) di circa 3 dB riducendo la profondità di modulazione (a 2 kHz da 0,90 a 0,74 nel
primo file, da 0,43 a 0,41 nel secondo), cioè peggiorando la distinzione delle sillabe.

## 3. Catena in vigore

Ordine di elaborazione, per ogni frame:

| # | Stadio | Parametri | Motivo |
|---|---|---|---|
| 1 | Mid/side (solo stereo) | componente laterale (L−R)/2 × 0,5 (−6 dB) | la voce è al centro; riverbero e pubblico sono decorrelati e stanno nei lati |
| 2 | Riduzione adattativa del rumore | vedi §4 | abbassa il fondo stimato fino a 12 dB e segnala la presenza di parlato |
| 3 | Passa-alto | Butterworth 4° ordine a 120 Hz (due biquad, Q 0,541 e 1,307) | toglie rimbombo e fondo sotto la voce |
| 4 | Scaffale basso | 250 Hz, −8 dB, Q 0,707 | attenua il fondo grave di sala |
| 5 | Campana | 450 Hz, −4 dB, Q 1 | toglie il timbro scatolato |
| 6 | Campana di presenza | 3 kHz, +3 dB, Q 1 | rinforza le consonanti |
| 7 | Scaffale alto | da 2 kHz, +6 dB, Q 0,707 | compensa il crollo degli acuti |
| 8 | Livellatore lento | RMS su 0,4 s, bersaglio −17 dBFS, guadagno tra −6 e +12 dB, salita 1,5 s, discesa 0,3 s, fermo sotto −50 dBFS o in assenza di parlato | avvicina sussurri e battute forti senza toccare il contrasto sillaba/pausa |
| 9 | Compressore collegato | −12 dBFS, 2,5:1, attacco 10 ms, rilascio 200 ms, nessuna compensazione | contiene solo le battute urlate |
| 10 | Limitatore di picco | tetto −2 dBFS, attacco istantaneo, rilascio 60 ms | evita la saturazione senza latenza di look-ahead |
| 11 | Limitatore morbido | tanh sopra 0,8 | ultima difesa contro il clipping |

Risposta complessiva dell'equalizzazione a 48 kHz: −56 dB a 30 Hz, −15 dB a 100 Hz, −5 dB a
300–450 Hz, 0 dB a 1 kHz, +4,5 dB a 2 kHz, +8 dB a 3–4 kHz, +6/+7 dB oltre 6 kHz (inclinazione di
circa +13 dB tra 300 Hz e 3 kHz).

Tutti gli stadi dinamici usano un unico guadagno per tutti i canali (rivelatori sul massimo o sulla
media tra i canali), così l'immagine stereo non si sposta. Filtri in Forma Diretta II trasposta in
doppia precisione, coefficienti dall'"Audio EQ Cookbook" di R. Bristow-Johnson; frequenze limitate a
0,45 × la frequenza di campionamento per la stabilità con flussi a bassa frequenza di campionamento.

Accensione e spegnimento: dissolvenza incrociata lineare di 30 ms tra originale ed elaborato; a
dissolvenza conclusa con effetto spento i campioni passano invariati; alla riaccensione dopo un
bypass completo, e dopo ogni seek, lo stato di filtri, stima del rumore e dinamica viene azzerato.

## 4. Riduzione adattativa del rumore

- **Analisi/sintesi**: STFT a 512 punti con sovrapposizione del 50% (salto di 256), finestra radice
  di Hann periodica sia in analisi sia in sintesi: con guadagno unitario la ricostruzione è esatta.
  FFT complessa radix-2 con tabelle precalcolate, nessuna allocazione nel thread audio.
- **Stima del rumore per bin** ("minimum statistics" semplificato, R. Martin 2001): periodogramma
  smussato (coefficiente 0,85), minimo su una finestra scorrevole di circa 1,5 s divisa in 8
  sotto-finestre, moltiplicato per un fattore di correzione 2,3 calibrato su rumore bianco
  stazionario (il minimo sottostima la media). In 1,5 s ogni banda attraversa almeno una
  micro-pausa del parlato, quindi la stima rappresenta il fondo e segue i suoi cambiamenti
  (verificato: un fondo che cresce di 20 dB torna attenuato di oltre 7 dB entro pochi secondi).
- **Guadagno**: Wiener con SNR a priori "decision-directed" (Ephraim–Malah, coefficiente 0,98), che
  limita il rumore musicale; pavimento a −12 dB.
- **Avvio**: finché la prima finestra di stima non è completa (circa 1,5 s dall'avvio o da un seek)
  il guadagno è unitario.
- **Presenza di parlato**: vera quando l'energia smussata nella banda 300–4000 Hz supera di 6 dB il
  rumore stimato; il livellatore aggiorna il guadagno solo in sua presenza, così nelle pause lunghe
  non alza il fondo.
- **Guadagno comune** ai canali, calcolato sulla potenza media.
- **Latenza**: 512 campioni (circa 11 ms a 48 kHz, 12 ms a 44,1 kHz), sotto la soglia di
  percezione del sincronismo labiale. Durante i 30 ms della dissolvenza originale (non ritardato) ed
  elaborato (ritardato) sono sfasati di questa quantità: breve effetto di pettine accettato pur di
  non ritardare l'audio a effetto spento.

## 5. Decisioni e alternative scartate

- **Campana a 1,3 kHz (catena precedente)** → rimossa: rinforzava la banda già più forte invece di
  colmare il crollo sopra i 2 kHz; sostituita da scaffale alto + campana a 3 kHz.
- **Compressore veloce con compensazione fissa come unico stadio dinamico** → sostituito da
  livellatore lento + compressore morbido: il primo segue la frase e non la sillaba, quindi alza le
  battute deboli senza schiacciare il contrasto sillaba/pausa.
- **Espansore verso il basso nelle pause** (attenuazione di ciò che sta 15–18 dB sotto il livello del
  parlato) → scartato: sul materiale reale le pause sono solo 10–18 dB sotto il parlato, l'effetto
  misurato era nullo, e un espansore rischia di tagliare le consonanti deboli. La riduzione spettrale
  del rumore ottiene lo stesso scopo in modo selettivo per frequenza.
- **Stima del rumore con inseguimento continuo del minimo (Doblinger 1995)** → scartata: con attori
  che parlano quasi senza pause la stima risaliva verso la voce e la riduzione toglieva 6–8 dB di
  parlato. In una prima variante con crescita moltiplicativa, inoltre, una stima partita da zero
  (silenzio iniziale o dopo un seek) restava a zero e la riduzione non agiva mai.
- **Pavimento di riduzione a −15 dB o più** → scartato: guadagno di misura modesto rispetto a −12 dB
  e rischio maggiore di timbro "subacqueo" e rumore musicale.
- **Livellatore con stato iniziale "già a bersaglio"** → corretto: nella discesa del rivelatore
  verso un silenzio il livello restava sopra la soglia per secondi e il guadagno alzava il rumore di
  quasi 9 dB; ora il rivelatore parte da zero.
- **Look-ahead nel limitatore** → scartato: aggiungerebbe latenza; tetto a −2 dBFS per lasciare
  margine ai picchi tra i campioni (misurati fino a −0,7 dBTP) e alle ricodifiche del sistema.
- **Soppressione del rumore con rete neurale (tipo RNNoise)** → non adottata: richiederebbe codice
  nativo e un modello; la soluzione classica è sufficiente sul materiale misurato e resta verificabile
  con test JVM.

## 6. Risultati misurati

Valori sui due file di riferimento (originale → catena precedente → catena in vigore, uscita
prodotta dal codice dell'app):

| Misura | File 1 | File 2 |
|---|---|---|
| Loudness integrata (LUFS) | −19,2 → −18,8 → −17,2 | −18,4 → −17,5 → −18,0 |
| Distacco parlato/pause (dB) | 18,2 → 14,2 → 20,3 | 9,5 → 6,9 → 15,8 |
| Profondità di modulazione a 2 kHz | 0,90 → 0,74 → 0,98 | 0,43 → 0,41 → 0,93 |
| Profondità di modulazione a 1 kHz | 0,92 → 0,81 → 1,20 | 0,63 → 0,53 → 1,21 |
| Picco campione | — → — → −2,0 dBFS | — → — → −2,0 dBFS |

La differenza più evidente è sul materiale più rumoroso (file 2): il fondo nelle pause scende di
circa 6 dB rispetto al parlato e la distinzione delle sillabe raddoppia.

## 7. Integrazione e riuso

- La catena è codice puro senza dipendenze di piattaforma (pacchetto
  `playback/audio`: `VoiceBoostDsp`, `NoiseReducer`, `Fft`, `BiquadFilter`, `SpeechLeveler`,
  `LinkedCompressor`, `PeakLimiter`, `SoftLimiter`); l'adattatore Media3 è
  `VoiceBoostAudioProcessor` (PCM 16 bit e float, inserito nel `DefaultAudioSink` prima di Sonic,
  così il cambio di velocità non altera l'elaborazione). Per riusarla altrove basta un adattatore
  equivalente che converta i campioni in float [-1, 1] e chiami l'elaborazione per blocchi.
- Costo: 6 biquad per canale per campione, due logaritmi e tre potenze per frame, e per ogni salto
  di 256 campioni una FFT diretta e una inversa da 512 punti per canale.
- Frequenze di campionamento previste: 32–48 kHz (il blocco da 512 campioni è tarato per questo
  intervallo; a 96 kHz la risoluzione in frequenza resterebbe valida ma la latenza si dimezzerebbe e
  le costanti in secondi restano corrette).
- Il percorso Google Cast non è interessato: l'elaborazione agisce solo sull'audio decodificato in
  locale dal player del dispositivo.
- **Tizen**: non applicabile. L'audio protetto da DRM viene decodificato in un percorso hardware non
  accessibile da JavaScript (né con EME/Shaka né con AVPlay), quindi la Web Audio API non può
  elaborarlo.
- **Laboratorio di misura**: `tools/audio_lab/` contiene il porting Python della catena precedente
  (`chain.py`), il prototipo della catena in vigore (`newchain.py`, `nr.py`), le misure spettrali e
  di dinamica (`analyze.py`, `fine.py`) e l'indicatore di modulazione (`metrics.py`). Si lavora su
  file float32 mono grezzi estratti con ffmpeg; la loudness si misura con il filtro `ebur128` di
  ffmpeg. Richiede solo numpy.

## 8. Limiti noti

- I suoni stazionari più lunghi di circa 1,5 s (note tenute, accordi d'organo, sigle musicali con
  fondo continuo) vengono trattati come rumore e attenuati fino a 12 dB: la funzione è pensata per il
  parlato e va lasciata spenta per i brani musicali.
- Per i primi 1,5 s dopo l'avvio o un seek la riduzione del rumore non agisce (stima in corso).
- Dopo un seek i primi 11 ms escono silenziosi (riempimento del blocco) e gli ultimi 11 ms di un
  contenuto non vengono emessi: effetti trascurabili su spettacoli che iniziano e finiscono con
  applausi o silenzio.
- Taratura basata su due soli campioni mono; l'attenuazione dei lati in stereo è verificata solo
  con segnali di test.

## 9. Punti aperti

- Verifica d'ascolto sulla TV e sul telefono con flussi stereo reali (effetto del trattamento
  mid/side e del riverbero nei lati).
- Eventuale disattivazione automatica della riduzione del rumore nei passaggi musicali (rilevamento
  di parlato/musica), se le note tenute attenuate risultassero un problema.
- Raccolta di altri campioni (sale diverse, spettacoli con musica dal vivo) per confermare la
  taratura.
