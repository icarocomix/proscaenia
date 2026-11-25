/*
 * Controller della prova tecnica: navigazione con il telecomando, esecuzione dei passi
 * (diagnostica, login, scelta spettacolo, riproduzione, esportazione log) e uscita ordinata.
 *
 * L'interfaccia è volutamente essenziale: lo scopo è raccogliere dati sulla TV reale, non anticipare
 * il client definitivo. Ogni azione è serializzata (flag "busy") perché l'hardware è modesto e due
 * richieste DRM o due player in parallelo renderebbero il log illeggibile.
 */
(function (global) {
    'use strict';

    var VERSION = '0.1.0';
    var KEY_LOG_SERVER = 'prsc.logServer';
    var SEEK_SHORT_S = 10;
    var SEEK_LONG_S = 30;

    var KEYS = {
        LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13,
        BACK: 10009, PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
        IME_DONE: 65376, IME_CANCEL: 65385
    };

    var engines = { shaka: global.ShakaEngine, avplay: global.AvplayEngine };
    var state = {
        busy: false,
        selectedShow: null,
        engine: null,
        lastFocused: null,
        exiting: false
    };

    function $(id) { return document.getElementById(id); }

    function setStatus(text) {
        $('status').textContent = text;
    }

    function showOsd(text) {
        var osd = $('osd');
        osd.textContent = text;
        osd.className = '';
    }

    function hideOsd() {
        $('osd').className = 'hidden';
    }

    // ---------------------------------------------------------------- navigazione da telecomando

    function focusables() {
        return Array.prototype.filter.call(document.querySelectorAll('.focusable'), function (el) {
            return el.offsetParent !== null && !el.disabled;
        });
    }

    function moveFocus(step) {
        var list = focusables();
        if (list.length === 0) return;
        var index = list.indexOf(document.activeElement);
        var next = index < 0 ? 0 : Math.max(0, Math.min(list.length - 1, index + step));
        list[next].focus();
        list[next].scrollIntoView({ block: 'nearest' });
    }

    function registerTvKeys() {
        if (!global.tizen || !tizen.tvinputdevice) return;
        var names = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind'];
        names.forEach(function (name) {
            try { tizen.tvinputdevice.registerKey(name); } catch (e) { Log.w('Tasto ' + name + ' non registrabile', e); }
        });
    }

    function onPlaybackKey(code) {
        var engine = engines[state.engine];
        switch (code) {
            case KEYS.BACK:
            case KEYS.STOP:
                stopPlayback();
                return true;
            case KEYS.ENTER:
            case KEYS.PLAY_PAUSE:
            case KEYS.PLAY:
            case KEYS.PAUSE:
                engine.togglePause();
                return true;
            case KEYS.LEFT: engine.seekBy(-SEEK_SHORT_S); return true;
            case KEYS.RIGHT: engine.seekBy(SEEK_SHORT_S); return true;
            case KEYS.RW: engine.seekBy(-SEEK_LONG_S); return true;
            case KEYS.FF: engine.seekBy(SEEK_LONG_S); return true;
            default: return false;
        }
    }

    function onKeyDown(event) {
        var code = event.keyCode;
        if (state.engine) {
            if (onPlaybackKey(code)) event.preventDefault();
            return;
        }
        var active = document.activeElement;
        var inInput = active && active.tagName === 'INPUT';
        switch (code) {
            case KEYS.UP:
                moveFocus(-1);
                event.preventDefault();
                break;
            case KEYS.DOWN:
                moveFocus(1);
                event.preventDefault();
                break;
            // Nei campi di testo lascio sinistra/destra al cursore; altrove servono a muoversi tra i pulsanti affiancati.
            case KEYS.LEFT:
                if (!inInput) { moveFocus(-1); event.preventDefault(); }
                break;
            case KEYS.RIGHT:
                if (!inInput) { moveFocus(1); event.preventDefault(); }
                break;
            case KEYS.ENTER:
                if (!inInput && active && active.click) { active.click(); event.preventDefault(); }
                break;
            case KEYS.IME_DONE:
            case KEYS.IME_CANCEL:
                // Chiusa la tastiera virtuale passo al controllo successivo, come un "Tab".
                if (inInput) {
                    var list = focusables();
                    var next = list[Math.min(list.length - 1, list.indexOf(active) + 1)];
                    active.blur();
                    if (next) next.focus();
                }
                break;
            case KEYS.BACK:
                event.preventDefault();
                releaseAndExit();
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- azioni

    // Serializzo le azioni: una sola alla volta, con esito sempre registrato e stato ripristinato.
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

    function doLogin() {
        var email = $('email').value.trim();
        var password = $('password').value;
        if (!email || !password) throw new Error('Inserire email e password');
        Log.i('Login come ' + email.replace(/^(.).*(@.*)$/, '$1***$2'));
        return Api.login(email, password).then(function (result) {
            // La password non resta nel DOM oltre il necessario.
            $('password').value = '';
            if (result.ok) Log.i('Login riuscito: ' + Api.describe(result));
            else Log.e('Login rifiutato: ' + Api.describe(result) + (result.reason ? ' – ' + result.reason : ''));
        });
    }

    function selectShow(button, show) {
        Array.prototype.forEach.call(document.querySelectorAll('#show-list .selected'), function (el) { el.classList.remove('selected'); });
        button.classList.add('selected');
        state.selectedShow = show;
        $('selected-show').textContent = 'Selezionato: ' + show.title + ' (id ' + show.id + ')';
        Log.i('Spettacolo selezionato: ' + show.title + ' (id ' + show.id + ')');
    }

    function loadShows() {
        return Api.catalog().then(function (result) {
            Log.i('Catalogo: ' + Api.describe(result) + ', righe ' + result.rows.length);
            if (!result.ok) throw new Error('Catalogo non disponibile');
            var container = $('show-list');
            container.innerHTML = '';
            // Mostro solo le prime righe: per la prova basta uno spettacolo, e la TV ha poca memoria.
            var seen = {};
            result.rows.slice(0, 3).forEach(function (row) {
                row.shows.slice(0, 12).forEach(function (show) {
                    if (seen[show.id]) return;
                    seen[show.id] = true;
                    var button = document.createElement('button');
                    button.className = 'focusable';
                    button.textContent = show.title;
                    button.addEventListener('click', function () { selectShow(button, show); });
                    container.appendChild(button);
                });
            });
            Log.i('Spettacoli elencati: ' + Object.keys(seen).length);
        });
    }

    function startPlayback(engineName, drmName) {
        if (!Api.hasToken()) throw new Error('Eseguire prima il login');
        if (!state.selectedShow) throw new Error('Selezionare prima uno spettacolo');
        var engine = engines[engineName];
        return Api.show(state.selectedShow.id).then(function (result) {
            Log.i('Scheda ' + state.selectedShow.id + ': ' + Api.describe(result));
            if (!result.ok || !result.show || !result.show.feature) throw new Error('Scheda senza parte riproducibile');
            var show = result.show;
            Log.i('Parti: ' + show.parts.length + ', DRM spettacolo ' + show.feature.drm + ', durata ' +
                Math.round(show.feature.durationMs / 1000) + ' s, progresso server ' + Math.round(show.progressMs / 1000) + ' s');
            state.lastFocused = document.activeElement;
            state.engine = engineName;
            document.body.className = 'playing playing-' + engineName;
            showOsd(engineName + ' · ' + drmName + ': caricamento');
            return engine.play(show.feature, drmName, show.progressMs / 1000, function (playbackState) {
                showOsd(engineName + ' · ' + drmName + ': ' + playbackState + '   (Indietro = stop, ◀▶ = ±10 s)');
                if (playbackState === 'in riproduzione') setTimeout(hideOsd, 4000);
            }).then(function (started) {
                Log.i('Avvio ' + engineName + '/' + drmName + ': ' + (started ? 'riuscito' : 'NON riuscito'));
            });
        });
    }

    function stopPlayback() {
        var engineName = state.engine;
        if (!engineName) return;
        Log.i('Stop riproduzione ' + engineName + ' a ' + Math.round(engines[engineName].positionMs() / 1000) + ' s');
        state.engine = null;
        engines[engineName].stop().then(function () {
            document.body.className = '';
            hideOsd();
            if (state.lastFocused) state.lastFocused.focus();
        });
    }

    function enableLogServer() {
        var url = $('log-server').value.trim();
        if (url && !/^https?:\/\//.test(url)) url = 'http://' + url;
        try { global.localStorage.setItem(KEY_LOG_SERVER, url); } catch (e) { /* preferenza non essenziale */ }
        Log.setServer(url || null);
        Log.i(url ? 'Invio log al PC attivo: ' + url : 'Invio log al PC disattivato');
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
        Api.releaseDevice().then(function (result) {
            if (result.skipped) Log.i('Nessuna sessione attiva: niente da liberare');
            else if (result.removal) Log.w('Rimozione dispositivo non riuscita (' + Api.describe(result.removal) + '), logout di ripiego: ' + Api.describe(result));
            else Log.i('Posto dispositivo liberato: ' + Api.describe(result));
        }).then(finish, function (error) {
            Log.e('Rilascio del posto non riuscito', error);
            finish();
        });
    }

    var actions = {
        'env': function () { return Diagnostics.environment(); },
        'cors': function () { return Diagnostics.cors(); },
        'login': doLogin,
        'shows': loadShows,
        'play': function (button) { return startPlayback(button.getAttribute('data-engine'), button.getAttribute('data-drm')); },
        'log-server': enableLogServer,
        'export-usb': function () { return LogExport.saveToStorage(); },
        'export-pc': function () { return LogExport.sendToPc(); },
        'export-share': function () { return LogExport.share(); },
        'release': function () { releaseAndExit(); }
    };

    function bindActions() {
        Array.prototype.forEach.call(document.querySelectorAll('[data-action]'), function (button) {
            button.addEventListener('click', function () {
                var name = button.getAttribute('data-action');
                if (name === 'release') return actions.release();
                runExclusive(button.textContent.trim(), function () { return actions[name](button); });
            });
        });
    }

    function init() {
        bindActions();
        registerTvKeys();
        document.addEventListener('keydown', onKeyDown);

        // Uscita brusca (tasto Exit/Home seguito da chiusura): ultimo tentativo sincrono di liberare il posto.
        global.addEventListener('unload', function () { Api.releaseDeviceSync(); });
        document.addEventListener('visibilitychange', function () {
            if (document.hidden && state.engine) {
                Log.i('App nascosta: metto in pausa');
                try { engines[state.engine].togglePause(); } catch (e) { /* già in pausa */ }
            }
        });

        $('email').value = Api.savedEmail();
        var savedServer = null;
        try { savedServer = global.localStorage.getItem(KEY_LOG_SERVER); } catch (e) { /* nessuna preferenza */ }
        if (savedServer) {
            $('log-server').value = savedServer;
            Log.setServer(savedServer);
        }
        // Dichiaro subito se la condivisione nativa manca, invece di lasciarlo scoprire premendo il pulsante.
        if (!LogExport.shareAvailable()) {
            document.querySelector('[data-action="export-share"]').textContent = 'Condividi ZIP (non disponibile su questa TV)';
        }

        Log.i('Proscaenia prova Tizen ' + VERSION + ' avviata');
        if (Api.hasToken()) {
            Log.w('Trovato un token di una sessione precedente: il posto dispositivo potrebbe essere ancora occupato. ' +
                'Si può riprodurre subito oppure liberarlo con "Libera posto dispositivo ed esci".');
        }
        moveFocus(0);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})(window);
