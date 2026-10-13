package com.icarocomix.proscaenia.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.icarocomix.proscaenia.core.logging.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Stato della connettività del dispositivo, osservato per l'intera vita del processo.
 *
 * Serve a far ripartire da soli i caricamenti e la riproduzione interrotti da una caduta di rete:
 * invece di chiedere all'utente di toccare "Riprova", chi è fermo su un errore reagisce a
 * [reconnections]. Seguo la rete predefinita del sistema, cioè quella che OkHttp userà davvero, e
 * considero "in linea" una rete che dichiara la capacità INTERNET. Non attendo la validazione
 * (VALIDATED) perché su alcune reti arriva con secondi di ritardo o mai; un tentativo prematuro
 * fallisce in fretta e viene comunque ripetuto da [AutomaticRetry].
 *
 * Registro il callback una sola volta e non lo rimuovo: il monitor vive quanto il processo.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val online = MutableStateFlow(currentlyOnline())

    val isOnline: StateFlow<Boolean> = online.asStateFlow()

    /**
     * Emette ogni volta che il dispositivo torna in linea dopo essere stato senza rete. Salto il valore
     * corrente: lo StateFlow non ripete valori uguali, quindi ogni `true` successivo segue un `false`.
     */
    val reconnections: Flow<Unit> = online.drop(1).filter { it }.map { }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = update(true)

                    // Con il callback della rete predefinita, se il sistema promuove un'altra rete (Wi-Fi
                    // che cade con la rete mobile attiva) onAvailable arriva subito dopo: il passaggio
                    // false → true conta come riconnessione, ed è corretto, perché la rete è cambiata.
                    override fun onLost(network: Network) = update(false)

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                        update(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                },
            )
        } catch (e: RuntimeException) {
            // Alcuni produttori limitano il numero di callback per processo: senza monitor l'app funziona
            // comunque, perdendo solo la ripresa automatica al ritorno della rete.
            AppLog.w(TAG, "Monitoraggio della rete non disponibile", e)
        }
    }

    private fun update(value: Boolean) {
        if (online.value != value) AppLog.i(TAG, if (value) "Rete disponibile" else "Rete assente")
        online.value = value
    }

    /** Stato iniziale, letto in modo sincrono prima che il callback riceva il primo evento. */
    private fun currentlyOnline(): Boolean {
        val manager = connectivityManager ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
