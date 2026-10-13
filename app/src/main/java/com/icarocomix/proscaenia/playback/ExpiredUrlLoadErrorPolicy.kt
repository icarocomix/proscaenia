package com.icarocomix.proscaenia.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Politica di gestione degli errori di caricamento consapevole degli URL a scadenza.
 *
 * La politica predefinita di Media3 ritenta più volte un caricamento fallito con HTTP 403 e, per HLS
 * e DASH, prova anche a escludere la variante e passare a un'altra qualità. Con un URL firmato
 * scaduto entrambe le strategie sono inutili, perché tutte le varianti condividono la stessa firma,
 * e ritardano soltanto di diversi secondi il momento in cui posso rigenerare il link. Per 403 e 410
 * faccio quindi fallire subito il caricamento, così l'errore arriva al servizio che esegue la
 * rigenerazione; per tutti gli altri errori mantengo il comportamento standard.
 */
@UnstableApi
class ExpiredUrlLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isExpiredUrl(loadErrorInfo)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (isExpiredUrl(loadErrorInfo)) return null
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    private fun isExpiredUrl(info: LoadErrorHandlingPolicy.LoadErrorInfo): Boolean {
        val exception = info.exception
        return exception is HttpDataSource.InvalidResponseCodeException &&
            exception.responseCode in PlaybackErrors.EXPIRED_URL_STATUS_CODES
    }
}
