/*
 * Custodia dei dati di accesso sulla TV: email e password si inseriscono una sola volta, al primo
 * avvio, e da quel momento ogni login richiesto dal servizio avviene in automatico con quelle salvate
 * (stesso modello del CredentialStore dell'app Android).
 *
 * Tizen non offre alle app web un equivalente dell'Android Keystore, quindi cifro con WebCrypto
 * (AES-GCM 256) usando una chiave generata sulla TV e creata come NON estraibile: il materiale della
 * chiave non è leggibile da JavaScript, nemmeno da questa app, che può solo usarla per cifrare e
 * decifrare. Chiave e dati cifrati stanno insieme in un unico record IndexedDB, così non possono
 * finire disallineati (chiave persa ma dati presenti o viceversa). Il tutto resta nella sandbox
 * dell'app, inaccessibile alle altre applicazioni.
 *
 * Se WebCrypto o IndexedDB non sono disponibili (motore web anomalo, archivio disattivato) ripiego
 * sull'archivio locale dell'app senza cifratura: la sandbox resta l'unica protezione, ma la funzione
 * più importante dell'app – non chiedere più i dati di accesso – continua a funzionare. Il ripiego
 * viene sempre registrato nel log.
 *
 * Tutte le operazioni sono asincrone e non rifiutano mai: un errore di archivio non deve bloccare
 * l'avvio dell'app. Scritto in ES5 per Chromium 63 (Tizen 5.0).
 */
(function (global) {
    'use strict';

    var DB_NAME = 'prsc-secure';
    var DB_VERSION = 1;
    var STORE = 'records';
    var RECORD_ID = 'access';
    var FALLBACK_KEY = 'prsc.access';

    // Copia in memoria dopo la prima lettura: evito di decifrare a ogni login automatico.
    var cached = null;
    var loaded = false;

    function subtle() {
        try { return global.crypto && global.crypto.subtle ? global.crypto.subtle : null; } catch (e) { return null; }
    }

    function idb() {
        try { return global.indexedDB || null; } catch (e) { return null; }
    }

    function secureAvailable() { return !!(subtle() && idb()); }

    function valid(value) {
        return !!value && typeof value.email === 'string' && value.email.length > 0 &&
            typeof value.password === 'string' && value.password.length > 0;
    }

    // ------------------------------------------------------------ IndexedDB

    function openDb() {
        return new Promise(function (resolve, reject) {
            var request;
            try {
                request = idb().open(DB_NAME, DB_VERSION);
            } catch (e) {
                reject(e);
                return;
            }
            request.onupgradeneeded = function () {
                var db = request.result;
                if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
            };
            request.onsuccess = function () { resolve(request.result); };
            request.onerror = function () { reject(request.error || new Error('IndexedDB non apribile')); };
            // Un'altra istanza tiene aperto il database con una versione vecchia: non attendo in eterno.
            request.onblocked = function () { reject(new Error('IndexedDB bloccato da un\'altra istanza')); };
        });
    }

    /** Eseguo una singola operazione in una transazione e chiudo subito il database. */
    function withStore(mode, operation) {
        return openDb().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx;
                var result;
                try {
                    tx = db.transaction(STORE, mode);
                    var request = operation(tx.objectStore(STORE));
                    request.onsuccess = function () { result = request.result; };
                } catch (e) {
                    db.close();
                    reject(e);
                    return;
                }
                // Risolvo al completamento della transazione, non della richiesta: solo allora la
                // scrittura è davvero consolidata su disco.
                tx.oncomplete = function () { db.close(); resolve(result); };
                tx.onerror = function () { db.close(); reject(tx.error || new Error('Transazione IndexedDB fallita')); };
                tx.onabort = function () { db.close(); reject(tx.error || new Error('Transazione IndexedDB annullata')); };
            });
        });
    }

    // ------------------------------------------------------------ cifratura

    function encode(text) {
        // TextEncoder esiste su Chromium 63; la conversione manuale UTF-8 resta come rete di sicurezza.
        if (global.TextEncoder) return new global.TextEncoder().encode(text);
        var utf8 = unescape(encodeURIComponent(text));
        var bytes = new Uint8Array(utf8.length);
        for (var i = 0; i < utf8.length; i++) bytes[i] = utf8.charCodeAt(i);
        return bytes;
    }

    function decode(buffer) {
        var bytes = new Uint8Array(buffer);
        if (global.TextDecoder) return new global.TextDecoder().decode(bytes);
        var binary = '';
        for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        return decodeURIComponent(escape(binary));
    }

    function encryptRecord(value) {
        var crypto = subtle();
        // Chiave nuova a ogni salvataggio: non serve conservarne una a lungo termine separata dai dati.
        return Promise.resolve(crypto.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']))
            .then(function (key) {
                var iv = new Uint8Array(12);
                global.crypto.getRandomValues(iv);
                var plain = encode(JSON.stringify({ email: value.email, password: value.password }));
                return Promise.resolve(crypto.encrypt({ name: 'AES-GCM', iv: iv }, key, plain)).then(function (data) {
                    return { v: 1, key: key, iv: iv, data: data };
                });
            });
    }

    function decryptRecord(record) {
        if (!record || record.v !== 1 || !record.key || !record.iv || !record.data) return Promise.resolve(null);
        return Promise.resolve(subtle().decrypt({ name: 'AES-GCM', iv: record.iv }, record.key, record.data))
            .then(function (plain) {
                var value = JSON.parse(decode(plain));
                return valid(value) ? { email: value.email, password: value.password } : null;
            });
    }

    // ------------------------------------------------------------ ripiego su localStorage

    function fallbackRead() {
        try {
            var raw = global.localStorage.getItem(FALLBACK_KEY);
            if (!raw) return null;
            var value = JSON.parse(raw);
            return valid(value) ? { email: value.email, password: value.password } : null;
        } catch (e) {
            return null;
        }
    }

    function fallbackWrite(value) {
        try {
            if (value) global.localStorage.setItem(FALLBACK_KEY, JSON.stringify({ email: value.email, password: value.password }));
            else global.localStorage.removeItem(FALLBACK_KEY);
            return true;
        } catch (e) {
            Log.e('Archivio locale non scrivibile: dati di accesso non salvati', e);
            return false;
        }
    }

    // ------------------------------------------------------------ API pubblica

    /**
     * Leggo i dati di accesso salvati (null se assenti). Un record cifrato illeggibile – chiave
     * invalidata, archivio danneggiato – è irrecuperabile per definizione: lo elimino, così l'app
     * mostra di nuovo il login una volta invece di fallire a ogni avvio.
     */
    function load() {
        if (loaded) return Promise.resolve(cached);
        var finish = function (value) {
            cached = value;
            loaded = true;
            return value;
        };
        if (!secureAvailable()) {
            var plain = fallbackRead();
            if (plain) Log.w('Dati di accesso letti dall\'archivio locale non cifrato (WebCrypto o IndexedDB assenti)');
            return Promise.resolve(finish(plain));
        }
        return withStore('readonly', function (store) { return store.get(RECORD_ID); })
            .then(decryptRecord)
            .then(function (value) {
                // Un ripiego scritto in passato (per esempio con IndexedDB momentaneamente non
                // disponibile) vale comunque: meglio usarlo che chiedere di nuovo i dati.
                return value || fallbackRead();
            }, function (error) {
                Log.w('Dati di accesso cifrati illeggibili: li elimino', error);
                return withStore('readwrite', function (store) { return store.delete(RECORD_ID); })
                    .then(null, function () { /* già illeggibile: nulla da aggiungere */ })
                    .then(fallbackRead);
            })
            .then(finish);
    }

    /**
     * Salvo i dati di accesso, cifrati se possibile. Risolvo con true se sono stati salvati in un
     * modo o nell'altro: in caso contrario il chiamante lo segnala, perché al riavvio verrebbe chiesto
     * di nuovo il login senza un motivo evidente.
     */
    function save(value) {
        if (!valid(value)) return Promise.resolve(false);
        var remember = function (ok) {
            if (ok) {
                cached = { email: value.email, password: value.password };
                loaded = true;
            }
            return ok;
        };
        if (!secureAvailable()) {
            Log.w('WebCrypto o IndexedDB assenti: salvo i dati di accesso nell\'archivio locale dell\'app, senza cifratura');
            return Promise.resolve(remember(fallbackWrite(value)));
        }
        return encryptRecord(value).then(function (record) {
            return withStore('readwrite', function (store) { return store.put(record, RECORD_ID); });
        }).then(function () {
            // Salvataggio cifrato riuscito: elimino un eventuale ripiego in chiaro rimasto da prima.
            fallbackWrite(null);
            Log.i('Dati di accesso salvati sulla TV (cifrati)');
            return remember(true);
        }, function (error) {
            Log.w('Salvataggio cifrato non riuscito, ripiego sull\'archivio locale dell\'app', error);
            return remember(fallbackWrite(value));
        });
    }

    /** Dimentico i dati di accesso: è il solo modo, voluto ed esplicito, per cambiare account. */
    function clear() {
        cached = null;
        loaded = true;
        fallbackWrite(null);
        if (!secureAvailable()) return Promise.resolve();
        return withStore('readwrite', function (store) { return store.delete(RECORD_ID); })
            .then(null, function (error) { Log.w('Cancellazione dei dati di accesso cifrati non riuscita', error); });
    }

    global.SecureStore = {
        load: load,
        save: save,
        clear: clear,
        /** Valido solo dopo load(): l'app lo chiama all'avvio, prima di scegliere la prima schermata. */
        has: function () { return valid(cached); },
        get: function () { return cached; },
        encrypted: secureAvailable
    };
})(window);
