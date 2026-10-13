package com.icarocomix.proscaenia.data.auth

import com.icarocomix.proscaenia.core.logging.AppLog
import com.icarocomix.proscaenia.data.remote.ProscaeniaEndpoints
import com.icarocomix.proscaenia.data.remote.stringOrNull
import com.icarocomix.proscaenia.domain.error.ApiException
import com.icarocomix.proscaenia.domain.error.DeviceLimitException
import com.icarocomix.proscaenia.domain.error.InactiveSubscriptionException
import com.icarocomix.proscaenia.domain.error.InvalidCredentialsException
import com.icarocomix.proscaenia.domain.error.MalformedResponseException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Chiamata di login verso la piattaforma (POST auth/login).
 *
 * La eseguo con un client OkHttp dedicato, che condivide User-Agent e header di piattaforma con
 * quello delle API ma non ha l'interceptor di sessione: così il login non può innescare un altro
 * login. È sincrona perché viene invocata dentro l'interceptor di OkHttp, già su un thread di rete.
 *
 * Il contratto reale, verificato contro il servizio: corpo JSON con email, password e remember;
 * risposta JSON con access_token (Bearer, validità di circa un anno) e refresh_token. Considero
 * riuscito il login solo se il token c'è davvero: in passato una risposta HTML con stato 200 (un
 * redirect alla home del sito) veniva scambiata per un successo, e l'errore emergeva più tardi in
 * modo fuorviante sul catalogo.
 */
class AuthApi(
    private val client: OkHttpClient,
    private val endpoints: ProscaeniaEndpoints,
) {

    data class LoginResult(val accessToken: String)

    @Throws(IOException::class)
    fun login(credentials: Credentials): LoginResult {
        val payload = JSONObject()
            .put("email", credentials.username)
            .put("password", credentials.password)
            .put("remember", true)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoints.login)
            .post(payload)
            .build()

        client.newCall(request).execute().use { response ->
            val json = parseJson(response.body?.string().orEmpty())
            if (!response.isSuccessful) logRejection(response.code, json)
            // Il client web distingue l'abbonamento non attivo dal resto leggendo il primo elemento
            // di "errors": riproduco la stessa regola per dare all'utente il messaggio corretto.
            when (json?.optJSONArray("errors")?.optString(0)) {
                ERROR_INACTIVE_SUBSCRIPTION -> throw InactiveSubscriptionException()
                // Verificato contro il servizio: limite di dispositivi raggiunto, con stato 401 e
                // credenziali corrette. Lo intercetto prima del controllo sullo stato HTTP.
                ERROR_MAX_DEVICE -> throw DeviceLimitException(json.stringOrNull("message") ?: "Numero massimo di dispositivi raggiunto")
            }
            if (response.code == 401 || response.code == 403) throw InvalidCredentialsException()
            if (!response.isSuccessful) throw ApiException(response.code, "Login non riuscito (HTTP ${response.code})")
            if (json == null) throw MalformedResponseException("Risposta di login non in formato JSON")
            val token = json.stringOrNull("access_token")
                ?: throw MalformedResponseException("Risposta di login priva di access_token")
            return LoginResult(token)
        }
    }

    /**
     * Libero il posto occupato da questo dispositivo sull'account, nello stesso modo del sito:
     * rimuovo la registrazione del dispositivo (DELETE user/device/{deviceId}). Verificato sul
     * servizio reale: la rimozione riuscita invalida anche il token (un logout successivo risponde
     * 401 "Unauthenticated."), quindi il logout lo eseguo solo come ripiego se la rimozione fallisce,
     * per invalidare almeno il token. Ogni passo è "al meglio": registro l'esito e non propago errori,
     * perché avviene all'uscita dall'app e nessuno potrebbe gestirli.
     *
     * Uso il token in modo esplicito e il client senza interceptor di sessione: un 401 qui non deve
     * mai innescare un nuovo login, che occuperebbe di nuovo il posto appena liberato.
     */
    fun releaseDevice(token: String, deviceId: String) {
        val removed = executeQuietly("Rimozione del dispositivo", Request.Builder().url(endpoints.userDevice(deviceId)).delete(), token)
        if (removed in 200..299) {
            AppLog.i(TAG, "Posto dispositivo liberato all'uscita (HTTP $removed)")
            return
        }
        val loggedOut = executeQuietly("Logout", Request.Builder().url(endpoints.logout).get(), token)
        AppLog.w(TAG, "Posto dispositivo non liberato (rimozione=$removed), logout di ripiego=$loggedOut")
    }

    /** Eseguo una chiamata autenticata ignorando il corpo e restituisco lo stato HTTP (o -1 se la rete fallisce). */
    private fun executeQuietly(label: String, builder: Request.Builder, token: String): Int =
        try {
            client.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
                if (!response.isSuccessful) {
                    // Riporto solo il messaggio d'errore del server, come per il login rifiutato.
                    val message = parseJson(response.body?.string().orEmpty())?.stringOrNull("message")?.take(MAX_ERROR_CHARS)
                    AppLog.w(TAG, "$label rifiutato (HTTP ${response.code}) ${message.orEmpty()}")
                }
                response.code
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "$label non riuscito per un errore di rete", e)
            -1
        }

    /**
     * Registro nel file di log il motivo per cui il backend rifiuta il login: lo stesso stato 401 può
     * indicare credenziali errate, un dispositivo non accettato o un header mancante, e solo il
     * messaggio del server permette di distinguerli. Copio esclusivamente i campi descrittivi
     * dell'errore e i nomi dei campi presenti, mai il corpo intero: non deve finire nel file nulla
     * che somigli a un token o ai dati di accesso.
     */
    private fun logRejection(code: Int, json: JSONObject?) {
        val details = if (json == null) {
            "corpo non JSON"
        } else {
            val keys = json.keys().asSequence().toList().sorted()
            val messages = ERROR_FIELDS.mapNotNull { field ->
                json.opt(field)?.takeIf { it != JSONObject.NULL }?.let { "$field=${it.toString().take(MAX_ERROR_CHARS)}" }
            }
            "campi=$keys ${messages.joinToString(" ")}"
        }
        AppLog.w(TAG, "Login rifiutato (HTTP $code): $details")
    }

    private fun parseJson(body: String): JSONObject? {
        if (body.isBlank()) return null
        return try {
            JSONObject(body)
        } catch (_: JSONException) {
            null
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ERROR_INACTIVE_SUBSCRIPTION = "Not active subscription"
        const val ERROR_MAX_DEVICE = "MAX Device"
        const val TAG = "AuthApi"
        val ERROR_FIELDS = listOf("message", "error", "errors", "code", "status")
        const val MAX_ERROR_CHARS = 300
    }
}
