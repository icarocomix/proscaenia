package com.icarocomix.proscaenia.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Unico punto in cui è descritta la forma degli endpoint reali della piattaforma.
 *
 * Il contratto l'ho ricavato dal client web ufficiale e verificato con richieste reali: le API
 * vivono sotto https://proscaenia.com/backoffice/api/, il login è POST auth/login, il catalogo
 * completo è pubblico in catalog/snapshot e la scheda con le sorgenti video è show/{id} con
 * preview=false. Costruisco i percorsi con `addPathSegment` per gli identificativi, così un ID con
 * caratteri riservati viene codificato invece di alterare la struttura dell'URL.
 */
class ProscaeniaEndpoints(val baseUrl: HttpUrl) {

    val login: HttpUrl = baseUrl.newBuilder().addPathSegments("auth/login").build()

    val catalogSnapshot: HttpUrl = baseUrl.newBuilder().addPathSegments("catalog/snapshot").build()

    /** Logout del client web: invalida il token lato server. */
    val logout: HttpUrl = baseUrl.newBuilder().addPathSegments("auth/logout").build()

    /**
     * Voce di un dispositivo registrato sull'account (DELETE = "Disconnetti" della pagina del sito).
     * Il sito identifica il dispositivo con lo stesso valore inviato nell'header DeviceId.
     */
    fun userDevice(deviceId: String): HttpUrl =
        baseUrl.newBuilder().addPathSegments("user/device").addPathSegment(deviceId).build()

    /** Scheda completa per la visione: contiene le sorgenti video firmate e il progresso salvato. */
    fun showForPlayback(showId: String): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegment("show")
            .addPathSegment(showId)
            .addQueryParameter("preview", "false")
            .build()

    /** Server delle licenze Widevine usato dal client web (integrazione KeyOS). */
    val widevineLicense: HttpUrl = "https://drm.proscaenia.com/?drm-type=widevine".toHttpUrl()

    /** Origine del sito, che il backend si aspetta negli header Origin e Referer. */
    val siteOrigin: String = "${baseUrl.scheme}://${baseUrl.host}"

    private val apiPathPrefix: String = baseUrl.encodedPath

    /** Vero per tutte le richieste verso le API della piattaforma. */
    fun isApi(url: HttpUrl): Boolean = url.host == baseUrl.host && url.encodedPath.startsWith(apiPathPrefix)

    /**
     * Stabilisco quali richieste richiedono una sessione: tutte le API tranne il login stesso (che
     * altrimenti innescherebbe un login ricorsivo) e il catalogo, che è pubblico e deve funzionare
     * anche prima di qualunque autenticazione.
     */
    fun requiresSession(url: HttpUrl): Boolean =
        isApi(url) && url.encodedPath != login.encodedPath && url.encodedPath != catalogSnapshot.encodedPath

    /** Risolvo gli URL relativi (immagini, flussi) rispetto all'origine della piattaforma. */
    fun absolute(rawUrl: String): String? = baseUrl.resolve(rawUrl)?.toString()
}
