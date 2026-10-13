package com.icarocomix.proscaenia.data.repository

import com.icarocomix.proscaenia.data.remote.CatalogSnapshot
import com.icarocomix.proscaenia.data.remote.ProscaeniaApi
import com.icarocomix.proscaenia.domain.error.ApiException
import com.icarocomix.proscaenia.domain.model.Category
import com.icarocomix.proscaenia.domain.model.ShowDetail
import com.icarocomix.proscaenia.domain.repository.CatalogRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Catalogo basato sullo snapshot unico della piattaforma, tenuto in memoria per la vita del processo.
 *
 * Il backend espone l'intero catalogo in un solo documento: lo scarico una volta e lo riuso sia per la
 * schermata principale sia per il dettaglio, così aprire una scheda non costa alcun round-trip e il
 * player può rileggere titolo e immagine per la notifica senza rete. Il "tira per aggiornare" forza
 * una nuova lettura. Un mutex serializza il caricamento, perché all'avvio catalogo e dettaglio
 * potrebbero richiederlo quasi insieme e non voglio scaricare lo snapshot due volte.
 */
class CatalogRepositoryImpl(
    private val api: ProscaeniaApi,
) : CatalogRepository {

    private val mutex = Mutex()

    @Volatile
    private var cached: CatalogSnapshot? = null

    override suspend fun categories(forceRefresh: Boolean): List<Category> =
        snapshot(forceRefresh).categories

    override suspend fun show(showId: String): ShowDetail =
        snapshot(forceRefresh = false).showsById[showId]
            ?: throw ApiException(404, "Spettacolo $showId non presente nel catalogo")

    private suspend fun snapshot(forceRefresh: Boolean): CatalogSnapshot {
        if (!forceRefresh) cached?.let { return it }
        return mutex.withLock {
            if (!forceRefresh) cached?.let { return it }
            api.fetchCatalog().also { cached = it }
        }
    }
}
