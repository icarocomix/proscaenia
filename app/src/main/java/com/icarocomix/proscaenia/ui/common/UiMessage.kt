package com.icarocomix.proscaenia.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionError
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.core.logging.AppLog
import com.icarocomix.proscaenia.core.network.AutomaticRetry
import com.icarocomix.proscaenia.domain.error.ApiException
import com.icarocomix.proscaenia.domain.error.DeviceLimitException
import com.icarocomix.proscaenia.domain.error.InactiveSubscriptionException
import com.icarocomix.proscaenia.domain.error.InvalidCredentialsException
import com.icarocomix.proscaenia.domain.error.MalformedResponseException
import com.icarocomix.proscaenia.domain.error.MissingCredentialsException
import java.io.IOException

/**
 * Messaggio destinato all'utente, espresso come risorsa stringa con i suoi argomenti.
 *
 * Faccio produrre ai ViewModel un riferimento a risorsa invece di una stringa già tradotta per
 * tenerli indipendenti dal Context e dalla lingua: la risoluzione avviene in Compose, quindi un
 * cambio di lingua di sistema aggiorna anche i messaggi già mostrati.
 */
data class UiMessage(
    @param:StringRes val resId: Int,
    val args: List<Any> = emptyList(),
)

@Composable
fun UiMessage.asString(): String = stringResource(resId, *args.toTypedArray())

/** Traduzione centralizzata degli errori in messaggi, così le schermate li presentano in modo uniforme. */
object ErrorMessages {

    /**
     * Ogni errore mostrato all'utente passa da qui, quindi è il punto naturale per registrarlo nel file
     * di log: in questo modo nessuna schermata deve ricordarsi di farlo e il file contiene esattamente
     * gli errori che l'utente ha visto, con la loro causa completa.
     */
    fun from(error: Throwable, context: String): UiMessage {
        AppLog.w(TAG, "$context: ${error.javaClass.simpleName}", error)
        return from(error)
    }

    private const val TAG = "UiError"

    /**
     * Fuori dalla schermata di primo avvio un rifiuto delle credenziali può nascere solo dall'accesso
     * automatico con quelle salvate, già verificate: lo presento come un problema momentaneo, che
     * l'app riprova da sola, e non come un invito a reinserirle. Il login lo traduce per conto suo.
     */
    fun from(error: Throwable): UiMessage = when (error) {
        is InvalidCredentialsException -> UiMessage(R.string.error_auto_sign_in_rejected)
        is InactiveSubscriptionException -> UiMessage(R.string.error_inactive_subscription)
        is DeviceLimitException -> UiMessage(R.string.error_device_limit)
        is MissingCredentialsException -> UiMessage(R.string.error_missing_credentials)
        is ApiException -> if (error.statusCode == 404) {
            UiMessage(R.string.error_not_found)
        } else {
            UiMessage(R.string.error_server, listOf(error.statusCode))
        }
        is MalformedResponseException -> UiMessage(R.string.error_malformed)
        is IOException -> UiMessage(R.string.error_network)
        else -> UiMessage(R.string.error_unknown)
    }

    /**
     * Vero per gli errori che un ritentativo automatico può risolvere: problemi di rete e del server,
     * accesso automatico rifiutato e limite di dispositivi (un posto può liberarsi dal sito o
     * dall'uscita di un altro dispositivo). Contenuti inesistenti, risposte malformate e abbonamento
     * non attivo non cambiano riprovando, quindi non li ripeto a vuoto ogni minuto.
     */
    fun isRetryable(error: Throwable): Boolean =
        error is IOException &&
            (AutomaticRetry.isTransient(error) || error is InvalidCredentialsException || error is DeviceLimitException)

    /** Stessa classificazione per gli errori che il servizio inoltra via sessione, di cui resta solo il codice. */
    fun isRetryable(error: SessionError): Boolean = error.code in RETRYABLE_SESSION_CODES

    private val RETRYABLE_SESSION_CODES = setOf(
        SessionError.ERROR_IO,
        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
        SessionError.ERROR_SESSION_CONCURRENT_STREAM_LIMIT,
    )

    /**
     * Gli errori del player arrivano alla UI attraverso la MediaSession, che trasmette il codice ma
     * non la catena delle cause: per questo mi baso solo sul codice. Un errore di stato HTTP che
     * arriva fin qui significa che il servizio ha già esaurito i tentativi di rigenerazione del link.
     */
    fun fromPlayback(error: PlaybackException): UiMessage = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        -> UiMessage(R.string.error_playback_link)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> UiMessage(R.string.error_playback_network)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> UiMessage(R.string.error_playback_format)
        // Gli spettacoli sono protetti con Widevine: distinguo i problemi di licenza (server che
        // rifiuta la richiesta, licenza scaduta, dispositivo non idoneo) da quelli di rete o di formato.
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        -> UiMessage(R.string.error_playback_drm)
        else -> UiMessage(R.string.error_playback_generic, listOf(error.errorCodeName))
    }

    /** Errori non legati al player che il servizio inoltra con `MediaSession.sendError`. */
    fun fromSession(error: SessionError): UiMessage = when (error.code) {
        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED -> UiMessage(R.string.error_auto_sign_in_rejected)
        SessionError.ERROR_SESSION_CONCURRENT_STREAM_LIMIT -> UiMessage(R.string.error_device_limit)
        SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED -> UiMessage(R.string.error_inactive_subscription)
        SessionError.ERROR_BAD_VALUE -> UiMessage(R.string.error_not_found)
        SessionError.ERROR_IO -> UiMessage(R.string.error_network)
        else -> UiMessage(R.string.error_unknown)
    }
}
