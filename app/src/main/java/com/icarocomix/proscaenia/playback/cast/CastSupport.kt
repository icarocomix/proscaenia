package com.icarocomix.proscaenia.playback.cast

import android.content.Context
import com.icarocomix.proscaenia.core.logging.AppLog
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Punto di accesso unico al framework Cast, tollerante all'assenza di Google Play Services.
 *
 * Su dispositivi senza servizi Google (o con una versione troppo vecchia) `CastContext` lancia
 * eccezioni già all'inizializzazione. Verifico prima la disponibilità dei servizi e intercetto
 * qualunque errore successivo: in quel caso restituisco null, la UI nasconde il pulsante Cast e il
 * servizio costruisce il solo player locale, così il resto dell'app funziona senza differenze.
 *
 * L'inizializzazione è pigra e va eseguita sul thread principale, come richiede il framework Cast;
 * sia il servizio sia la UI la invocano da lì.
 */
class CastSupport(context: Context) {

    private val appContext = context.applicationContext

    private val castContext: CastContext? by lazy { createCastContext() }

    fun castContextOrNull(): CastContext? = castContext

    val isAvailable: Boolean get() = castContext != null

    /** Nome del Chromecast collegato, per il messaggio mostrato in UI durante il Cast. */
    fun connectedDeviceName(): String? =
        castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName

    @Suppress("DEPRECATION")
    private fun createCastContext(): CastContext? {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) {
            AppLog.i(TAG, "Google Play Services non disponibili (codice $availability): Cast disattivato")
            return null
        }
        return try {
            CastContext.getSharedInstance(appContext)
        } catch (e: Exception) {
            // Intercetto in modo ampio perché il framework può lanciare eccezioni diverse a seconda
            // della versione dei servizi (moduli dinamici mancanti, stato illegale, errori interni).
            AppLog.w(TAG, "Inizializzazione del Cast non riuscita: Cast disattivato", e)
            null
        }
    }

    private companion object {
        const val TAG = "CastSupport"
    }
}
