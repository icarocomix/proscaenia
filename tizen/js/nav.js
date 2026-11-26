/*
 * Navigazione spaziale da telecomando, equivalente a quella di Compose su Android TV.
 *
 * Invece di un ordine lineare (che su una griglia di righe e schede costringe a scorrere tutto),
 * scelgo il prossimo elemento in base alla geometria: tra gli elementi ".focusable" visibili nella
 * radice attiva prendo quelli che stanno nella direzione premuta e preferisco il più vicino, pesando
 * di più lo scostamento laterale, così "Giù" scende nella riga sottostante restando in colonna.
 *
 * I contenitori ".focus-group" (le righe del catalogo) ricordano l'ultimo elemento con il focus:
 * rientrando nella riga dall'alto o dal basso il focus torna lì, come fa focusRestorer su Android.
 *
 * Scritto in ES5 per Chromium 63 (Tizen 5.0).
 */
(function (global) {
    'use strict';

    var root = document;

    function isVisible(el) {
        if (el.disabled || el.offsetParent === null) return false;
        var rect = el.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
    }

    function candidates() {
        return Array.prototype.filter.call(root.querySelectorAll('.focusable'), isVisible);
    }

    function center(rect) {
        return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    }

    function groupOf(el) {
        var node = el.parentNode;
        while (node && node !== document) {
            if (node.classList && node.classList.contains('focus-group')) return node;
            node = node.parentNode;
        }
        return null;
    }

    /** Distanza "pesata": lo scostamento ortogonale alla direzione conta tre volte quello lungo l'asse. */
    function score(from, to, direction) {
        var a = center(from);
        var b = center(to);
        var dx = b.x - a.x;
        var dy = b.y - a.y;
        // Tolleranza di un pixel per elementi allineati sullo stesso bordo.
        switch (direction) {
            case 'left': if (to.right > from.left + 1 && dx >= 0) return -1; return -dx + Math.abs(dy) * 3;
            case 'right': if (to.left < from.right - 1 && dx <= 0) return -1; return dx + Math.abs(dy) * 3;
            case 'up': if (to.bottom > from.top + 1 && dy >= 0) return -1; return -dy + Math.abs(dx) * 3;
            case 'down': if (to.top < from.bottom - 1 && dy <= 0) return -1; return dy + Math.abs(dx) * 3;
            default: return -1;
        }
    }

    function focus(el) {
        if (!el) return false;
        // preventScroll: lo scorrimento lo gestisco io qui sotto (sui Chromium più vecchi l'opzione è ignorata).
        try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
        var group = groupOf(el);
        if (group) group.lastFocused = el;
        // "nearest": scorro il minimo indispensabile nei contenitori interni (righe del catalogo).
        try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e) { el.scrollIntoView(false); }
        resetPageScroll();
        return true;
    }

    /**
     * La pagina è a dimensione fissa (1920×1080) e non deve mai scorrere: overflow:hidden blocca lo
     * scroll dell'utente ma non quello programmatico di focus()/scrollIntoView(). Se il viewport è più
     * basso di 1080 px (es. browser sul PC) il focus su un elemento in basso, come la scheda d'errore
     * del player, sposterebbe tutto il documento e lo lascerebbe spostato nelle schermate successive.
     * Azzero quindi lo scroll di pagina, lasciando intatto quello dei contenitori interni.
     */
    function resetPageScroll() {
        var html = document.documentElement;
        var body = document.body;
        if (window.pageXOffset || window.pageYOffset) window.scrollTo(0, 0);
        if (html.scrollTop || html.scrollLeft) { html.scrollTop = 0; html.scrollLeft = 0; }
        if (body && (body.scrollTop || body.scrollLeft)) { body.scrollTop = 0; body.scrollLeft = 0; }
    }

    function move(direction) {
        var current = document.activeElement;
        var list = candidates();
        if (list.length === 0) return false;
        if (!current || list.indexOf(current) < 0) return focus(list[0]);

        var from = current.getBoundingClientRect();
        var currentGroup = groupOf(current);
        var best = null;
        var bestScore = Infinity;
        list.forEach(function (el) {
            if (el === current) return;
            var s = score(from, el.getBoundingClientRect(), direction);
            if (s >= 0 && s < bestScore) {
                best = el;
                bestScore = s;
            }
        });
        if (!best) return false;

        // Cambiando riga preferisco l'ultimo elemento visitato in quella riga, se ancora presente.
        var targetGroup = groupOf(best);
        if (targetGroup && targetGroup !== currentGroup && targetGroup.lastFocused &&
                list.indexOf(targetGroup.lastFocused) >= 0) {
            best = targetGroup.lastFocused;
        }
        return focus(best);
    }

    global.Nav = {
        /** Limita la navigazione a un sottoalbero (schermata attiva o OSD del player). */
        setRoot: function (element) { root = element || document; },
        move: move,
        focus: focus,
        focusFirst: function () {
            var list = candidates();
            return list.length > 0 && focus(list[0]);
        },
        isInside: function (element) {
            return !!element && (root === document || root.contains(element));
        }
    };
})(window);
