package com.icarocomix.proscaenia.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.SessionError
import com.icarocomix.proscaenia.domain.error.ApiException
import com.icarocomix.proscaenia.domain.error.DeviceLimitException
import com.icarocomix.proscaenia.domain.error.InactiveSubscriptionException
import com.icarocomix.proscaenia.domain.error.InvalidCredentialsException
import com.icarocomix.proscaenia.domain.error.MalformedResponseException
import com.icarocomix.proscaenia.domain.error.MissingCredentialsException
import java.io.IOException

/**
 * Classificazione degli errori di riproduzione condivisa tra servizio e UI.
 */
object PlaybackErrors {

    /** Codici con cui le CDN segnalano un URL firmato scaduto o revocato. */
    val EXPIRED_URL_STATUS_CODES = setOf(403, 410)

    /**
     * Cerco l'errore HTTP lungo tutta la catena delle cause e non solo nella causa diretta: a seconda
     * del tipo di sorgente (progressiva, segmento HLS, manifest DASH) Media3 può incapsulare
     * l'InvalidResponseCodeException in uno o più livelli di eccezioni intermedie.
     */
    fun expiredUrlStatus(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode.takeIf { it in EXPIRED_URL_STATUS_CODES }
            }
            cause = cause.cause
        }
        return null
    }

    /**
     * Errori del player dovuti alla connessione (rete caduta, timeout, lettura interrotta): non
     * dipendono dal contenuto e si risolvono ricaricando la sorgente quando la rete è disponibile.
     */
    fun isNetworkError(error: PlaybackException): Boolean = error.errorCode in NETWORK_ERROR_CODES

    private val NETWORK_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

    /** Errore di "contenuto non riproducibile" da inviare ai controller tramite la sessione. */
    fun unplayable(message: String): SessionError = SessionError(SessionError.ERROR_NOT_SUPPORTED, message)

    /**
     * Traduco un errore di risoluzione della sorgente in un SessionError, l'unico canale con cui il
     * servizio può informare i controller collegati (la UI, ma anche un controller esterno) di un
     * problema che non nasce dal player. Il rifiuto dell'accesso automatico lo segnalo come
     * "autenticazione scaduta": la UI lo presenta come problema momentaneo e lo riprova da sola,
     * senza mai chiedere di reinserire le credenziali.
     */
    fun toSessionError(error: Throwable): SessionError {
        val code = when (error) {
            is InvalidCredentialsException, is MissingCredentialsException -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
            // Media3 non ha un codice per il "limite di dispositivi": uso quello dei flussi
            // concorrenti, il più vicino per significato, e la UI lo traduce nel messaggio corretto.
            is DeviceLimitException -> SessionError.ERROR_SESSION_CONCURRENT_STREAM_LIMIT
            is InactiveSubscriptionException -> SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED
            is ApiException -> if (error.statusCode == 404) SessionError.ERROR_BAD_VALUE else SessionError.ERROR_IO
            is MalformedResponseException -> SessionError.ERROR_UNKNOWN
            is IOException -> SessionError.ERROR_IO
            else -> SessionError.ERROR_UNKNOWN
        }
        return SessionError(code, error.message ?: error.javaClass.simpleName)
    }
}
