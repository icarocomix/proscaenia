/*
 * Esportazione del log come archivio ZIP.
 *
 * Su Tizen TV non esiste un "menu di condivisione" tra app come su Android, e il motore web di
 * Tizen 5.0 non ha la Web Share API. Offro quindi tre uscite, dalla più universale alla più comoda:
 *  1. salvataggio su chiavetta USB inserita nella TV (o, in sua assenza, nella cartella Download);
 *  2. invio dello ZIP al ricevitore sul PC (tools/tizen_log_receiver.py);
 *  3. condivisione nativa con la Web Share API, solo se il runtime la espone (firmware futuri).
 */
(function (global) {
    'use strict';

    function pad(n) { return (n < 10 ? '0' : '') + n; }

    function fileName() {
        var d = new Date();
        return 'proscaenia-tizen-log-' + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + '-' +
            pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds()) + '.zip';
    }

    function deviceSummary() {
        var lines = [
            'Generato: ' + new Date().toISOString(),
            'User-Agent: ' + navigator.userAgent,
            'DeviceData: ' + Api.deviceData
        ];
        try { lines.push('Firmware: ' + webapis.productinfo.getFirmware()); } catch (e) { lines.push('Firmware: non disponibile'); }
        return lines.join('\n') + '\n';
    }

    // Il log è già sanificato riga per riga da Log: nello ZIP finisce solo ciò che è visibile a schermo.
    function buildZip() {
        if (!global.JSZip) return Promise.reject(new Error('JSZip non caricato'));
        var zip = new JSZip();
        zip.file('proscaenia-tizen-log.txt', Log.text());
        zip.file('dispositivo.txt', deviceSummary());
        return zip.generateAsync({ type: 'uint8array', compression: 'DEFLATE', compressionOptions: { level: 6 } }).then(function (bytes) {
            var name = fileName();
            Log.i('ZIP creato: ' + name + ', ' + bytes.length + ' byte');
            return { name: name, bytes: bytes };
        });
    }

    // Su Tizen 5.0 i privilegi di archiviazione possono richiedere un consenso a runtime: lo chiedo se l'API esiste.
    function requestStoragePermission() {
        return new Promise(function (resolve) {
            if (!global.tizen || !tizen.ppm || !tizen.ppm.requestPermission) return resolve();
            try {
                tizen.ppm.requestPermission('http://tizen.org/privilege/externalstorage', function (result) {
                    Log.i('Permesso archiviazione esterna: ' + result);
                    resolve();
                }, function (error) {
                    Log.w('Permesso archiviazione esterna non ottenuto', error);
                    resolve();
                });
            } catch (e) {
                resolve();
            }
        });
    }

    // Preferisco una chiavetta USB montata: è il modo più semplice per portare il file al PC o al telefono.
    function chooseStorage() {
        return new Promise(function (resolve) {
            try {
                tizen.filesystem.listStorages(function (storages) {
                    var usb = storages.filter(function (s) { return s.type === 'EXTERNAL' && s.state === 'MOUNTED'; })[0];
                    Log.i('Archivi disponibili: ' + storages.map(function (s) { return s.label + '(' + s.type + ',' + s.state + ')'; }).join(' '));
                    resolve(usb ? usb.label : 'downloads');
                }, function (error) {
                    Log.w('Elenco archivi non disponibile', error);
                    resolve('downloads');
                });
            } catch (e) {
                resolve('downloads');
            }
        });
    }

    // API di Tizen 5.0 (FileHandle): scrittura binaria diretta.
    function writeModern(root, name, bytes) {
        var handle = tizen.filesystem.openFile(root + '/' + name, 'w');
        try {
            handle.writeData(bytes);
        } finally {
            handle.close();
        }
    }

    // API storica (resolve + openStream), per firmware che non hanno ancora openFile.
    function writeLegacy(root, name, bytes) {
        return new Promise(function (resolve, reject) {
            tizen.filesystem.resolve(root, function (dir) {
                try {
                    var file = dir.createFile(name);
                    file.openStream('w', function (stream) {
                        try {
                            stream.writeBytes(Array.prototype.slice.call(bytes));
                            stream.close();
                            resolve();
                        } catch (e) {
                            reject(e);
                        }
                    }, reject);
                } catch (e) {
                    reject(e);
                }
            }, reject, 'rw');
        });
    }

    function saveToStorage() {
        if (!global.tizen || !tizen.filesystem) return Promise.reject(new Error('Filesystem Tizen non disponibile (eseguire sulla TV)'));
        var archive;
        return buildZip().then(function (zip) {
            archive = zip;
            return requestStoragePermission();
        }).then(chooseStorage).then(function (root) {
            if (typeof tizen.filesystem.openFile === 'function') {
                try {
                    writeModern(root, archive.name, archive.bytes);
                    return root;
                } catch (e) {
                    Log.w('Scrittura con openFile non riuscita, provo l\'API storica', e);
                }
            }
            return writeLegacy(root, archive.name, archive.bytes).then(function () { return root; });
        }).then(function (root) {
            Log.i('ZIP salvato in ' + root + '/' + archive.name);
            return root + '/' + archive.name;
        });
    }

    function sendToPc() {
        var server = Log.serverUrl();
        if (!server) return Promise.reject(new Error('Ricevitore PC non impostato: inserire l\'indirizzo e premere "Attiva invio al PC"'));
        return buildZip().then(function (archive) {
            return Api.request({
                method: 'POST',
                url: server + '/upload?name=' + encodeURIComponent(archive.name),
                headers: { 'Content-Type': 'application/zip' },
                // Invio la vista e non il suo buffer: il buffer potrebbe essere più grande dei dati effettivi.
                body: archive.bytes,
                timeout: 30000
            }).then(function (result) {
                if (!result.ok) throw new Error('Invio al PC non riuscito: ' + Api.describe(result));
                Log.i('ZIP inviato al PC: ' + archive.name);
                return archive.name;
            });
        });
    }

    function shareAvailable() {
        return typeof navigator.share === 'function' && typeof global.File === 'function';
    }

    function share() {
        if (!shareAvailable()) {
            return Promise.reject(new Error('Condivisione nativa non disponibile su questa TV: usare "Esporta ZIP (USB)" o "Invia ZIP al PC"'));
        }
        return buildZip().then(function (archive) {
            var file = new File([archive.bytes], archive.name, { type: 'application/zip' });
            var data = { files: [file], title: 'Log Proscaenia Tizen', text: 'Log della prova tecnica Proscaenia su Tizen' };
            if (navigator.canShare && !navigator.canShare(data)) throw new Error('Il sistema non consente di condividere file ZIP');
            return navigator.share(data).then(function () {
                Log.i('ZIP condiviso: ' + archive.name);
                return archive.name;
            });
        });
    }

    global.LogExport = {
        saveToStorage: saveToStorage,
        sendToPc: sendToPc,
        share: share,
        shareAvailable: shareAvailable
    };
})(window);
