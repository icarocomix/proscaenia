/*
 * Riproduzione con AVPlay, il player nativo Samsung (decodifica hardware, DRM gestito dal firmware).
 * È l'alternativa a Shaka se il motore web della RU7400 si rivela troppo lento o se EME non funziona.
 *
 * Uso la modalità "GetChallenge": il firmware mi consegna la challenge DRM, io la invio al server
 * licenze con XHR aggiungendo gli header di dispositivo (cosa che con LicenseServer diretto non
 * controllerei) e poi installo la licenza ricevuta. I nomi delle proprietà di installazione della
 * licenza variano tra versioni del firmware, quindi provo le varianti documentate in sequenza e
 * registro quale viene accettata: è una delle informazioni che la prova deve raccogliere.
 */
(function (global) {
    'use strict';

    var DRM_TYPES = {
        playready: 'PLAYREADY',
        widevine: 'WIDEVINE_CDM'
    };
    var TIME_LOG_INTERVAL_MS = 10000;

    var active = false;
    var lastTimeLog = 0;
    // Callback di stato della riproduzione corrente: pausa e ripresa comandate da qui non generano
    // eventi AVPlay, quindi le notifico io all'interfaccia.
    var notifyState = function () {};

    function avplay() {
        return global.webapis && webapis.avplay ? webapis.avplay : null;
    }

    function base64ToBuffer(b64) {
        var binary = atob(b64);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes.buffer;
    }

    function bufferToBase64(buffer) {
        var bytes = new Uint8Array(buffer);
        var binary = '';
        // Converto a blocchi: String.fromCharCode con migliaia di argomenti supera lo stack su motori datati.
        for (var i = 0; i < bytes.length; i += 0x8000) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        }
        return btoa(binary);
    }

    function installLicense(drmType, licenseB64) {
        var attempts = [
            ['InstallLicense', JSON.stringify({ ResponseMessage: licenseB64 })],
            ['widevine_license_data', JSON.stringify({ ResponseMessage: licenseB64 })],
            ['widevine_license_data', licenseB64]
        ];
        if (drmType === DRM_TYPES.playready) attempts = attempts.slice(0, 1);
        for (var i = 0; i < attempts.length; i++) {
            try {
                avplay().setDrm(drmType, attempts[i][0], attempts[i][1]);
                Log.i('AVPlay: licenza installata con "' + attempts[i][0] + '" (variante ' + (i + 1) + ')');
                return true;
            } catch (e) {
                Log.w('AVPlay: installazione licenza con "' + attempts[i][0] + '" (variante ' + (i + 1) + ') rifiutata', e);
            }
        }
        Log.e('AVPlay: nessuna variante di installazione della licenza accettata');
        return false;
    }

    function handleChallenge(drmName, drmType, drmData) {
        var challenge = drmData.challenge || drmData.challengeData;
        if (!challenge) {
            Log.e('AVPlay: evento Challenge senza dati di challenge, chiavi ' + Object.keys(drmData).join(','));
            return;
        }
        var body = base64ToBuffer(challenge);
        var headers = Api.licenseHeaders();
        // PlayReady su KeyOS si aspetta l'envelope SOAP come testo XML.
        headers['Content-Type'] = drmType === DRM_TYPES.playready ? 'text/xml; charset=utf-8' : 'application/octet-stream';
        Log.i('AVPlay: challenge ' + drmName + ' di ' + body.byteLength + ' byte, invio al server licenze');
        Api.request({
            method: 'POST',
            url: Api.LICENSE_URLS[drmName],
            headers: headers,
            body: body,
            responseType: 'arraybuffer'
        }).then(function (result) {
            if (!result.ok) {
                var preview = '';
                try { preview = new TextDecoder('utf-8').decode(new Uint8Array(result.body || new ArrayBuffer(0)).subarray(0, 300)); } catch (e) { /* corpo binario */ }
                Log.e('AVPlay: licenza rifiutata ' + Api.describe(result) + ' ' + preview);
                return;
            }
            Log.i('AVPlay: licenza ricevuta, ' + result.body.byteLength + ' byte in ' + result.ms + ' ms');
            installLicense(drmType, bufferToBase64(result.body));
        });
    }

    function stop() {
        var av = avplay();
        if (!av || !active) return Promise.resolve();
        active = false;
        try { av.stop(); } catch (e) { Log.w('AVPlay: stop', e); }
        try { av.close(); } catch (e) { Log.w('AVPlay: close', e); }
        Log.i('AVPlay: chiuso');
        return Promise.resolve();
    }

    function play(part, drmName, startSeconds, onStateChange, options) {
        var startPaused = !!(options && options.startPaused);
        notifyState = onStateChange;
        var av = avplay();
        if (!av) {
            Log.e('AVPlay non disponibile (webapis assente: eseguire sulla TV)');
            return Promise.resolve(false);
        }
        var drmType = DRM_TYPES[drmName];
        Log.i('AVPlay ' + av.getVersion() + ': apro ' + part.dash + ' con ' + drmType + ' da ' + startSeconds.toFixed(0) + ' s');
        onStateChange('caricamento');

        return new Promise(function (resolve) {
            try {
                av.open(part.dash);
                active = true;
                av.setDisplayRect(0, 0, 1920, 1080);
                av.setListener({
                    onbufferingstart: function () { Log.i('AVPlay: buffering iniziato'); onStateChange('buffering'); },
                    onbufferingcomplete: function () {
                        Log.i('AVPlay: buffering completato');
                        onStateChange(av.getState() === 'PLAYING' ? 'in riproduzione' : 'in pausa');
                    },
                    oncurrentplaytime: function (ms) {
                        var now = Date.now();
                        if (now - lastTimeLog >= TIME_LOG_INTERVAL_MS) {
                            lastTimeLog = now;
                            Log.i('AVPlay: posizione ' + Math.round(ms / 1000) + ' s, stato ' + av.getState());
                        }
                    },
                    onstreamcompleted: function () { Log.i('AVPlay: fine del flusso'); onStateChange('terminato'); },
                    onevent: function (type, data) { Log.i('AVPlay evento ' + type, data); },
                    onerror: function (type) { Log.e('AVPlay errore ' + type); onStateChange('errore'); },
                    ondrmevent: function (drmEvent, drmData) {
                        var data = drmData || {};
                        Log.i('AVPlay ondrmevent ' + drmEvent + ' name=' + data.name + ' chiavi=' + Object.keys(data).join(','));
                        if (data.name === 'Challenge') handleChallenge(drmName, drmType, data);
                        else if (data.name === 'DrmError') Log.e('AVPlay errore DRM', { code: data.code, message: data.message });
                    }
                });

                var properties = { DeleteLicenseAfterUse: true, GetChallenge: true };
                if (drmType === DRM_TYPES.widevine) {
                    properties.AppSession = Api.deviceId;
                    properties.DataType = 'MPEG-DASH';
                }
                av.setDrm(drmType, 'SetProperties', JSON.stringify(properties));
                Log.i('AVPlay: proprietà DRM impostate', properties);
            } catch (e) {
                Log.e('AVPlay: errore di configurazione', e);
                onStateChange('errore');
                stop();
                resolve(false);
                return;
            }

            av.prepareAsync(function () {
                Log.i('AVPlay: preparato, durata ' + Math.round(av.getDuration() / 1000) + ' s');
                var startMs = Math.round(startSeconds * 1000);
                if (startMs > 0) {
                    // seekTo dopo prepare: la ripresa dalla posizione salvata sul server.
                    av.seekTo(startMs, function () { Log.i('AVPlay: posizionato a ' + startSeconds.toFixed(0) + ' s'); }, function (e) { Log.w('AVPlay: seek fallito', e); });
                }
                if (startPaused) {
                    onStateChange('in pausa');
                } else {
                    av.play();
                    onStateChange('in riproduzione');
                }
                resolve(true);
            }, function (error) {
                Log.e('AVPlay: prepareAsync fallito', error);
                onStateChange('errore');
                stop();
                resolve(false);
            });
        });
    }

    global.AvplayEngine = {
        play: play,
        stop: stop,
        togglePause: function () {
            var av = avplay();
            if (!av || !active) return;
            if (av.getState() === 'PLAYING') {
                av.pause();
                notifyState('in pausa');
            } else {
                av.play();
                notifyState('in riproduzione');
            }
        },
        pause: function () {
            var av = avplay();
            if (av && active && av.getState() === 'PLAYING') {
                av.pause();
                notifyState('in pausa');
            }
        },
        isPaused: function () {
            var av = avplay();
            try { return !av || !active || av.getState() !== 'PLAYING'; } catch (e) { return true; }
        },
        durationMs: function () {
            var av = avplay();
            try { return av && active ? av.getDuration() : 0; } catch (e) { return 0; }
        },
        seekBy: function (seconds) {
            var av = avplay();
            if (!av || !active) return;
            try {
                if (seconds >= 0) av.jumpForward(seconds * 1000); else av.jumpBackward(-seconds * 1000);
            } catch (e) {
                Log.w('AVPlay: salto non riuscito', e);
            }
        },
        positionMs: function () {
            var av = avplay();
            try { return av && active ? av.getCurrentTime() : 0; } catch (e) { return 0; }
        }
    };
})(window);
