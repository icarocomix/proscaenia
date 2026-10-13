package com.icarocomix.proscaenia.data.remote

import androidx.core.text.HtmlCompat
import com.icarocomix.proscaenia.domain.error.MalformedResponseException
import com.icarocomix.proscaenia.domain.model.Category
import com.icarocomix.proscaenia.domain.model.DrmConfig
import com.icarocomix.proscaenia.domain.model.ShowDetail
import com.icarocomix.proscaenia.domain.model.ShowSummary
import com.icarocomix.proscaenia.domain.model.StreamSource
import com.icarocomix.proscaenia.domain.model.VideoPart
import org.json.JSONObject

/** Catalogo completo già tradotto: le righe da mostrare e tutte le schede indicizzate per ID. */
data class CatalogSnapshot(
    val categories: List<Category>,
    val showsById: Map<String, ShowDetail>,
)

/**
 * Traduzione dal JSON reale della piattaforma ai modelli di dominio.
 *
 * Il contratto l'ho ricavato dal client web e verificato contro il servizio: catalog/snapshot è un
 * unico documento pubblico con l'intero catalogo (elenco spettacoli, righe editoriali, generi), mentre
 * show/{id}?preview=false restituisce la scheda con le sorgenti video firmate e il progresso di
 * visione. Scarto in modo silenzioso i singoli elementi incompleti (uno spettacolo senza ID non è
 * navigabile), mentre considero un errore l'assenza dei campi strutturali, perché in quel caso il
 * contratto non è rispettato e una schermata vuota nasconderebbe il problema.
 */
internal class ProscaeniaJsonMapper(private val endpoints: ProscaeniaEndpoints) {

    fun catalog(root: JSONObject): CatalogSnapshot {
        if (!root.has(FIELD_SHOWS)) throw MalformedResponseException("Catalogo privo del campo '$FIELD_SHOWS'")

        // Indicizzo prima generi e schede, poi compongo le righe risolvendo i loro show_ids: così la
        // stessa scheda compare in più righe senza essere riletta, e le righe restano nell'ordine del sito.
        val categoryNames = root.arrayOrEmpty(FIELD_CATEGORIES).mapObjects { category ->
            category.stringOrNull(FIELD_ID)?.let { id -> id to category.stringOrNull(FIELD_NAME) }
        }.toMap()

        val detailsById = LinkedHashMap<String, ShowDetail>()
        val summariesById = LinkedHashMap<String, ShowSummary>()
        root.arrayOrEmpty(FIELD_SHOWS).mapObjects { show ->
            val id = show.stringOrNull(FIELD_ID) ?: return@mapObjects null
            detailsById[id] = snapshotDetail(show, id, categoryNames)
            summariesById[id] = snapshotSummary(show, id)
        }

        val rows = root.arrayOrEmpty(FIELD_ROWS).mapObjects { row ->
            val id = row.stringOrNull(FIELD_ID) ?: return@mapObjects null
            val shows = idList(row.arrayOrEmpty(FIELD_SHOW_IDS)).mapNotNull { summariesById[it] }
            Category(id = id, name = row.stringOrNull(FIELD_TITLE) ?: id, shows = shows)
        }.filter { it.shows.isNotEmpty() }

        return CatalogSnapshot(categories = rows, showsById = detailsById)
    }

    /**
     * Compongo le sorgenti per la riproduzione. La licenza DRM e i suoi header li fornisce il livello
     * dati (dipendono dall'identità del dispositivo), qui li applico a ogni parte protetta. La durata
     * della sigla iniziale la escludo dal conteggio: la posizione salvata deve riferirsi allo spettacolo.
     */
    fun streamSource(root: JSONObject, drmConfig: DrmConfig): StreamSource {
        val data = root.optJSONObject(FIELD_DATA) ?: throw MalformedResponseException("Scheda priva di '$FIELD_DATA'")
        val parts = data.arrayOrEmpty(FIELD_VIDEO).mapObjects { video ->
            val urls = video.optJSONObject(FIELD_URLS) ?: return@mapObjects null
            val dash = urls.stringOrNull(FIELD_DASH)?.let(endpoints::absolute) ?: return@mapObjects null
            val isIntro = video.stringOrNull(FIELD_TYPE) == TYPE_ADS
            VideoPart(
                dashUrl = dash,
                hlsUrl = urls.stringOrNull(FIELD_HLS)?.let(endpoints::absolute),
                drm = if (video.optInt(FIELD_DRM, 0) == 1) drmConfig else null,
                durationMs = video.optJSONObject(FIELD_DURATION)?.longOrNull(FIELD_MS) ?: 0L,
                isIntro = isIntro,
            )
        }
        if (parts.none { !it.isIntro }) throw MalformedResponseException("Scheda priva di una parte video riproducibile")
        val featureDuration = parts.firstOrNull { !it.isIntro }?.durationMs ?: 0L
        return StreamSource(
            parts = parts,
            subtitles = emptyList(),
            serverProgressMs = data.optJSONObject(FIELD_USER_DATA)?.longOrNull(FIELD_PROGRESS) ?: 0L,
            featureDurationMs = featureDuration,
        )
    }

    private fun snapshotDetail(show: JSONObject, id: String, categoryNames: Map<String, String?>): ShowDetail =
        ShowDetail(
            id = id,
            title = show.stringOrNull(FIELD_TITLE) ?: id,
            synopsis = stripHtml(show.stringOrNull(FIELD_CONTENT)),
            durationSeconds = null,
            thumbnailUrl = thumbnailOf(show),
            categoryName = show.stringOrNull(FIELD_CATEGORY_ID)?.let { categoryNames[it] } ?: categoryNames[show.optInt(FIELD_CATEGORY_ID, -1).toString()],
        )

    private fun snapshotSummary(show: JSONObject, id: String): ShowSummary =
        ShowSummary(
            id = id,
            title = show.stringOrNull(FIELD_TITLE) ?: id,
            thumbnailUrl = thumbnailOf(show),
            durationSeconds = null,
        )

    /** Preferisco la locandina verticale, con ripiego sull'immagine di testata: è il poster del catalogo. */
    private fun thumbnailOf(show: JSONObject): String? =
        (show.stringOrNull(FIELD_IMG_PREVIEW) ?: show.stringOrNull(FIELD_IMG_HEADER))?.let(endpoints::absolute)

    private fun idList(array: org.json.JSONArray): List<String> =
        (0 until array.length()).mapNotNull { index ->
            when {
                array.isNull(index) -> null
                else -> array.optLong(index, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.toString()
                    ?: array.optString(index).takeIf { it.isNotBlank() }
            }
        }

    /**
     * La sinossi arriva come HTML con entità di ogni tipo (&rsquo;, &egrave;, numeriche...): la faccio
     * interpretare al parser HTML di sistema invece di sostituire a mano un elenco di entità, che
     * sul servizio reale si è rivelato incompleto. Poi compatto gli spazi, compresi quelli non
     * separabili prodotti da &nbsp;.
     */
    private fun stripHtml(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private companion object {
        const val FIELD_SHOWS = "shows"
        const val FIELD_ROWS = "rows"
        const val FIELD_CATEGORIES = "categories"
        const val FIELD_SHOW_IDS = "show_ids"
        const val FIELD_DATA = "data"
        const val FIELD_ID = "id"
        const val FIELD_NAME = "name"
        const val FIELD_TITLE = "title"
        const val FIELD_CONTENT = "content"
        const val FIELD_CATEGORY_ID = "category_id"
        const val FIELD_IMG_PREVIEW = "img_preview"
        const val FIELD_IMG_HEADER = "img_header"
        const val FIELD_VIDEO = "video"
        const val FIELD_URLS = "urls"
        const val FIELD_DASH = "dash"
        const val FIELD_HLS = "hls"
        const val FIELD_DRM = "drm"
        const val FIELD_TYPE = "type"
        const val FIELD_DURATION = "duration"
        const val FIELD_MS = "ms"
        const val FIELD_USER_DATA = "user_data"
        const val FIELD_PROGRESS = "progress"
        const val TYPE_ADS = "ads"
    }
}
