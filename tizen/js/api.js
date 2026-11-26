/*
 * Accesso alle API reali di Proscaenia, con lo stesso contratto dell'app Android
 * (docs/client-android-proscaenia.md §7-§8).
 *
 * Uso XMLHttpRequest e non fetch: Chromium 63 (Tizen 5.0) non ha AbortController, mentre XHR offre
 * timeout nativo e, soprattutto, distingue un blocco CORS/rete (status 0) da una risposta HTTP vera,
 * cosa che è esattamente uno degli obiettivi della prova.
 *
 * Origin e Referer non si possono impostare da JavaScript (header vietati): l'app Android li invia con
 * l'origine del sito, qui il runtime della TV metterà la propria. Verificare se il backend lo accetta è
 * il rischio principale che la prova deve sciogliere.
 */
(function (global) {
    'use strict';

    var BASE_URL = 'https://proscaenia.com/backoffice/api/';
    var SITE_ORIGIN = 'https://proscaenia.com';
    var CURRENT_BUILD = 'ios_1';
    var DEFAULT_TIMEOUT_MS = 20000;
    // Attese tra i tentativi sugli errori transitori: 5 tentativi in circa 15 s, come AutomaticRetry su Android.
    var RETRY_DELAYS_MS = [1000, 2000, 4000, 8000];
    // Il rifiuto dell'accesso automatico lo ripeto una sola volta: insistere farebbe scattare la
    // protezione anti forza bruta del backend.
    var REJECTED_RETRY_DELAY_MS = 2000;

    var LICENSE_URLS = {
        widevine: 'https://drm.proscaenia.com?drm-type=widevine',
        playready: 'https://drm.proscaenia.com?drm-type=playready'
    };

    var KEY_DEVICE_ID = 'prsc.deviceId';
    var KEY_TOKEN = 'prsc.token';
    var KEY_EMAIL = 'prsc.email';

    // localStorage può lanciare eccezioni (quota, archivio disattivato): non deve mai interrompere la prova.
    function storeGet(key) {
        try { return global.localStorage.getItem(key); } catch (e) { return null; }
    }
    function storeSet(key, value) {
        try {
            if (value === null) global.localStorage.removeItem(key);
            else global.localStorage.setItem(key, value);
        } catch (e) {
            Log.w('Archivio locale non scrivibile (' + key + ')', e);
        }
    }

    function randomHex(bytes) {
        var array = new Uint8Array(bytes);
        global.crypto.getRandomValues(array);
        var out = '';
        for (var i = 0; i < array.length; i++) out += (array[i] < 16 ? '0' : '') + array[i].toString(16);
        return out;
    }

    // Genero l'identificativo una volta sola e lo conservo: il backend registra i dispositivi e un ID
    // nuovo a ogni avvio consumerebbe un posto diverso dei 4 disponibili sull'account.
    var deviceId = storeGet(KEY_DEVICE_ID);
    if (!deviceId) {
        deviceId = randomHex(16);
        storeSet(KEY_DEVICE_ID, deviceId);
    }

    function chromeVersion() {
        var match = /Chrome\/([\d.]+)/.exec(navigator.userAgent);
        return match ? match[1] : 'unknown';
    }

    function tvModel() {
        try {
            if (global.webapis && webapis.productinfo) return webapis.productinfo.getRealModel() || webapis.productinfo.getModel();
        } catch (e) { /* fuori dalla TV o privilegio assente: uso un valore generico */ }
        return 'Tizen';
    }

    // Stessa struttura del client web/Android (derivata da FingerprintJS); cambio solo il modello.
    var deviceData = JSON.stringify({
        deviceId: deviceId,
        deviceBrand: 'Samsung',
        deviceModel: tvModel(),
        deviceSystemName: 'Chrome',
        deviceSystemVersion: chromeVersion()
    });

    var token = storeGet(KEY_TOKEN);
    // Generazione del token: cambia a ogni login o cancellazione. Chi riceve un 401 chiede il rinnovo
    // indicando la generazione usata, così se un'altra chiamata ha già rinnovato non rifaccio il login.
    var generation = 0;
    // Login automatico in corso: le chiamate che arrivano nel frattempo lo attendono invece di
    // avviarne un secondo, che potrebbe invalidare il token appena ottenuto.
    var pendingLogin = null;

    function setToken(value) {
        token = value;
        generation++;
        storeSet(KEY_TOKEN, value);
    }

    function platformHeaders() {
        return {
            'Accept': 'application/json',
            'Content-Language': 'it',
            'Current-Build': CURRENT_BUILD,
            'DeviceId': deviceId,
            'DeviceData': deviceData
        };
    }

    function parseJson(text) {
        if (!text) return null;
        try { return JSON.parse(text); } catch (e) { return null; }
    }

    /**
     * Richiesta generica. Risolvo sempre (mai reject) con un esito descrittivo: per la diagnostica
     * conta sapere se è fallita la rete/CORS (status 0), se è scaduto il tempo o che stato HTTP è tornato.
     */
    function request(options) {
        return new Promise(function (resolve) {
            var started = Date.now();
            var xhr = new XMLHttpRequest();
            var settled = false;
            function done(result) {
                if (settled) return;
                settled = true;
                result.ms = Date.now() - started;
                resolve(result);
            }
            xhr.open(options.method || 'GET', options.url, true);
            xhr.timeout = options.timeout || DEFAULT_TIMEOUT_MS;
            if (options.responseType) xhr.responseType = options.responseType;
            var headers = options.headers || {};
            Object.keys(headers).forEach(function (name) { xhr.setRequestHeader(name, headers[name]); });
            xhr.onload = function () {
                var text = (!options.responseType || options.responseType === 'text') ? xhr.responseText : null;
                done({
                    ok: xhr.status >= 200 && xhr.status < 300,
                    status: xhr.status,
                    text: text,
                    json: text !== null ? parseJson(text) : null,
                    body: xhr.response,
                    contentType: xhr.getResponseHeader('Content-Type')
                });
            };
            xhr.onerror = function () { done({ ok: false, status: 0, failure: 'rete o CORS' }); };
            xhr.ontimeout = function () { done({ ok: false, status: 0, failure: 'timeout' }); };
            try {
                xhr.send(options.body !== undefined ? options.body : null);
            } catch (e) {
                done({ ok: false, status: 0, failure: 'eccezione: ' + e.message });
            }
        });
    }

    function describe(result) {
        if (result.status === 0) return 'FALLITA (' + result.failure + ') in ' + result.ms + ' ms';
        var message = result.json && (result.json.message || (result.json.errors && result.json.errors[0]));
        return 'HTTP ' + result.status + ' in ' + result.ms + ' ms' + (message ? ' – ' + message : '') +
            (result.json === null && result.text ? ' (corpo non JSON, ' + result.text.length + ' caratteri)' : '');
    }

    function api(path, options) {
        var headers = platformHeaders();
        if (options && options.auth && token) headers['Authorization'] = 'Bearer ' + token;
        if (options && options.body) headers['Content-Type'] = 'application/json';
        return request({
            method: (options && options.method) || 'GET',
            url: BASE_URL + path,
            headers: headers,
            body: options && options.body ? JSON.stringify(options.body) : undefined
        });
    }

    // ------------------------------------------------------------ sessione e ritentativi

    function wait(ms) {
        return new Promise(function (resolve) { setTimeout(resolve, ms); });
    }

    /** Errori per cui ha senso riprovare da soli: rete/CORS/timeout, server in difficoltà, 408 e 429. */
    function isTransient(result) {
        return result.status === 0 || result.status >= 500 || result.status === 408 || result.status === 429;
    }

    /**
     * Ripeto [task] sugli errori transitori con attese crescenti; il rifiuto dei dati di accesso lo
     * ripeto al massimo [rejectedRetries] volte. Gli altri esiti (abbonamento non attivo, limite di
     * dispositivi, contenuto inesistente) tornano subito al chiamante.
     */
    function withRetry(label, task, rejectedRetries) {
        var rejectedLeft = rejectedRetries || 0;
        function run(attempt) {
            return task().then(function (result) {
                if (result.ok) return result;
                if (isTransient(result) && attempt < RETRY_DELAYS_MS.length) {
                    var delay = RETRY_DELAYS_MS[attempt];
                    Log.w(label + ': ' + describe(result) + ', nuovo tentativo tra ' + delay / 1000 + ' s');
                    return wait(delay).then(function () { return run(attempt + 1); });
                }
                if (result.rejected && rejectedLeft > 0) {
                    rejectedLeft--;
                    Log.w(label + ': dati di accesso rifiutati (' + describe(result) + '), riprovo una volta');
                    return wait(REJECTED_RETRY_DELAY_MS).then(function () { return run(attempt); });
                }
                return result;
            });
        }
        return run(0);
    }

    /**
     * Una singola richiesta di login. Classifico l'esito: il backend usa lo stesso 401 per credenziali
     * errate e per il limite di dispositivi, quindi distinguo dal codice in errors[0].
     */
    function loginRequest(email, password) {
        return api('auth/login', { method: 'POST', body: { email: email, password: password, remember: true } }).then(function (result) {
            var json = result.json;
            var firstError = json && json.errors && json.errors[0];
            if (firstError === 'MAX Device') result.reason = 'Limite di dispositivi raggiunto: liberane uno dal sito';
            else if (firstError === 'Not active subscription') result.reason = 'Abbonamento non attivo';
            if (result.ok && json && json.access_token) {
                setToken(json.access_token);
            } else if (result.ok) {
                result.ok = false;
                result.reason = 'Risposta senza access_token';
            } else if (!result.reason && (result.status === 400 || result.status === 401 || result.status === 403 || result.status === 422)) {
                result.rejected = true;
            }
            return result;
        });
    }

    /**
     * Garantisco un token prima di una chiamata autenticata. Se manca (primo uso, posto liberato
     * all'uscita, token rifiutato) rifaccio il login con i dati salvati, senza chiedere nulla
     * all'utente. needsLogin è vero solo quando sulla TV non ci sono dati di accesso salvati.
     */
    function ensureSession() {
        if (token) return Promise.resolve({ ok: true });
        if (pendingLogin) return pendingLogin;
        var saved = SecureStore.get();
        if (!SecureStore.has()) {
            return Promise.resolve({ ok: false, status: 0, needsLogin: true, failure: 'nessun dato di accesso salvato' });
        }
        Log.i('Accesso automatico con i dati salvati');
        pendingLogin = withRetry('Accesso automatico', function () {
            return loginRequest(saved.email, saved.password);
        }, 1).then(function (result) {
            pendingLogin = null;
            if (result.ok) {
                Log.i('Accesso automatico riuscito: ' + describe(result));
            } else {
                if (result.rejected) result.reason = 'La piattaforma ha rifiutato i dati di accesso salvati';
                Log.e('Accesso automatico non riuscito: ' + describe(result) + (result.reason ? ' – ' + result.reason : ''));
            }
            return result;
        }, function (error) {
            pendingLogin = null;
            throw error;
        });
        return pendingLogin;
    }

    /** Rinnovo la sessione solo se nessun'altra chiamata l'ha già fatto dopo il 401 ricevuto. */
    function renewSession(rejectedGeneration) {
        if (token && generation !== rejectedGeneration) return Promise.resolve({ ok: true });
        if (token) setToken(null);
        return ensureSession();
    }

    /**
     * Chiamata autenticata: sessione garantita, ritentativi sugli errori transitori e, su un 401,
     * rinnovo del token e una sola ripetizione (un rifiuto definitivo non deve generare un ciclo).
     * Se la sessione non si apre restituisco l'esito del login, marcato con sessionFailed.
     */
    function authorized(label, path, options) {
        var call = function () { return api(path, { method: options && options.method, auth: true }); };
        var sessionFailure = function (session) {
            session.ok = false;
            session.sessionFailed = true;
            return session;
        };
        return ensureSession().then(function (session) {
            if (!session.ok) return sessionFailure(session);
            var usedGeneration = generation;
            return withRetry(label, call).then(function (result) {
                if (result.status !== 401) return result;
                Log.w('Token rifiutato (HTTP 401) su ' + path + ': rinnovo la sessione con i dati salvati');
                return renewSession(usedGeneration).then(function (renewed) {
                    return renewed.ok ? withRetry(label, call) : sessionFailure(renewed);
                });
            });
        });
    }

    function absolute(raw) {
        try { return new URL(raw, BASE_URL).toString(); } catch (e) { return null; }
    }

    // La sinossi arriva come HTML con entità di ogni tipo: la faccio interpretare al parser del
    // motore web (come HtmlCompat su Android) e compatto gli spazi, compresi quelli non separabili.
    function stripHtml(html) {
        if (!html) return '';
        try {
            var text = new DOMParser().parseFromString(String(html), 'text/html').body.textContent || '';
            return text.replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
        } catch (e) {
            return String(html).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
        }
    }

    // Catalogo: righe editoriali nell'ordine del sito, con le schede risolte dagli show_ids. Come su
    // Android preferisco la locandina (img_preview) con ripiego sull'immagine di testata.
    function mapCatalog(json) {
        var categories = {};
        (json.categories || []).forEach(function (category) {
            if (category && category.id !== undefined && category.id !== null) categories[String(category.id)] = category.name || null;
        });
        var shows = {};
        (json.shows || []).forEach(function (show) {
            if (!show || show.id === undefined || show.id === null) return;
            var image = show.img_preview || show.img_header;
            shows[String(show.id)] = {
                id: String(show.id),
                title: show.title || String(show.id),
                thumbnail: image ? absolute(image) : null,
                category: show.category_id !== undefined && show.category_id !== null ? categories[String(show.category_id)] || null : null,
                synopsis: stripHtml(show.content)
            };
        });
        return (json.rows || []).map(function (row) {
            return {
                id: String(row.id),
                title: row.title || String(row.id),
                shows: (row.show_ids || []).map(function (id) { return shows[String(id)]; }).filter(Boolean)
            };
        }).filter(function (row) { return row.shows.length > 0; });
    }

    // Scheda: scelgo la prima parte che non sia la sigla ("ads"), come fa l'app Android.
    function mapShow(json) {
        var data = json && json.data;
        if (!data || !data.video) return null;
        var parts = data.video.map(function (video) {
            var urls = video.urls || {};
            return {
                dash: urls.dash ? absolute(urls.dash) : null,
                hls: urls.hls ? absolute(urls.hls) : null,
                drm: video.drm === 1 || video.drm === '1' || video.drm === true,
                isIntro: video.type === 'ads',
                durationMs: video.duration && video.duration.ms ? Number(video.duration.ms) : 0
            };
        }).filter(function (part) { return part.dash; });
        var feature = parts.filter(function (part) { return !part.isIntro; })[0] || null;
        var progress = data.user_data && data.user_data.progress ? Number(data.user_data.progress) : 0;
        return { parts: parts, feature: feature, progressMs: isFinite(progress) ? progress : 0 };
    }

    global.Api = {
        BASE_URL: BASE_URL,
        SITE_ORIGIN: SITE_ORIGIN,
        LICENSE_URLS: LICENSE_URLS,
        deviceId: deviceId,
        deviceData: deviceData,
        request: request,
        describe: describe,
        hasToken: function () { return !!token; },

        /** Da chiamare all'avvio prima di scegliere la prima schermata: carica i dati di accesso salvati. */
        init: function () { return SecureStore.load(); },

        /** Vero se sulla TV ci sono dati di accesso salvati: allora il login non va mai più chiesto. */
        hasCredentials: function () { return SecureStore.has(); },

        /** Dimentico un token che il backend ha rifiutato: il prossimo uso rifarà il login automatico. */
        forgetToken: function () { setToken(null); },

        savedEmail: function () {
            var saved = SecureStore.get();
            return (saved && saved.email) || storeGet(KEY_EMAIL) || '';
        },

        ensureSession: ensureSession,

        /** Header da aggiungere alle richieste di licenza, come fa l'app Android (Origin/Referer esclusi: vietati). */
        licenseHeaders: function () {
            return { 'DeviceId': deviceId, 'DeviceData': deviceData };
        },

        /** Catalogo pubblico senza header personalizzati: richiesta "semplice", nessun preflight CORS. */
        catalogSimple: function () {
            return request({ url: BASE_URL + 'catalog/snapshot' });
        },

        /** Catalogo pubblico con gli header di piattaforma: forza il preflight CORS, come ogni altra chiamata. */
        catalog: function () {
            return api('catalog/snapshot').then(function (result) {
                result.rows = result.ok && result.json ? mapCatalog(result.json) : [];
                return result;
            });
        },

        /**
         * Primo accesso dalla schermata di login. Verifico i dati con un login reale e li salvo solo
         * se la piattaforma li accetta: salvarli prima vorrebbe dire non chiedere mai più una password
         * digitata male. Ripeto da solo gli errori di rete, non il rifiuto, che qui significa davvero
         * dati errati. Un "MAX Device" non salva nulla: non è documentato che il backend verifichi la
         * password prima del limite di dispositivi. result.saved indica se il salvataggio è riuscito.
         */
        signIn: function (email, password) {
            storeSet(KEY_EMAIL, email);
            return withRetry('Primo accesso', function () { return loginRequest(email, password); }, 0).then(function (result) {
                if (!result.ok) return result;
                return SecureStore.save({ email: email, password: password }).then(function (saved) {
                    result.saved = saved;
                    return result;
                });
            });
        },

        show: function (showId) {
            return authorized('Scheda ' + showId, 'show/' + encodeURIComponent(showId) + '?preview=false').then(function (result) {
                result.show = result.ok ? mapShow(result.json) : null;
                return result;
            });
        },

        /**
         * Libero il posto dispositivo come il sito: DELETE user/device/{deviceId}. La rimozione riuscita
         * invalida anche il token; il logout lo uso solo come ripiego (comportamento verificato su Android).
         * Cancello solo il token: i dati di accesso restano salvati e il login successivo avviene da solo,
         * alla prima riproduzione. Se un login automatico è in corso lo attendo, altrimenti il suo token
         * arriverebbe dopo la rimozione e terrebbe occupato il posto.
         */
        releaseDevice: function () {
            var before = pendingLogin ? pendingLogin.then(null, function () { /* conta solo che sia finito */ }) : Promise.resolve();
            return before.then(function () {
                if (!token) return { ok: true, skipped: true };
                return api('user/device/' + encodeURIComponent(deviceId), { method: 'DELETE', auth: true }).then(function (removed) {
                    if (removed.ok) return removed;
                    return api('auth/logout', { auth: true }).then(function (loggedOut) {
                        loggedOut.removal = removed;
                        return loggedOut;
                    });
                }).then(function (result) {
                    // Il token locale lo cancello comunque: un token orfano non serve più a nulla, e
                    // un nuovo login con lo stesso DeviceId riprende lo stesso posto.
                    setToken(null);
                    return result;
                });
            });
        },

        /** Ultima difesa alla chiusura della pagina: XHR sincrona, l'unica che sopravvive a "unload" su Chromium 63. */
        releaseDeviceSync: function () {
            if (!token) return;
            try {
                var xhr = new XMLHttpRequest();
                xhr.open('DELETE', BASE_URL + 'user/device/' + encodeURIComponent(deviceId), false);
                var headers = platformHeaders();
                headers['Authorization'] = 'Bearer ' + token;
                Object.keys(headers).forEach(function (name) { xhr.setRequestHeader(name, headers[name]); });
                xhr.send(null);
                if (xhr.status >= 200 && xhr.status < 300) setToken(null);
            } catch (e) { /* nulla da fare in fase di chiusura */ }
        }
    };
})(window);
