package com.icarocomix.proscaenia.domain.model

/** Genere teatrale (commedia, dramma, ...) con gli spettacoli che la piattaforma vi associa. */
data class Category(
    val id: String,
    val name: String,
    val shows: List<ShowSummary>,
)

/** Informazioni minime per mostrare uno spettacolo in una riga del catalogo. */
data class ShowSummary(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
)

/** Scheda completa dello spettacolo, mostrata nella pagina di dettaglio e usata per la notifica. */
data class ShowDetail(
    val id: String,
    val title: String,
    val synopsis: String,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val categoryName: String?,
)
