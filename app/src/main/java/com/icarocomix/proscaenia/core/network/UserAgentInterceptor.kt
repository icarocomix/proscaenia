package com.icarocomix.proscaenia.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Applica lo stesso User-Agent a ogni richiesta che passa dal client OkHttp condiviso.
 *
 * Lo imposto con `header` e non con `addHeader` così sostituisco qualunque valore predefinito
 * (quello di OkHttp o di una libreria a monte) invece di affiancarlo: il backend deve vedere
 * sempre e solo il profilo descritto in [UserAgent].
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header(HEADER_USER_AGENT, userAgent)
                .build(),
        )

    private companion object {
        const val HEADER_USER_AGENT = "User-Agent"
    }
}
