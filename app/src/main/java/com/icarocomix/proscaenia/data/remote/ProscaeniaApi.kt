package com.icarocomix.proscaenia.data.remote

import com.icarocomix.proscaenia.core.network.AutomaticRetry
import com.icarocomix.proscaenia.core.network.DeviceIdentity
import com.icarocomix.proscaenia.core.network.await
import com.icarocomix.proscaenia.domain.error.ApiException
import com.icarocomix.proscaenia.domain.error.MalformedResponseException
import com.icarocomix.proscaenia.domain.model.DrmConfig
import com.icarocomix.proscaenia.domain.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * Accesso di basso livello alle API reali della piattaforma.
 *
 * Il client che ricevo ha già gli header di piattaforma e, per le chiamate autenticate, l'interceptor
 * di sessione: qui mi occupo solo di eseguire la GET, verificare lo stato HTTP e affidare il corpo al
 * mapper. Leggo e interpreto la risposta su Dispatchers.IO perché lettura dello stream e parsing JSON
 * sono bloccanti e non devono toccare il thread principale.
 */
class ProscaeniaApi(
    private val client: OkHttpClient,
    private val endpoints: ProscaeniaEndpoints,
    private val deviceIdentity: DeviceIdentity,
) {

    private val mapper = ProscaeniaJsonMapper(endpoints)

    suspend fun fetchCatalog(): CatalogSnapshot = getJson(endpoints.catalogSnapshot) { mapper.catalog(it) }

    suspend fun fetchStream(showId: String): StreamSource =
        getJson(endpoints.showForPlayback(showId)) { mapper.streamSource(it, drmConfig()) }

    /**
     * Configurazione DRM per Widevine: URL del server licenze della piattaforma e header che la
     * richiesta di licenza deve portare. Il server lega la chiave al dispositivo tramite DeviceId,
     * quindi invio gli stessi identificativi usati per le API, più Origin e Referer come farebbe il sito.
     */
    private fun drmConfig(): DrmConfig = DrmConfig(
        licenseUrl = endpoints.widevineLicense.toString(),
        requestHeaders = mapOf(
            "DeviceId" to deviceIdentity.deviceId,
            "DeviceData" to deviceIdentity.deviceData,
            "Origin" to endpoints.siteOrigin,
            "Referer" to "${endpoints.siteOrigin}/",
        ),
    )

    /**
     * Ogni GET passa da [AutomaticRetry]: l'eventuale login con le credenziali salvate avviene dentro
     * l'interceptor di sessione, quindi un errore di rete durante l'accesso automatico viene ripetuto
     * qui insieme alla richiesta, senza mai risalire fino a una schermata di login.
     */
    private suspend fun <T> getJson(url: HttpUrl, transform: (JSONObject) -> T): T =
        AutomaticRetry.run("Richiesta a ${url.encodedPath}", retryRejectedSignIn = true) { getJsonOnce(url, transform) }

    private suspend fun <T> getJsonOnce(url: HttpUrl, transform: (JSONObject) -> T): T {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) {
                    throw ApiException(it.code, "Richiesta a ${url.encodedPath} fallita (HTTP ${it.code})")
                }
                val body = it.body?.string().orEmpty()
                try {
                    transform(JSONObject(body))
                } catch (e: JSONException) {
                    // Conservo la causa originale: è l'unico indizio su quale campo del contratto non
                    // corrisponde a ciò che il backend invia davvero.
                    throw MalformedResponseException("Risposta non valida da ${url.encodedPath}", e)
                }
            }
        }
    }
}
