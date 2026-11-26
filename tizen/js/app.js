/*
 * Controller dell'app Tizen: schermate, navigazione da telecomando, riproduzione con OSD e ciclo di
 * vita. Struttura, etichette e comportamento ricalcano l'app Android TV:
 *
 * - dati di accesso chiesti una sola volta, al primo avvio, e salvati: da lì ogni login richiesto dal
 *   servizio avviene da solo con quelli salvati (Api.ensureSession); catalogo a righe; scheda con
 *   locandina a sinistra e "Riproduci"
 *   già con il focus a destra; player a tutto schermo con OSD;
 * - OSD: Indietro 10 s, Play/Pausa, Stop, Avanti 10 s, barra di avanzamento; compare a ogni tasto e
 *   si nasconde dopo 5 s di inattività mentre lo spettacolo scorre;
 * - uscendo dal player con Indietro l'audio continua mentre si sceglie altro, con la barra "In
 *   riproduzione" (Torna al player / Stop) in catalogo e scheda;
 * - quando l'app esce dal primo piano (Home, cambio sorgente o app, uscita) la riproduzione si ferma
 *   sempre; al rientro sul player lo spettacolo viene ripreparato in pausa dallo stesso punto.
 *
 * La diagnostica della prova tecnica (ambiente, CORS, scelta del motore, log) resta in una schermata
 * dedicata. Scritto in ES5 per Chromium 63 (Tizen 5.0); ogni operazione di rete è serializzata perché
 * l'hardware è modesto e due richieste DRM o due player in parallelo renderebbero il log illeggibile.
 */
(function (global) {
    'use strict';

    var VERSION = '0.2.0';
    var KEY_LOG_SERVER = 'prsc.logServer';
    var KEY_ENGINE = 'prsc.engine';
    var KEY_POSITION_PREFIX = 'prsc.pos.';

    // Stessi valori dell'app Android (PlayerFactory.SEEK_INCREMENT_MS, ResumePolicy, PlaybackService).
    var SEEK_S = 10;
    var OSD_TIMEOUT_MS = 5000;
    var PROGRESS_POLL_MS = 500;
    var POSITION_SAVE_MS = 10000;
    var MIN_RESUME_POSITION_MS = 5000;
    var END_EXCLUSION_MS = 15000;
    // Tempo massimo concesso a un player per partire (manifest, licenza DRM, primo buffer) prima di
    // considerarlo inadatto e provare il successivo: largo, per non scartarlo su una rete lenta.
    var ATTEMPT_TIMEOUT_MS = 45000;
    // Ordine standard dei tentativi: prima il percorso web (Shaka/EME), poi il player nativo AVPlay.
    var ENGINE_ORDER = ['shaka:widevine', 'shaka:playready', 'avplay:playready', 'avplay:widevine'];
    // Righe di cui carico le immagini attorno a quella con il focus: la TV ha poca memoria grafica.
    var IMAGE_ROWS_AHEAD = 2;

    var KEYS = {
        LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13,
        BACK: 10009, PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
        IME_DONE: 65376, IME_CANCEL: 65385
    };

    var engines = { shaka: global.ShakaEngine, avplay: global.AvplayEngine };

    var state = {
        screen: null,
        history: [],
        lastFocus: {},
        rows: [],
        detailShow: null,
        /** Riproduzione corrente: { show, part, engineName, drmName, status }. */
        playback: null,
        starting: false,
        osdVisible: false,
        osdTimer: null,
        progressTimer: null,
        saveTimer: null,
        /** Spettacolo fermato perché l'app è uscita dal primo piano mentre era sul player. */
        resumeAfterHidden: null,
        busy: false,
        exiting: false,
        signingIn: false,
        /** Prima pressione di "Cambia account": serve una seconda pressione entro pochi secondi. */
        forgetArmedUntil: 0
    };

    function $(id) { return document.getElementById(id); }

    function setStatus(text) { $('status').textContent = text; }

    function storeGet(key) {
        try { return global.localStorage.getItem(key); } catch (e) { return null; }
    }
    function storeSet(key, value) {
        try {
            if (value === null) global.localStorage.removeItem(key);
            else global.localStorage.setItem(key, value);
        } catch (e) { Log.w('Archivio locale non scrivibile (' + key + ')', e); }
    }

    // Stesso formato di formatTimestamp su Android: "m:ss" o "h:mm:ss".
    function formatTimestamp(ms) {
        var total = Math.floor(Math.max(0, ms) / 1000);
        var h = Math.floor(total / 3600);
        var m = Math.floor(total / 60) % 60;
        var s = total % 60;
        var pad = function (n) { return (n < 10 ? '0' : '') + n; };
        return h > 0 ? h + ':' + pad(m) + ':' + pad(s) : m + ':' + pad(s);
    }

    // ---------------------------------------------------------------- schermate

    function screenElement(name) { return $('screen-' + name); }

    function defaultFocus(name) {
        if (name === 'detail') return $('detail-play');
        if (name === 'catalog') return $('rows').querySelector('.card');
        if (name === 'login') return $('email').value ? $('password') : $('email');
        return null;
    }

    function activate(name) {
        if (state.screen && state.screen !== 'player' && document.activeElement) {
            state.lastFocus[state.screen] = document.activeElement;
        }
        ['login', 'catalog', 'detail', 'diag', 'player'].forEach(function (screen) {
            screenElement(screen).classList.toggle('active', screen === name);
        });
        document.body.classList.toggle('player-visible', name === 'player');
        state.screen = name;
        Nav.setRoot(screenElement(name));
        if (name === 'player') return;
        hideOsd();
        renderNowPlaying();
        var target = state.lastFocus[name];
        if (!target || !Nav.isInside(target) || target.offsetParent === null) target = defaultFocus(name);
        if (!(target && Nav.focus(target))) Nav.focusFirst();
    }

    function navigate(name) {
        if (state.screen && state.screen !== name) state.history.push(state.screen);
        activate(name);
    }

    function goBack() {
        var previous = state.history.pop();
        if (previous) activate(previous);
        return !!previous;
    }

    function resetTo(name) {
        state.history = [];
        state.lastFocus = {};
        activate(name);
    }

    // ---------------------------------------------------------------- login

    function doLogin() {
        var email = $('email').value.trim();
        var password = $('password').value;
        var message = $('login-message');
        if (!email || !password) {
            message.textContent = 'Inserisci username e password';
            return Promise.resolve();
        }
        if (state.signingIn) return Promise.resolve();
        state.signingIn = true;
        message.textContent = 'Verifica in corso…';
        Log.i('Primo accesso come ' + email.replace(/^(.).*(@.*)$/, '$1***$2'));
        return Api.signIn(email, password).then(function (result) {
            state.signingIn = false;
            // La password non resta nel DOM oltre il necessario: da qui in poi la custodisce SecureStore.
            $('password').value = '';
            if (result.ok) {
                Log.i('Login riuscito: ' + Api.describe(result));
                if (!result.saved) Log.e('Dati di accesso NON salvati sulla TV: al prossimo avvio verranno richiesti di nuovo');
                message.textContent = '';
                resetTo('catalog');
                return loadCatalog();
            }
            Log.e('Login rifiutato: ' + Api.describe(result) + (result.reason ? ' – ' + result.reason : ''));
            message.textContent = result.reason ||
                (result.status === 0 ? 'Connessione assente o bloccata (vedi Diagnostica)' : 'La piattaforma ha rifiutato username e password.');
        }, function (error) {
            state.signingIn = false;
            Log.e('Login non riuscito', error);
            message.textContent = 'Si è verificato un errore imprevisto.';
        });
    }

    /**
     * Schermata di login: solo quando sulla TV non ci sono dati di accesso salvati (primo avvio,
     * oppure "Cambia account" dalla Diagnostica). Un rifiuto della piattaforma con dati già salvati
     * non passa mai da qui: diventa un errore con "Riprova", come su Android.
     */
    function requireLogin(reason) {
        $('login-message').textContent = reason || '';
        $('email').value = Api.savedEmail();
        resetTo('login');
    }

    /** Messaggio per una sessione che non si è aperta nonostante i dati salvati e i ritentativi. */
    function sessionErrorText(result) {
        if (result.reason) return result.reason + '.';
        return result.status === 0
            ? 'Connessione assente o instabile. Controlla la rete e riprova.'
            : 'Accesso alla piattaforma non riuscito (' + Api.describe(result) + '). Riprova.';
    }

    // ---------------------------------------------------------------- catalogo

    function catalogState(text, withRetry) {
        var box = $('catalog-state');
        box.innerHTML = '';
        if (!text) return;
        var p = document.createElement('p');
        p.textContent = text;
        box.appendChild(p);
        if (withRetry) {
            var retry = document.createElement('button');
            retry.className = 'focusable btn btn-primary';
            retry.textContent = 'Riprova';
            retry.addEventListener('click', function () { loadCatalog(); });
            box.appendChild(retry);
            Nav.focus(retry);
        }
    }

    function loadCatalog() {
        catalogState('Caricamento del catalogo…');
        $('rows').innerHTML = '';
        return Api.catalog().then(function (result) {
            Log.i('Catalogo: ' + Api.describe(result) + ', righe ' + result.rows.length);
            if (!result.ok) {
                catalogState(result.status === 0
                    ? 'Connessione assente o instabile. Controlla la rete e riprova.'
                    : 'Il servizio ha risposto con un errore (HTTP ' + result.status + ').', true);
                return;
            }
            state.rows = result.rows;
            if (state.rows.length === 0) {
                catalogState('Il catalogo non contiene ancora spettacoli.');
                return;
            }
            catalogState('');
            renderRows();
        });
    }

    function renderRows() {
        var container = $('rows');
        container.innerHTML = '';
        state.rows.forEach(function (row, rowIndex) {
            var title = document.createElement('h2');
            title.className = 'row-title';
            title.textContent = row.title;
            var scroll = document.createElement('div');
            scroll.className = 'row-scroll focus-group';
            row.shows.forEach(function (show) {
                var card = document.createElement('button');
                card.className = 'focusable card';
                card.setAttribute('data-row', String(rowIndex));
                var imageBox = document.createElement('div');
                imageBox.className = 'card-image';
                var img = document.createElement('img');
                img.alt = '';
                if (show.thumbnail) img.setAttribute('data-src', show.thumbnail);
                imageBox.appendChild(img);
                var caption = document.createElement('div');
                caption.className = 'card-title';
                caption.textContent = show.title;
                card.appendChild(imageBox);
                card.appendChild(caption);
                card.addEventListener('click', function () { openDetail(show); });
                card.addEventListener('focus', function () { loadImagesAround(rowIndex); });
                scroll.appendChild(card);
            });
            container.appendChild(title);
            container.appendChild(scroll);
        });
        loadImagesAround(0);
        if (state.screen === 'catalog') Nav.focus(defaultFocus('catalog'));
    }

    // Carico le immagini solo delle righe vicine a quella con il focus: con decine di locandine
    // decodificate insieme il motore web della TV esaurisce la memoria grafica.
    function loadImagesAround(rowIndex) {
        var images = $('rows').querySelectorAll('img[data-src]');
        Array.prototype.forEach.call(images, function (img) {
            var row = Number(img.parentNode.parentNode.getAttribute('data-row'));
            if (row >= rowIndex - 1 && row <= rowIndex + IMAGE_ROWS_AHEAD) {
                img.src = img.getAttribute('data-src');
                img.removeAttribute('data-src');
            }
        });
    }

    // ---------------------------------------------------------------- scheda

    function openDetail(show) {
        state.detailShow = show;
        $('detail-title').textContent = show.title;
        $('detail-meta').textContent = show.category || '';
        $('detail-synopsis').textContent = show.synopsis || '';
        var image = $('detail-image');
        if (show.thumbnail) image.src = show.thumbnail; else image.removeAttribute('src');
        updatePlayLabel();
        // Ogni scheda riparte con il focus su "Riproduci", come su Android.
        state.lastFocus.detail = null;
        navigate('detail');
    }

    function updatePlayLabel() {
        var show = state.detailShow;
        if (!show) return;
        var resume = resumePositionMs(show.id, 0, 0);
        $('detail-play').textContent = resume > 0 ? '▶  Riprendi da ' + formatTimestamp(resume) : '▶  Riproduci';
    }

    // ---------------------------------------------------------------- posizioni di visione

    function savedPosition(showId) {
        var raw = storeGet(KEY_POSITION_PREFIX + showId);
        if (!raw) return null;
        try { return JSON.parse(raw); } catch (e) { return null; }
    }

    /** Stessa regola di ResumePolicy su Android: niente ripresa nei primi 5 s né negli ultimi 15 s. */
    function resumePositionMs(showId, catalogDurationMs, serverProgressMs) {
        var saved = savedPosition(showId) || (serverProgressMs > 0 ? { positionMs: serverProgressMs, durationMs: catalogDurationMs } : null);
        if (!saved || saved.positionMs <= MIN_RESUME_POSITION_MS) return 0;
        var duration = saved.durationMs > 0 ? saved.durationMs : catalogDurationMs;
        if (duration > 0 && saved.positionMs >= duration - END_EXCLUSION_MS) return 0;
        return saved.positionMs;
    }

    function savePosition() {
        var pb = state.playback;
        if (!pb || pb.status === 'caricamento') return;
        var engine = engines[pb.engineName];
        var position = engine.positionMs();
        if (position <= 0) return;
        var duration = engine.durationMs() || pb.part.durationMs || 0;
        storeSet(KEY_POSITION_PREFIX + pb.show.id, JSON.stringify({ positionMs: position, durationMs: duration }));
    }

    // ---------------------------------------------------------------- riproduzione

    function preferredEngine() {
        var raw = storeGet(KEY_ENGINE) || ENGINE_ORDER[0];
        var parts = raw.split(':');
        return engines[parts[0]] ? { engine: parts[0], drm: parts[1] || 'widevine' } : { engine: 'shaka', drm: 'widevine' };
    }

    /**
     * Sequenza dei player da provare: per primo quello preferito (l'ultimo che ha funzionato su questa
     * TV, o quello scelto nella Diagnostica), poi tutti gli altri nell'ordine standard. Così dopo il
     * primo avvio riuscito la TV parte subito con il player giusto, senza ripetere i tentativi falliti.
     */
    function engineCandidates() {
        var preferred = preferredEngine();
        var first = preferred.engine + ':' + preferred.drm;
        return [first].concat(ENGINE_ORDER.filter(function (key) { return key !== first; })).map(function (key) {
            var parts = key.split(':');
            return { engine: parts[0], drm: parts[1] };
        });
    }

    /**
     * Avvio uno spettacolo. Se è già quello in sottofondo mi limito a riaprire il player. Opzioni:
     * positionMs (posizione esplicita, es. ripresa dopo il background) e startPaused.
     */
    function startPlayback(show, options) {
        options = options || {};
        if (state.playback && state.playback.show.id === show.id && !options.restart) {
            openPlayer();
            return Promise.resolve();
        }
        if (state.starting) return Promise.resolve();
        state.starting = true;
        var candidates = engineCandidates();
        // Nessun controllo del token qui: Api.show apre o rinnova da sola la sessione con i dati salvati.
        return stopPlayback({ stay: true }).then(function () {
            return Api.show(show.id);
        }).then(function (result) {
            Log.i('Scheda ' + show.id + ': ' + Api.describe(result));
            if (result.needsLogin) {
                // Capita solo se sulla TV non ci sono dati salvati (es. installazione precedente a
                // questa versione, che conservava solo il token): li chiedo una volta e li salvo.
                requireLogin('Accedi una volta: i dati verranno salvati su questa TV.');
                return;
            }
            if (result.sessionFailed) {
                showPlayerError(sessionErrorText(result), show);
                return;
            }
            if (!result.ok || !result.show || !result.show.feature) {
                showPlayerError(result.status === 0
                    ? 'Connessione assente o instabile. Controlla la rete e riprova.'
                    : 'Lo spettacolo non è disponibile (' + Api.describe(result) + ').', show);
                return;
            }
            var detail = result.show;
            var start = options.positionMs !== undefined
                ? options.positionMs
                : resumePositionMs(show.id, detail.feature.durationMs, detail.progressMs);
            state.playback = {
                show: show,
                part: detail.feature,
                engineName: candidates[0].engine,
                drmName: candidates[0].drm,
                status: 'caricamento',
                attempt: 0
            };
            hidePlayerError();
            Log.i('Avvio di ' + show.id + ' da ' + Math.round(start / 1000) + ' s' + (options.startPaused ? ' (in pausa)' : '') +
                ', player in ordine di prova: ' + candidates.map(function (c) { return c.engine + '/' + c.drm; }).join(', '));
            openPlayer();
            $('player-buffering').classList.remove('hidden');
            return tryEngines(state.playback, start, !!options.startPaused, candidates, 0);
        }).then(function () {
            state.starting = false;
        }, function (error) {
            state.starting = false;
            Log.e('Avvio della riproduzione non riuscito', error);
            showPlayerError('Si è verificato un errore imprevisto.', show);
        });
    }

    /**
     * Provo i player uno dopo l'altro finché uno avvia davvero la riproduzione. Un tentativo riesce
     * quando il player arriva "in riproduzione" (o "in pausa" se è richiesto l'avvio in pausa); fallisce
     * se l'avvio viene rifiutato, se il player segnala un errore prima di partire (licenza DRM, formato,
     * decodifica) o se non parte entro [ATTEMPT_TIMEOUT_MS]. Tra un tentativo e l'altro chiudo il player
     * fallito, per non tenere aperti due decoder sulla TV. L'errore all'utente arriva solo quando
     * nessun player ha funzionato; il player riuscito diventa il preferito per i prossimi avvii.
     */
    function tryEngines(pb, startMs, startPaused, candidates, index) {
        // L'utente ha fermato o sostituito la riproduzione durante i tentativi: interrompo la catena.
        if (state.playback !== pb) return Promise.resolve();
        if (index >= candidates.length) {
            Log.e('Nessun player ha avviato ' + pb.show.id + ' su questa TV (' + candidates.length + ' tentativi)');
            pb.status = 'errore';
            // Nessun piano video attivo: sotto la scheda d'errore resta lo sfondo nero dell'app.
            document.body.classList.remove('engine-shaka', 'engine-avplay');
            showPlayerError('Impossibile riprodurre lo spettacolo su questa TV: nessuno dei player disponibili è riuscito ad avviarlo. ' +
                'Riprova più tardi; se il problema persiste, invia il log dalla Diagnostica.', pb.show);
            return Promise.resolve();
        }
        var candidate = candidates[index];
        var attempt = index + 1;
        pb.engineName = candidate.engine;
        pb.drmName = candidate.drm;
        pb.status = 'caricamento';
        pb.attempt = attempt;
        document.body.classList.remove('engine-shaka', 'engine-avplay');
        document.body.classList.add('engine-' + candidate.engine);
        Log.i('Tentativo ' + attempt + '/' + candidates.length + ': ' + candidate.engine + '/' + candidate.drm);

        return new Promise(function (resolve) {
            var settled = false;
            var succeeded = false;
            var timer = null;
            function finish(ok, reason) {
                if (settled) return;
                settled = true;
                succeeded = ok;
                clearTimeout(timer);
                resolve({ ok: ok, reason: reason });
            }
            timer = setTimeout(function () { finish(false, 'nessun avvio entro ' + ATTEMPT_TIMEOUT_MS / 1000 + ' s'); }, ATTEMPT_TIMEOUT_MS);

            var onState = function (status) {
                // Ignoro eventi in ritardo di un tentativo precedente o di una riproduzione fermata.
                if (state.playback !== pb || pb.attempt !== attempt) return;
                if (settled) {
                    // Dopo un tentativo fallito il player viene chiuso: i suoi ultimi eventi non contano.
                    if (succeeded) onPlaybackState(status);
                    return;
                }
                if (status === 'in riproduzione' || (startPaused && status === 'in pausa')) {
                    pb.status = status;
                    finish(true);
                } else if (status === 'errore') {
                    finish(false, 'errore del player prima dell\'avvio');
                } else {
                    // Stati intermedi (caricamento, buffering): aggiorno solo l'indicatore di attesa.
                    pb.status = status;
                    updateOsd();
                }
            };
            var engine = engines[candidate.engine];
            Promise.resolve().then(function () {
                return engine.play(pb.part, candidate.drm, startMs / 1000, onState, { startPaused: startPaused });
            }).then(function (started) {
                if (!started) finish(false, 'avvio rifiutato');
            }, function (error) {
                Log.w('Player ' + candidate.engine + '/' + candidate.drm + ': eccezione in avvio', error);
                finish(false, 'eccezione in avvio');
            });
        }).then(function (result) {
            if (state.playback !== pb || pb.attempt !== attempt) return;
            if (result.ok) {
                var key = candidate.engine + ':' + candidate.drm;
                if (storeGet(KEY_ENGINE) !== key) {
                    storeSet(KEY_ENGINE, key);
                    markEngine();
                    Log.i('Player ' + key + ' funzionante: diventa il preferito per i prossimi avvii');
                }
                Log.i('Avvio riuscito con ' + key + ' al tentativo ' + attempt);
                onPlaybackState(pb.status);
                return;
            }
            Log.w('Player ' + candidate.engine + '/' + candidate.drm + ' non utilizzabile (' + result.reason + '): provo il successivo');
            // Invalido gli eventi del tentativo fallito prima di chiuderlo, poi passo al successivo.
            pb.attempt = -attempt;
            return engines[candidate.engine].stop().then(function () {
                return tryEngines(pb, startMs, startPaused, candidates, index + 1);
            });
        });
    }

    function onPlaybackState(status) {
        var pb = state.playback;
        if (!pb) return;
        pb.status = status;
        $('player-buffering').classList.toggle('hidden', status !== 'caricamento' && status !== 'buffering');
        updateOsd();
        renderNowPlaying();
        if (status === 'in riproduzione') {
            startPositionSaving();
            restartOsdTimer();
        } else if (status === 'in pausa') {
            savePosition();
            if (state.screen === 'player') showOsd();
        } else if (status === 'terminato') {
            // A fine spettacolo la ripresa riparte da capo: cancello la posizione salvata.
            storeSet(KEY_POSITION_PREFIX + pb.show.id, null);
            stopPlayback({ stay: state.screen !== 'player' }).then(updatePlayLabel);
        } else if (status === 'errore') {
            savePosition();
            showPlayerError('Errore di riproduzione. Riprova; se il problema persiste, invia il log dalla Diagnostica.', pb.show);
        }
    }

    function startPositionSaving() {
        if (state.saveTimer) return;
        state.saveTimer = setInterval(savePosition, POSITION_SAVE_MS);
    }

    /**
     * Fermo la riproduzione salvando la posizione e rilasciando il player (licenza, decoder, rete).
     * Con stay=false, se il player è in primo piano, torno alla schermata precedente.
     */
    function stopPlayback(options) {
        var pb = state.playback;
        if (!pb) return Promise.resolve();
        savePosition();
        Log.i('Stop riproduzione ' + pb.engineName + ' di ' + pb.show.id + ' a ' + Math.round(engines[pb.engineName].positionMs() / 1000) + ' s');
        state.playback = null;
        if (state.saveTimer) clearInterval(state.saveTimer);
        state.saveTimer = null;
        hideOsd();
        $('player-buffering').classList.add('hidden');
        renderNowPlaying();
        var leave = !(options && options.stay) && state.screen === 'player';
        if (leave) goBack();
        return engines[pb.engineName].stop().then(function () {
            if (!state.playback) document.body.classList.remove('engine-shaka', 'engine-avplay');
            updatePlayLabel();
        });
    }

    function openPlayer() {
        if (state.screen !== 'player') navigate('player');
        showOsd();
    }

    /** Indietro dal player: l'audio prosegue mentre si naviga nell'app (barra "In riproduzione"). */
    function leavePlayer() {
        hideOsd();
        if (!state.playback) hidePlayerError();
        if (!goBack()) resetTo('catalog');
        if (state.playback) Log.i('Player lasciato con audio in sottofondo: ' + state.playback.show.id);
    }

    // ---------------------------------------------------------------- errore del player

    function showPlayerError(text, show) {
        state.errorShow = show || null;
        hideOsd();
        $('player-buffering').classList.add('hidden');
        $('player-error-text').textContent = text;
        $('player-error').classList.remove('hidden');
        if (state.screen !== 'player') navigate('player');
        Nav.setRoot($('player-error'));
        Nav.focusFirst();
    }

    function hidePlayerError() {
        $('player-error').classList.add('hidden');
        Nav.setRoot(screenElement(state.screen || 'catalog'));
    }

    function errorVisible() { return !$('player-error').classList.contains('hidden'); }

    function retryAfterError() {
        var show = state.errorShow || (state.playback && state.playback.show);
        var position = state.playback ? engines[state.playback.engineName].positionMs() : undefined;
        hidePlayerError();
        if (!show) return leavePlayer();
        startPlayback(show, { restart: true, positionMs: position > 0 ? position : undefined });
    }

    function closeAfterError() {
        hidePlayerError();
        stopPlayback({ stay: true }).then(function () {
            if (state.screen === 'player') leavePlayer();
        });
    }

    // ---------------------------------------------------------------- OSD

    function isRunning() {
        var pb = state.playback;
        return !!pb && pb.status === 'in riproduzione';
    }

    function showOsd() {
        if (errorVisible() || state.screen !== 'player') return;
        var wasVisible = state.osdVisible;
        state.osdVisible = true;
        $('osd').classList.remove('hidden');
        Nav.setRoot($('osd'));
        updateOsd();
        // A ogni apertura il focus parte da Play/Pausa, come su Android.
        if (!wasVisible) Nav.focus($('osd-playpause'));
        if (!state.progressTimer) state.progressTimer = setInterval(updateOsd, PROGRESS_POLL_MS);
        restartOsdTimer();
    }

    function hideOsd() {
        state.osdVisible = false;
        $('osd').classList.add('hidden');
        if (state.osdTimer) clearTimeout(state.osdTimer);
        state.osdTimer = null;
        if (state.progressTimer) clearInterval(state.progressTimer);
        state.progressTimer = null;
        // Tolgo il focus dai pulsanti nascosti: i tasti tornano al documento, che riapre l'OSD.
        if (document.activeElement && $('osd').contains(document.activeElement)) document.activeElement.blur();
    }

    // L'OSD si chiude da solo solo mentre lo spettacolo scorre: in pausa o in buffering resta visibile.
    function restartOsdTimer() {
        if (state.osdTimer) clearTimeout(state.osdTimer);
        state.osdTimer = null;
        if (!state.osdVisible || !isRunning()) return;
        state.osdTimer = setTimeout(hideOsd, OSD_TIMEOUT_MS);
    }

    function updateOsd() {
        var pb = state.playback;
        if (!pb) return;
        var engine = engines[pb.engineName];
        var position = engine.positionMs();
        var duration = engine.durationMs() || pb.part.durationMs || 0;
        $('osd-title').textContent = pb.show.title;
        $('osd-position').textContent = formatTimestamp(position);
        $('osd-duration').textContent = duration > 0 ? formatTimestamp(duration) : '--:--';
        $('osd-fill').style.width = (duration > 0 ? Math.min(100, position / duration * 100) : 0) + '%';
        var paused = pb.status === 'in pausa' || (pb.status !== 'buffering' && pb.status !== 'caricamento' && engine.isPaused());
        $('osd-playpause').innerHTML = paused ? '&#9654;&nbsp; Play' : '&#10074;&#10074;&nbsp; Pausa';
    }

    function togglePlayPause() {
        var pb = state.playback;
        if (!pb) return;
        engines[pb.engineName].togglePause();
        updateOsd();
    }

    function seekBy(seconds) {
        var pb = state.playback;
        if (!pb) return;
        engines[pb.engineName].seekBy(seconds);
        updateOsd();
    }

    // ---------------------------------------------------------------- barra "In riproduzione"

    function buildNowPlayingBars() {
        Array.prototype.forEach.call(document.querySelectorAll('.now-playing'), function (bar) {
            bar.innerHTML =
                '<div class="np-text"><div class="np-label"></div><div class="np-title"></div></div>' +
                '<button class="focusable btn btn-primary" data-action="np-open">&#9654;&nbsp; Torna al player</button>' +
                '<button class="focusable btn btn-outlined" data-action="np-stop">&#9632;&nbsp; Stop</button>';
        });
    }

    function renderNowPlaying() {
        var pb = state.playback;
        var visible = !!pb && pb.status !== 'errore';
        Array.prototype.forEach.call(document.querySelectorAll('.now-playing'), function (bar) {
            // Se la barra sparisce mentre ha il focus lo sposto sull'elemento predefinito della schermata.
            var hadFocus = bar.contains(document.activeElement);
            bar.classList.toggle('hidden', !visible);
            if (!visible) {
                if (hadFocus && state.screen && state.screen !== 'player') {
                    if (!Nav.focus(defaultFocus(state.screen))) Nav.focusFirst();
                }
                return;
            }
            bar.querySelector('.np-label').textContent = pb.status === 'in pausa' ? 'In pausa' : 'In riproduzione';
            bar.querySelector('.np-title').textContent = pb.show.title;
        });
    }

    // ---------------------------------------------------------------- tasti

    function registerTvKeys() {
        if (!global.tizen || !tizen.tvinputdevice) return;
        var names = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind'];
        names.forEach(function (name) {
            try { tizen.tvinputdevice.registerKey(name); } catch (e) { Log.w('Tasto ' + name + ' non registrabile', e); }
        });
    }

    /** Tasti multimediali: attivi su qualunque schermata finché c'è una riproduzione. */
    function onMediaKey(code) {
        if (!state.playback) return false;
        var engine = engines[state.playback.engineName];
        switch (code) {
            case KEYS.PLAY_PAUSE: togglePlayPause(); break;
            case KEYS.PLAY: if (engine.isPaused()) togglePlayPause(); break;
            case KEYS.PAUSE: if (!engine.isPaused()) togglePlayPause(); break;
            case KEYS.STOP: stopPlayback(); return true;
            case KEYS.FF: seekBy(SEEK_S); break;
            case KEYS.RW: seekBy(-SEEK_S); break;
            default: return false;
        }
        if (state.screen === 'player') showOsd();
        return true;
    }

    function onPlayerKey(code) {
        if (errorVisible()) {
            if (code === KEYS.BACK) { closeAfterError(); return true; }
            return false;
        }
        if (!state.osdVisible) {
            switch (code) {
                case KEYS.LEFT: seekBy(-SEEK_S); showOsd(); return true;
                case KEYS.RIGHT: seekBy(SEEK_S); showOsd(); return true;
                case KEYS.UP:
                case KEYS.DOWN:
                case KEYS.ENTER: showOsd(); return true;
                case KEYS.BACK: leavePlayer(); return true;
                default: return false;
            }
        }
        restartOsdTimer();
        if (code === KEYS.BACK) {
            // Con lo spettacolo che scorre Indietro nasconde l'OSD; in pausa esce dal player.
            if (isRunning()) hideOsd(); else leavePlayer();
            return true;
        }
        return false;
    }

    function onKeyDown(event) {
        var code = event.keyCode;
        if (onMediaKey(code)) { event.preventDefault(); return; }
        if (state.screen === 'player' && onPlayerKey(code)) { event.preventDefault(); return; }

        var active = document.activeElement;
        var inInput = active && active.tagName === 'INPUT';
        switch (code) {
            case KEYS.UP: if (Nav.move('up')) event.preventDefault(); break;
            case KEYS.DOWN: if (Nav.move('down')) event.preventDefault(); break;
            // Nei campi di testo lascio sinistra/destra al cursore.
            case KEYS.LEFT: if (!inInput) { Nav.move('left'); event.preventDefault(); } break;
            case KEYS.RIGHT: if (!inInput) { Nav.move('right'); event.preventDefault(); } break;
            case KEYS.ENTER:
                if (!inInput && active && active.click) { active.click(); event.preventDefault(); }
                break;
            case KEYS.IME_DONE:
            case KEYS.IME_CANCEL:
                // Chiusa la tastiera virtuale passo al controllo successivo, come un "Tab".
                if (inInput) {
                    active.blur();
                    Nav.focus(active);
                    Nav.move('down');
                }
                break;
            case KEYS.BACK:
                event.preventDefault();
                onBack();
                break;
            default:
                break;
        }
    }

    function onBack() {
        if (state.screen === 'catalog' || state.screen === 'login') {
            // Dalla schermata principale Indietro chiude l'app, come su Android: audio fermato e
            // posto dispositivo liberato.
            releaseAndExit();
            return;
        }
        goBack();
    }

    // ---------------------------------------------------------------- ciclo di vita

    /**
     * Uscita dal primo piano (Home, cambio app o sorgente): la riproduzione si ferma sempre. Se
     * l'utente era sul player ricordo spettacolo e posizione per ripreparlo in pausa al rientro.
     */
    function onVisibilityChange() {
        if (document.hidden) {
            var pb = state.playback;
            if (!pb) return;
            var onPlayer = state.screen === 'player';
            var position = engines[pb.engineName].positionMs();
            Log.i('App nascosta: fermo la riproduzione di ' + pb.show.id);
            state.resumeAfterHidden = onPlayer ? { show: pb.show, positionMs: position } : null;
            stopPlayback({ stay: true });
            return;
        }
        var resume = state.resumeAfterHidden;
        state.resumeAfterHidden = null;
        if (resume && state.screen === 'player') {
            Log.i('App di nuovo visibile: ripreparo ' + resume.show.id + ' in pausa');
            startPlayback(resume.show, { restart: true, positionMs: resume.positionMs, startPaused: true });
        }
    }

    function exitApp() {
        try {
            tizen.application.getCurrentApplication().exit();
        } catch (e) {
            Log.w('Uscita non disponibile fuori dalla TV', e);
        }
    }

    // Libero il posto dispositivo prima di uscire: la TV non deve occupare uno dei 4 posti dell'account.
    function releaseAndExit() {
        if (state.exiting) return;
        state.exiting = true;
        setStatus('Uscita: libero il posto dispositivo…');
        var finish = function () {
            Log.flush();
            // Lascio il tempo all'ultimo invio del log verso il PC prima di chiudere.
            setTimeout(exitApp, 1500);
        };
        stopPlayback({ stay: true }).then(function () {
            return Api.releaseDevice();
        }).then(function (result) {
            if (result.skipped) Log.i('Nessuna sessione attiva: niente da liberare');
            else if (result.removal) Log.w('Rimozione dispositivo non riuscita (' + Api.describe(result.removal) + '), logout di ripiego: ' + Api.describe(result));
            else Log.i('Posto dispositivo liberato: ' + Api.describe(result));
        }).then(finish, function (error) {
            Log.e('Rilascio del posto non riuscito', error);
            finish();
        });
    }

    var FORGET_CONFIRM_MS = 5000;
    var FORGET_LABEL = 'Cambia account (dimentica i dati di accesso)';

    /**
     * Unico modo per far richiedere i dati di accesso dopo il primo avvio, ed è voluto: serve per
     * cambiare account o dopo un cambio password dal sito. Chiedo una seconda pressione di conferma,
     * perché un tasto premuto per sbaglio costringerebbe a riscrivere email e password col telecomando.
     */
    function forgetAccount(button) {
        var now = Date.now();
        if (now > state.forgetArmedUntil) {
            state.forgetArmedUntil = now + FORGET_CONFIRM_MS;
            button.textContent = 'Premi di nuovo per confermare';
            setTimeout(function () {
                if (Date.now() >= state.forgetArmedUntil) button.textContent = FORGET_LABEL;
            }, FORGET_CONFIRM_MS);
            return;
        }
        state.forgetArmedUntil = 0;
        button.textContent = FORGET_LABEL;
        runExclusive('Cambio account', function () {
            return stopPlayback({ stay: true }).then(function () {
                // Libero prima il posto dispositivo, finché il token è ancora valido.
                return Api.releaseDevice();
            }).then(function (result) {
                Log.i(result.skipped ? 'Nessuna sessione attiva da chiudere' : 'Sessione chiusa: ' + Api.describe(result));
            }, function (error) {
                Log.w('Chiusura della sessione non riuscita', error);
            }).then(function () {
                return SecureStore.clear();
            }).then(function () {
                Log.i('Dati di accesso dimenticati: alla prossima richiesta servirà un nuovo login');
                requireLogin('Inserisci i dati di accesso dell\'account da usare.');
            });
        });
    }

    // ---------------------------------------------------------------- diagnostica

    // Serializzo le azioni di diagnostica: una sola alla volta, con esito sempre registrato.
    function runExclusive(label, task) {
        if (state.busy) {
            Log.w('Operazione in corso, attendere: ' + label + ' ignorata');
            return;
        }
        state.busy = true;
        setStatus(label + '…');
        Promise.resolve().then(task).then(function () {
            setStatus(label + ': fatto');
        }, function (error) {
            Log.e(label + ' non riuscito', error);
            setStatus(label + ': errore (vedi log)');
        }).then(function () {
            state.busy = false;
        });
    }

    function markEngine() {
        var current = preferredEngine();
        Array.prototype.forEach.call(document.querySelectorAll('.engine'), function (button) {
            var selected = button.getAttribute('data-engine') === current.engine && button.getAttribute('data-drm') === current.drm;
            button.classList.toggle('selected', selected);
        });
    }

    function selectEngine(button) {
        var value = button.getAttribute('data-engine') + ':' + button.getAttribute('data-drm');
        storeSet(KEY_ENGINE, value);
        markEngine();
        Log.i('Motore di riproduzione predefinito: ' + value);
    }

    function enableLogServer() {
        var url = $('log-server').value.trim();
        if (url && !/^https?:\/\//.test(url)) url = 'http://' + url;
        storeSet(KEY_LOG_SERVER, url || null);
        Log.setServer(url || null);
        Log.i(url ? 'Invio log al PC attivo: ' + url : 'Invio log al PC disattivato');
    }

    var diagActions = {
        'env': function () { return Diagnostics.environment(); },
        'cors': function () { return Diagnostics.cors(); },
        'log-server': enableLogServer,
        'export-usb': function () { return LogExport.saveToStorage(); },
        'export-pc': function () { return LogExport.sendToPc(); },
        'export-share': function () { return LogExport.share(); }
    };

    var actions = {
        'login': function () { return doLogin(); },
        'open-diag': function () { navigate('diag'); },
        'play': function () { if (state.detailShow) startPlayback(state.detailShow); },
        'np-open': function () { openPlayer(); },
        'np-stop': function () { stopPlayback({ stay: true }); },
        'osd-rewind': function () { seekBy(-SEEK_S); },
        'osd-playpause': togglePlayPause,
        'osd-stop': function () { stopPlayback(); },
        'osd-forward': function () { seekBy(SEEK_S); },
        'player-retry': retryAfterError,
        'player-close': closeAfterError,
        'engine': selectEngine,
        'release': releaseAndExit,
        'forget-account': forgetAccount
    };

    // Delego i clic al documento: le barre "In riproduzione" e le righe del catalogo sono dinamiche.
    function onClick(event) {
        var target = event.target;
        while (target && target !== document && !(target.getAttribute && target.getAttribute('data-action'))) target = target.parentNode;
        if (!target || target === document) return;
        var name = target.getAttribute('data-action');
        if (diagActions[name]) {
            runExclusive(target.textContent.trim(), function () { return diagActions[name](target); });
        } else if (actions[name]) {
            actions[name](target);
        }
    }

    function init() {
        buildNowPlayingBars();
        registerTvKeys();
        document.addEventListener('keydown', onKeyDown);
        document.addEventListener('click', onClick);
        document.addEventListener('visibilitychange', onVisibilityChange);

        // Uscita brusca (tasto Exit, chiusura dal sistema): fermo il player e libero il posto in modo sincrono.
        global.addEventListener('unload', function () {
            try { if (state.playback) engines[state.playback.engineName].stop(); } catch (e) { /* in chiusura */ }
            Api.releaseDeviceSync();
        });

        var savedServer = storeGet(KEY_LOG_SERVER);
        if (savedServer) {
            $('log-server').value = savedServer;
            Log.setServer(savedServer);
        }
        // Dichiaro subito se la condivisione nativa manca, invece di lasciarlo scoprire premendo il pulsante.
        if (!LogExport.shareAvailable()) {
            document.querySelector('[data-action="export-share"]').textContent = 'Condividi ZIP (non disponibile)';
        }
        markEngine();

        Log.i('Proscaenia Tizen ' + VERSION + ' avviata, motore ' + storeGet(KEY_ENGINE) + ' (predefinito shaka:widevine)');
        // Carico i dati di accesso salvati prima di scegliere la prima schermata: se ci sono, il login
        // non compare più. Il catalogo è pubblico, quindi non serve aprire la sessione all'avvio:
        // avverrà da sola alla prima riproduzione (e non occupa un posto dispositivo finché non serve).
        Api.init().then(function () {
            $('email').value = Api.savedEmail();
            var saved = Api.hasCredentials();
            Log.i(saved
                ? 'Dati di accesso salvati presenti (' + (SecureStore.encrypted() ? 'cifrati' : 'archivio locale') + '): login non richiesto'
                : 'Nessun dato di accesso salvato');
            // Con il solo token di una versione precedente lascio sfogliare il catalogo: i dati
            // verranno chiesti una volta quando il token non basterà più.
            if (saved || Api.hasToken()) {
                resetTo('catalog');
                loadCatalog();
            } else {
                resetTo('login');
            }
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})(window);
