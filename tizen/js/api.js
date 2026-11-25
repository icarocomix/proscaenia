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

    function absolute(raw) {
        try { return new URL(raw, BASE_URL).toString(); } catch (e) { return null; }
    }

    // Catalogo: righe editoriali nell'ordine del sito, con le schede risolte dagli show_ids.
    function mapCatalog(json) {
        var shows = {};
        (json.shows || []).forEach(function (show) {
            if (show && show.id !== undefined && show.id !== null) shows[String(show.id)] = { id: String(show.id), title: show.title || String(show.id) };
        });
        return (json.rows || []).map(function (row) {
            return {
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
        savedEmail: function () { return storeGet(KEY_EMAIL) || ''; },

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

        login: function (email, password) {
            storeSet(KEY_EMAIL, email);
            return api('auth/login', { method: 'POST', body: { email: email, password: password, remember: true } }).then(function (result) {
                var json = result.json;
                var firstError = json && json.errors && json.errors[0];
                if (firstError === 'MAX Device') result.reason = 'Limite di dispositivi raggiunto: liberane uno dal sito';
                else if (firstError === 'Not active subscription') result.reason = 'Abbonamento non attivo';
                if (result.ok && json && json.access_token) {
                    token = json.access_token;
                    // Conservo il token per poter liberare il posto anche dopo un'uscita brusca dell'app.
                    storeSet(KEY_TOKEN, token);
                } else if (result.ok) {
                    result.ok = false;
                    result.reason = 'Risposta senza access_token';
                }
                return result;
            });
        },

        show: function (showId) {
            return api('show/' + encodeURIComponent(showId) + '?preview=false', { auth: true }).then(function (result) {
                result.show = result.ok ? mapShow(result.json) : null;
                return result;
            });
        },

        /**
         * Libero il posto dispositivo come il sito: DELETE user/device/{deviceId}. La rimozione riuscita
         * invalida anche il token; il logout lo uso solo come ripiego (comportamento verificato su Android).
         */
        releaseDevice: function () {
            if (!token) return Promise.resolve({ ok: true, skipped: true });
            return api('user/device/' + encodeURIComponent(deviceId), { method: 'DELETE', auth: true }).then(function (removed) {
                if (removed.ok) return removed;
                return api('auth/logout', { auth: true }).then(function (loggedOut) {
                    loggedOut.removal = removed;
                    return loggedOut;
                });
            }).then(function (result) {
                // Il token locale lo cancello comunque: un token orfano non serve più a nulla.
                token = null;
                storeSet(KEY_TOKEN, null);
                return result;
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
                if (xhr.status >= 200 && xhr.status < 300) {
                    token = null;
                    storeSet(KEY_TOKEN, null);
                }
            } catch (e) { /* nulla da fare in fase di chiusura */ }
        }
    };
})(window);
