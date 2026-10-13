package com.icarocomix.proscaenia.domain.repository

import com.icarocomix.proscaenia.domain.model.Category
import com.icarocomix.proscaenia.domain.model.ShowDetail
import com.icarocomix.proscaenia.domain.model.StreamSource

/*
 * Contratti del livello di dominio. Li tengo separati dalle implementazioni perché ViewModel e
 * servizio di riproduzione dipendano solo da queste astrazioni: così posso sostituire la sorgente
 * dati (per esempio quando il contratto reale delle API sarà allineato) senza toccare la UI, e i
 * consumatori restano testabili con implementazioni finte.
 */

interface AuthRepository {

    /** Vero se il primo avvio è già stato completato e le credenziali sono salvate sul dispositivo. */
    fun hasStoredCredentials(): Boolean

    /** Verifico le credenziali con un login reale e, solo se accettate, le salvo in modo cifrato. */
    suspend fun signIn(username: String, password: String)
}

interface CatalogRepository {

    suspend fun categories(forceRefresh: Boolean = false): List<Category>

    suspend fun show(showId: String): ShowDetail
}

interface StreamRepository {

    /** Ottengo ogni volta un URL fresco: le sorgenti scadono e non vanno mai riutilizzate da cache. */
    suspend fun resolve(showId: String): StreamSource
}
