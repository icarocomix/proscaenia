/*
 * Diagnostica dell'ambiente della TV: piattaforma, motore web, capacità EME/MSE e raggiungibilità
 * (CORS) di API e server licenze. Ogni controllo è isolato in try/catch: una API assente su questo
 * firmware deve produrre una riga di log, non interrompere gli altri controlli.
 */
(function (global) {
    'use strict';

    var KEY_SYSTEMS = {
        widevine: 'com.widevine.alpha',
        playready: 'com.microsoft.playready'
    };

    var VIDEO_TYPE = 'video/mp4; codecs="avc1.640028"';
    var AUDIO_TYPE = 'audio/mp4; codecs="mp4a.40.2"';

    function safe(label, fn) {
        try {
            Log.i(label + ': ' + fn());
        } catch (e) {
            Log.w(label + ': non disponibile', e);
        }
    }

    function platformInfo() {
        Log.i('--- Ambiente ---');
        safe('User-Agent', function () { return navigator.userAgent; });
        safe('Origine della pagina', function () { return location.protocol + '//' + location.host + ' (href ' + location.href + ')'; });
        safe('Schermo', function () { return screen.width + 'x' + screen.height + ' dpr ' + global.devicePixelRatio; });
        safe('Versione Tizen', function () { return tizen.systeminfo.getCapability('http://tizen.org/feature/platform.version'); });
        safe('Modello', function () { return webapis.productinfo.getRealModel() + ' / ' + webapis.productinfo.getModel(); });
        safe('Firmware', function () { return webapis.productinfo.getFirmware(); });
        safe('UHD', function () { return webapis.productinfo.isUdPanelSupported(); });
        safe('AVPlay', function () { return webapis.avplay.getVersion(); });
        safe('DeviceId inviato', function () { return Api.deviceId; });
        safe('DeviceData inviato', function () { return Api.deviceData; });
        safe('MSE avc1 High 4.0', function () { return global.MediaSource && MediaSource.isTypeSupported(VIDEO_TYPE); });
        safe('MSE HEVC', function () { return global.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L120.90"'); });
        safe('MSE AAC', function () { return global.MediaSource && MediaSource.isTypeSupported(AUDIO_TYPE); });
        safe('Web Share API', function () { return typeof navigator.share === 'function'; });
    }

    // Provo ogni sistema DRM con e senza requisiti di robustezza: il livello hardware decide la qualità ottenibile.
    function probeKeySystem(name, keySystem, robustness) {
        if (!navigator.requestMediaKeySystemAccess) return Promise.resolve(Log.w('EME assente: requestMediaKeySystemAccess non definita'));
        var video = { contentType: VIDEO_TYPE };
        var audio = { contentType: AUDIO_TYPE };
        if (robustness) {
            video.robustness = robustness;
            audio.robustness = robustness;
        }
        var config = [{
            initDataTypes: ['cenc'],
            videoCapabilities: [video],
            audioCapabilities: [audio],
            persistentState: 'optional',
            distinctiveIdentifier: 'optional'
        }];
        var label = 'EME ' + name + (robustness ? ' [' + robustness + ']' : '');
        return navigator.requestMediaKeySystemAccess(keySystem, config).then(function (access) {
            var granted = access.getConfiguration();
            var grantedVideo = granted.videoCapabilities && granted.videoCapabilities[0];
            Log.i(label + ': SUPPORTATO' + (grantedVideo && grantedVideo.robustness ? ' (robustezza ' + grantedVideo.robustness + ')' : ''));
        }, function (error) {
            Log.w(label + ': non supportato', error);
        });
    }

    function emeInfo() {
        Log.i('--- EME / DRM ---');
        var checks = [
            ['Widevine', KEY_SYSTEMS.widevine, null],
            ['Widevine', KEY_SYSTEMS.widevine, 'HW_SECURE_ALL'],
            ['Widevine', KEY_SYSTEMS.widevine, 'SW_SECURE_DECODE'],
            ['Widevine', KEY_SYSTEMS.widevine, 'SW_SECURE_CRYPTO'],
            ['PlayReady', KEY_SYSTEMS.playready, null]
        ];
        // Eseguo i controlli in sequenza: l'hardware della TV è modesto e le richieste EME non sono gratuite.
        return checks.reduce(function (chain, check) {
            return chain.then(function () { return probeKeySystem(check[0], check[1], check[2]); });
        }, Promise.resolve()).then(function () {
            if (!global.shaka) return Log.w('Shaka Player non caricato');
            shaka.polyfill.installAll();
            Log.i('Shaka ' + shaka.Player.version + ' supportato dal browser: ' + shaka.Player.isBrowserSupported());
            return shaka.Player.probeSupport().then(function (support) {
                Object.keys(support.drm).forEach(function (keySystem) {
                    Log.i('Shaka probeSupport ' + keySystem + ': ' + (support.drm[keySystem] ? JSON.stringify(support.drm[keySystem]) : 'no'));
                });
                Log.i('Shaka probeSupport DASH: ' + support.manifest.mpd);
            });
        });
    }

    /**
     * Test di raggiungibilità. Uno status 0 indica blocco CORS o di rete (il browser non lascia
     * distinguere i due casi), qualunque stato HTTP indica invece che il server ha risposto e che il
     * runtime della TV ci lascia leggere la risposta.
     */
    function corsInfo() {
        Log.i('--- API e CORS ---');
        return Api.catalogSimple().then(function (simple) {
            Log.i('Catalogo senza header (richiesta semplice): ' + Api.describe(simple));
            return Api.catalog();
        }).then(function (full) {
            Log.i('Catalogo con header di piattaforma (preflight): ' + Api.describe(full) + ', righe ' + (full.rows ? full.rows.length : 0));
            if (full.status === 0) Log.e('Le API non sono raggiungibili con gli header di piattaforma: probabile blocco CORS del preflight');
            // Una richiesta di licenza vuota: mi aspetto un rifiuto HTTP del server, non uno status 0.
            return Api.request({
                method: 'POST',
                url: Api.LICENSE_URLS.widevine,
                headers: Api.licenseHeaders(),
                body: new Uint8Array([8, 4]).buffer,
                responseType: 'text'
            });
        }).then(function (license) {
            Log.i('Server licenze (richiesta fittizia, rifiuto atteso): ' + Api.describe(license));
            if (license.status === 0) Log.e('Server licenze non raggiungibile dalla TV: probabile blocco CORS');
        });
    }

    global.Diagnostics = {
        environment: function () {
            platformInfo();
            return emeInfo();
        },
        cors: corsInfo
    };
})(window);
