/*
 * Log della prova tecnica: a schermo, in memoria (per l'esportazione ZIP) e, facoltativamente,
 * inviato a un piccolo ricevitore sul PC (tools/tizen_log_receiver.py).
 *
 * Il log deve poter uscire dalla TV, quindi sanifico ogni riga prima di conservarla: tolgo le
 * query string degli URL (gli URL dei flussi sono firmati), maschero token Bearer, access_token e
 * password. Stessa regola del file di log dell'app Android.
 */
(function (global) {
    'use strict';

    var MAX_ENTRIES = 5000;
    var MAX_DOM_LINES = 400;
    var FLUSH_INTERVAL_MS = 2000;

    var entries = [];
    var pending = [];
    var serverUrl = null;
    var flushTimer = null;
    var logElement = null;

    function pad(n, width) {
        var s = String(n);
        while (s.length < width) s = '0' + s;
        return s;
    }

    function timestamp() {
        var d = new Date();
        return pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2) + ':' + pad(d.getSeconds(), 2) + '.' + pad(d.getMilliseconds(), 3);
    }

    // Rimuovo tutto ciò che può somigliare a una credenziale prima che la riga venga conservata.
    function sanitize(text) {
        return String(text)
            .replace(/(https?:\/\/[^\s?"'<>]+)\?[^\s"'<>]*/g, '$1?…')
            .replace(/(Bearer\s+)[A-Za-z0-9\-._~+\/=]+/gi, '$1***')
            .replace(/("(?:access_token|refresh_token|token|password)"\s*:\s*")[^"]*"/gi, '$1***"')
            .replace(/((?:password|token)=)[^&\s]+/gi, '$1***');
    }

    function stringify(value) {
        if (value === undefined) return '';
        if (value === null) return 'null';
        if (value instanceof Error) return value.name + ': ' + value.message;
        if (typeof value === 'string') return value;
        try {
            return JSON.stringify(value);
        } catch (e) {
            return String(value);
        }
    }

    function append(level, message, detail) {
        var line = timestamp() + ' ' + level + ' ' + sanitize(message + (detail !== undefined ? ' ' + stringify(detail) : ''));
        entries.push(line);
        if (entries.length > MAX_ENTRIES) entries.splice(0, entries.length - MAX_ENTRIES);
        if (serverUrl) pending.push(line);
        render(level, line);
        if (global.console) {
            (level === 'E' ? console.error : level === 'W' ? console.warn : console.log).call(console, line);
        }
    }

    // Aggiorno solo la coda del pannello: con migliaia di righe un innerHTML completo rallenterebbe la TV.
    function render(level, line) {
        if (!logElement) logElement = document.getElementById('log');
        if (!logElement) return;
        var span = document.createElement('span');
        if (level === 'W') span.className = 'w';
        if (level === 'E') span.className = 'e';
        span.textContent = line + '\n';
        logElement.appendChild(span);
        while (logElement.childNodes.length > MAX_DOM_LINES) logElement.removeChild(logElement.firstChild);
        logElement.scrollTop = logElement.scrollHeight;
    }

    // Invio a blocchi come text/plain: è una richiesta "semplice", quindi niente preflight CORS verso il PC.
    function flush() {
        if (!serverUrl || pending.length === 0) return;
        var batch = pending.splice(0, pending.length);
        var xhr = new XMLHttpRequest();
        xhr.open('POST', serverUrl + '/log', true);
        xhr.timeout = 5000;
        xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
        xhr.onerror = xhr.ontimeout = function () {
            // Rimetto in coda il blocco: il PC potrebbe essere momentaneamente irraggiungibile.
            pending = batch.concat(pending).slice(-MAX_ENTRIES);
        };
        xhr.send(batch.join('\n') + '\n');
    }

    global.Log = {
        i: function (message, detail) { append('I', message, detail); },
        w: function (message, detail) { append('W', message, detail); },
        e: function (message, detail) { append('E', message, detail); },
        sanitize: sanitize,
        text: function () { return entries.join('\n') + '\n'; },
        setServer: function (url) {
            serverUrl = url ? url.replace(/\/+$/, '') : null;
            if (flushTimer) clearInterval(flushTimer);
            flushTimer = null;
            if (serverUrl) {
                // Al primo collegamento invio anche lo storico, così il file sul PC è completo.
                pending = entries.slice();
                flushTimer = setInterval(flush, FLUSH_INTERVAL_MS);
                flush();
            }
        },
        serverUrl: function () { return serverUrl; },
        flush: flush
    };

    // Intercetto gli errori non gestiti: in una prova su TV sono spesso l'unico indizio disponibile.
    global.addEventListener('error', function (event) {
        append('E', 'Errore JS non gestito: ' + event.message + ' @' + (event.filename || '?') + ':' + event.lineno);
    });
    global.addEventListener('unhandledrejection', function (event) {
        append('E', 'Promise rifiutata non gestita', event.reason);
    });
})(window);
