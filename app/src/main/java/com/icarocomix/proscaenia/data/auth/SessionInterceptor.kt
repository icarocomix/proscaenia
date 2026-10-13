package com.icarocomix.proscaenia.data.auth

import com.icarocomix.proscaenia.core.logging.AppLog
import com.icarocomix.proscaenia.data.remote.ProscaeniaEndpoints
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Interceptor applicativo che rende l'autenticazione invisibile al resto dell'app.
 *
 * Per ogni richiesta verso le API che richiedono sessione mi assicuro che esista un token (al primo
 * utilizzo lo riprendo da quello salvato, o eseguo un login con le credenziali) e lo inietto come
 * header Authorization. Se il backend risponde 401 considero il token scaduto: chiudo la risposta,
 * chiedo il rinnovo al [SessionManager] e ripeto la richiesta una sola volta, così un rifiuto
 * definitivo (per esempio un contenuto non incluso nell'abbonamento) non genera un ciclo infinito.
 *
 * Ripetere `proceed` è lecito in un interceptor applicativo, ed è il motivo per cui questo interceptor
 * è registrato con `addInterceptor` e non come interceptor di rete.
 */
class SessionInterceptor(
    private val endpoints: ProscaeniaEndpoints,
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!endpoints.requiresSession(original.url)) return chain.proceed(original)

        val generation = sessionManager.ensureSession()
        val firstAttempt = chain.proceed(original.withBearer())
        if (firstAttempt.code != UNAUTHORIZED) return firstAttempt

        firstAttempt.close()
        AppLog.w(TAG, "Token rifiutato (HTTP 401) su ${original.url.encodedPath}: rinnovo la sessione")
        sessionManager.renewSession(generation)
        return chain.proceed(original.withBearer())
    }

    private fun Request.withBearer(): Request {
        val token = sessionManager.bearerToken ?: return this
        return newBuilder().header(HEADER_AUTHORIZATION, "Bearer $token").build()
    }

    private companion object {
        const val TAG = "SessionInterceptor"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val UNAUTHORIZED = 401
    }
}
