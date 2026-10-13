package com.icarocomix.proscaenia.core.network

import com.icarocomix.proscaenia.data.remote.ProscaeniaEndpoints
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Aggiunge gli header che il backend di Proscaenia richiede e che il client web invia sempre.
 *
 * Li ho individuati confrontando le richieste del sito con quelle rifiutate dal server: senza
 * Current-Build il login risponde 411 "Current Build not found.", senza DeviceId e DeviceData
 * valorizzati risponde 401 anche con credenziali corrette. Origin e Referer li aggiungo a tutte le
 * richieste verso i domini della piattaforma (API, video, licenze), perché è ciò che un browser
 * invierebbe dalla pagina del sito; gli header di dispositivo e lingua solo alle API, dove servono.
 */
class PlatformHeadersInterceptor(
    private val endpoints: ProscaeniaEndpoints,
    private val deviceIdentity: DeviceIdentity,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host != endpoints.baseUrl.host && !host.endsWith(".${endpoints.baseUrl.host}")) {
            return chain.proceed(request)
        }
        val builder = request.newBuilder()
            .header("Origin", endpoints.siteOrigin)
            .header("Referer", "${endpoints.siteOrigin}/")
        if (endpoints.isApi(request.url)) {
            builder
                .header("Accept", "application/json")
                .header("Content-Language", CONTENT_LANGUAGE)
                .header("Current-Build", CURRENT_BUILD)
                .header("DeviceId", deviceIdentity.deviceId)
                .header("DeviceData", deviceIdentity.deviceData)
        }
        return chain.proceed(builder.build())
    }

    private companion object {
        const val CONTENT_LANGUAGE = "it"

        /** Valore impostato globalmente dal client web; il backend lo esige su ogni richiesta. */
        const val CURRENT_BUILD = "ios_1"
    }
}
