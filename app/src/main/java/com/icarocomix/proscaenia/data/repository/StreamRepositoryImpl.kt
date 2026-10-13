package com.icarocomix.proscaenia.data.repository

import com.icarocomix.proscaenia.data.remote.ProscaeniaApi
import com.icarocomix.proscaenia.domain.model.StreamSource
import com.icarocomix.proscaenia.domain.repository.StreamRepository

/**
 * Risoluzione delle sorgenti di riproduzione, volutamente senza cache: ogni chiamata deve produrre
 * un URL appena firmato dalla piattaforma, altrimenti la rigenerazione dopo un 403/410 restituirebbe
 * lo stesso link già scaduto.
 */
class StreamRepositoryImpl(
    private val api: ProscaeniaApi,
) : StreamRepository {

    override suspend fun resolve(showId: String): StreamSource = api.fetchStream(showId)
}
