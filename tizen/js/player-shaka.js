/*
 * Riproduzione con Shaka Player (MSE + EME), cioè il percorso "web standard" della TV.
 *
 * Aggiungo alle richieste di licenza gli stessi header di dispositivo dell'app Android: il server
 * licenze lega la chiave al DeviceId. Registro ogni passaggio (manifest, licenza, avvio, stalli,
 * errori Shaka con codice) perché l'esito della prova si legge solo dal log.
 */
(function (global) {
    'use strict';

    var KEY_SYSTEMS = {
        widevine: 'com.widevine.alpha',
        playready: 'com.microsoft.playready'
    };
    var STATS_INTERVAL_MS = 10000;

    var player = null;
    var statsTimer = null;
    var video = null;
    var videoListeners = [];

    function onVideo(event, handler) {
        video.addEventListener(event, handler);
        videoListeners.push([event, handler]);
    }

    function logStats() {
        if (!player) return;
        var s = player.getStats();
        Log.i('Shaka stats: ' + s.width + 'x' + s.height + ', banda stimata ' + Math.round(s.estimatedBandwidth / 1000) +
            ' kbps, frame persi ' + s.droppedFrames + '/' + s.decodedFrames + ', buffering ' + s.bufferingTime.toFixed(1) +
            ' s, stalli ' + (s.stallsDetected || 0) + ', latenza licenza ' + (s.licenseTime || 0).toFixed(2) + ' s, posizione ' +
            (video ? video.currentTime.toFixed(0) : '?') + ' s');
    }

    // Traduco l'errore Shaka in una riga leggibile: categoria e codice numerico bastano per la tabella ufficiale.
    function describeError(error) {
        if (!error) return 'errore sconosciuto';
        var data = '';
        try { data = JSON.stringify(error.data).slice(0, 600); } catch (e) { data = '(dati non serializzabili)'; }
        return 'Shaka errore codice ' + error.code + ' categoria ' + error.category + ' severità ' + error.severity + ' dati ' + data;
    }

    function stop() {
        if (statsTimer) clearInterval(statsTimer);
        statsTimer = null;
        if (video) {
            videoListeners.forEach(function (pair) { video.removeEventListener(pair[0], pair[1]); });
            videoListeners = [];
        }
        if (!player) return Promise.resolve();
        var current = player;
        player = null;
        return current.destroy().then(function () {
            Log.i('Shaka: player distrutto');
        }, function (error) {
            Log.w('Shaka: errore in distruzione', error);
        });
    }

    function play(part, drmName, startSeconds, onStateChange) {
        video = document.getElementById('video');
        shaka.polyfill.installAll();
        if (!shaka.Player.isBrowserSupported()) {
            Log.e('Shaka: browser non supportato su questa TV');
            return Promise.resolve(false);
        }

        player = new shaka.Player();
        var servers = {};
        servers[KEY_SYSTEMS[drmName]] = Api.LICENSE_URLS[drmName];
        player.configure({
            drm: { servers: servers },
            // Buffer contenuto: la RU7400 ha poca memoria per il motore web, meglio non imitare i 120 s di Android.
            streaming: { bufferingGoal: 30, rebufferingGoal: 4, bufferBehind: 15 },
            abr: { enabled: true }
        });

        var networking = player.getNetworkingEngine();
        var licenseType = shaka.net.NetworkingEngine.RequestType.LICENSE;
        var licenseStarted = 0;
        networking.registerRequestFilter(function (type, request) {
            if (type !== licenseType) return;
            var headers = Api.licenseHeaders();
            Object.keys(headers).forEach(function (name) { request.headers[name] = headers[name]; });
            licenseStarted = Date.now();
            Log.i('Shaka: richiesta licenza ' + drmName + ' → ' + request.uris[0] + ' (' + (request.body ? request.body.byteLength : 0) + ' byte)');
        });
        networking.registerResponseFilter(function (type, response) {
            if (type !== licenseType) return;
            Log.i('Shaka: licenza ricevuta, ' + response.data.byteLength + ' byte in ' + (Date.now() - licenseStarted) + ' ms');
        });

        player.addEventListener('error', function (event) {
            Log.e(describeError(event.detail));
            onStateChange('errore');
        });
        player.addEventListener('buffering', function (event) {
            Log.i('Shaka: buffering ' + (event.buffering ? 'iniziato' : 'terminato'));
        });
        player.addEventListener('adaptation', function () {
            var track = player.getVariantTracks().filter(function (t) { return t.active; })[0];
            if (track) Log.i('Shaka: variante attiva ' + track.width + 'x' + track.height + ' ' + Math.round(track.bandwidth / 1000) + ' kbps ' + track.videoCodec);
        });
        onVideo('playing', function () { onStateChange('in riproduzione'); Log.i('Video: playing a ' + video.currentTime.toFixed(1) + ' s'); });
        onVideo('waiting', function () { Log.w('Video: waiting (stallo) a ' + video.currentTime.toFixed(1) + ' s'); });
        onVideo('pause', function () { onStateChange('in pausa'); });
        onVideo('error', function () { Log.e('Video: errore elemento', video.error ? { code: video.error.code, message: video.error.message } : null); });

        Log.i('Shaka ' + shaka.Player.version + ': carico ' + part.dash + ' con ' + drmName + ' da ' + startSeconds.toFixed(0) + ' s');
        onStateChange('caricamento');
        return player.attach(video).then(function () {
            return player.load(part.dash, startSeconds);
        }).then(function () {
            var drmInfo = player.drmInfo();
            Log.i('Shaka: manifest caricato, DRM attivo ' + (drmInfo ? drmInfo.keySystem : 'nessuno') + ', durata ' + video.duration.toFixed(0) + ' s');
            statsTimer = setInterval(logStats, STATS_INTERVAL_MS);
            return video.play().then(function () { return true; }, function (error) {
                Log.e('Video: play() rifiutato', error);
                return false;
            });
        }, function (error) {
            Log.e(describeError(error));
            onStateChange('errore');
            return false;
        });
    }

    global.ShakaEngine = {
        play: play,
        stop: stop,
        togglePause: function () {
            if (!video) return;
            if (video.paused) video.play(); else video.pause();
        },
        seekBy: function (seconds) {
            if (video && isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(video.duration - 1, video.currentTime + seconds));
        },
        positionMs: function () { return video ? Math.round(video.currentTime * 1000) : 0; }
    };
})(window);
