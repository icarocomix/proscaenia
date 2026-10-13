package com.icarocomix.proscaenia.core.network

import com.icarocomix.proscaenia.core.logging.AppLog
import com.icarocomix.proscaenia.domain.error.ApiException
import com.icarocomix.proscaenia.domain.error.DeviceLimitException
import com.icarocomix.proscaenia.domain.error.InactiveSubscriptionException
import com.icarocomix.proscaenia.domain.error.InvalidCredentialsException
import com.icarocomix.proscaenia.domain.error.MalformedResponseException
import com.icarocomix.proscaenia.domain.error.MissingCredentialsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Ritentativi automatici, con attesa crescente, delle chiamate verso la piattaforma.
 *
 * Le credenziali si chiedono una sola volta, al primo avvio: da lì in poi ogni problema di
 * connessione deve risolversi senza intervento dell'utente. Ripeto quindi in automatico le chiamate
 * fallite per cause transitorie (rete assente o instabile, timeout, errori 5xx, 408, 429) con attese
 * di 1, 2, 4 e 8 secondi, cioè circa un quarto di minuto in tutto prima di arrendermi; oltre questo
 * orizzonte subentrano i ritentativi dei ViewModel e del servizio, legati al ritorno della rete.
 *
 * Il rifiuto dell'accesso automatico (login con le credenziali salvate) lo ripeto una sola volta e
 * solo se il chiamante lo consente: le credenziali sono già state verificate al primo avvio, quindi
 * un rifiuto è con buona probabilità un disservizio momentaneo, ma ripeterlo a raffica farebbe
 * scattare la protezione anti forza bruta del backend. Non ripeto mai gli errori definitivi
 * (abbonamento non attivo, limite di dispositivi, contenuto inesistente, risposta malformata).
 *
 * Uso `delay` e non una sospensione del thread: chi chiude la schermata cancella anche l'attesa.
 */
object AutomaticRetry {

    private const val TAG = "AutomaticRetry"

    /** Attese tra un tentativo e il successivo: 5 tentativi complessivi. */
    internal val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)

    /** Tentativi aggiuntivi concessi a un accesso automatico rifiutato. */
    internal const val MAX_REJECTED_SIGN_IN_RETRIES = 1

    /**
     * Eseguo [block] ripetendolo sugli errori transitori. [retryRejectedSignIn] va a true per le
     * chiamate che accedono con le credenziali salvate, a false per il login del primo avvio, dove un
     * rifiuto significa davvero password errata e va mostrato subito.
     */
    suspend fun <T> run(
        label: String,
        retryRejectedSignIn: Boolean,
        sleep: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var rejectedSignIns = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e is InvalidCredentialsException) rejectedSignIns++
                val retryable = isTransient(e) ||
                    (e is InvalidCredentialsException && retryRejectedSignIn && rejectedSignIns <= MAX_REJECTED_SIGN_IN_RETRIES)
                if (!retryable || attempt >= BACKOFF_MS.size) throw e
                val wait = BACKOFF_MS[attempt++]
                AppLog.w(TAG, "$label non riuscito (${e.javaClass.simpleName}): nuovo tentativo $attempt tra $wait ms")
                sleep(wait)
            }
        }
    }

    /**
     * Classifico come transitori gli errori di trasporto (qualunque IOException che non sia una delle
     * eccezioni di dominio) e gli stati HTTP che indicano un problema momentaneo del server. Il 401
     * arriva qui solo se il backend ha rifiutato anche il token appena ottenuto dal rinnovo, cosa
     * plausibile soltanto per un disallineamento momentaneo tra i nodi del servizio.
     */
    fun isTransient(error: IOException): Boolean = when (error) {
        is InvalidCredentialsException,
        is InactiveSubscriptionException,
        is DeviceLimitException,
        is MissingCredentialsException,
        is MalformedResponseException,
        -> false
        is ApiException -> error.statusCode == 401 || error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
        else -> true
    }
}
