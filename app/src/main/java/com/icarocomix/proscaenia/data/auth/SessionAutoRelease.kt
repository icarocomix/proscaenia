package com.icarocomix.proscaenia.data.auth

import androidx.annotation.MainThread
import com.icarocomix.proscaenia.core.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Libera in automatico il posto dispositivo quando l'app viene lasciata.
 *
 * Android non notifica in modo affidabile la "chiusura" di un'app: lo swipe dalle app recenti
 * termina il processo senza alcun callback se non c'è un servizio attivo. Per questo considero
 * l'app lasciata quando si verificano insieme due condizioni osservabili in modo certo:
 *
 * - l'interfaccia non è visibile (onStop dell'unica activity, che precede sempre lo swipe);
 * - non c'è una riproduzione attiva, cioè uno spettacolo caricato in riproduzione, in buffering o
 *   in pausa. Uno spettacolo fermato, terminato o in errore non conta.
 *
 * Non uso la sola esistenza del servizio di riproduzione perché i controlli multimediali di sistema
 * restano collegati alla sessione e lo tengono in vita anche a riproduzione ferma: verificato sul
 * dispositivo, il posto non sarebbe mai stato liberato. La pausa invece mantiene la sessione, perché
 * dalla notifica si può riprendere e la riproduzione in background può dover rigenerare gli URL.
 *
 * Gli stati li aggiornano activity e servizio sul thread principale, quindi non serve
 * sincronizzazione; il lavoro di rete parte sullo scope applicativo di I/O.
 */
class SessionAutoRelease(
    private val sessionManager: SessionManager,
    private val ioScope: CoroutineScope,
) {

    private var uiVisible = false
    private var playbackActive = false

    @MainThread
    fun onUiVisibilityChanged(visible: Boolean) {
        if (uiVisible == visible) return
        uiVisible = visible
        releaseIfLeft()
    }

    /** Chiamato dal servizio a ogni evento del player: agisco solo quando lo stato cambia davvero. */
    @MainThread
    fun onPlaybackActiveChanged(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        releaseIfLeft()
    }

    private fun releaseIfLeft() {
        if (uiVisible || playbackActive) return
        AppLog.i(TAG, "App lasciata (interfaccia chiusa, nessuna riproduzione attiva): chiudo la sessione")
        // Se non c'è mai stata una sessione (catalogo sfogliato senza riprodurre) releaseSession
        // termina subito senza richieste di rete.
        ioScope.launch { sessionManager.releaseSession() }
    }

    private companion object {
        const val TAG = "SessionAutoRelease"
    }
}
