package com.icarocomix.proscaenia.ui.common

import com.icarocomix.proscaenia.core.network.NetworkMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Momenti in cui una schermata ferma su un errore deve riprovare da sola, senza attendere "Riprova".
 *
 * Gli eventi sono tre: la rete torna disponibile, l'utente riporta l'app in primo piano, oppure è
 * passato un minuto con l'app visibile e l'errore ancora presente (utile per i disservizi del server,
 * che non producono alcun evento di rete). Li considero solo con interfaccia visibile e dispositivo in
 * linea: in background un nuovo tentativo potrebbe rifare il login e rioccupare il posto dispositivo
 * appena liberato all'uscita, mentre senza rete fallirebbe in ogni caso. È il ViewModel a decidere,
 * a ogni evento, se c'è davvero un errore da recuperare.
 */
class AutoRetryTriggers(
    private val networkMonitor: NetworkMonitor,
    private val uiVisible: StateFlow<Boolean>,
) {

    fun events(): Flow<Unit> =
        merge(
            networkMonitor.reconnections,
            uiVisible.drop(1).filter { it }.map { },
            periodicWhileVisible(),
        ).filter { uiVisible.value && networkMonitor.isOnline.value }

    private fun periodicWhileVisible(): Flow<Unit> = flow {
        while (true) {
            // Sospendo il conteggio finché l'app non è visibile: in background non consumo nulla.
            uiVisible.first { it }
            delay(PERIODIC_RETRY_MS)
            emit(Unit)
        }
    }

    private companion object {
        const val PERIODIC_RETRY_MS = 60_000L
    }
}
