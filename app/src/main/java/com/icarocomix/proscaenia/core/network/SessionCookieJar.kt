package com.icarocomix.proscaenia.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Archivio dei cookie di sessione, tenuto esclusivamente in memoria.
 *
 * Non persisto i cookie su disco per scelta di sicurezza: le credenziali sono già custodite in forma
 * cifrata e una nuova sessione costa un solo login, che il SessionManager esegue in modo trasparente
 * quando serve. Lo stesso archivio è condiviso dal client delle API e da quello dei flussi
 * multimediali, così i cookie ottenuti al login accompagnano anche le richieste dei segmenti video
 * quando la CDN vive sullo stesso dominio della piattaforma.
 *
 * Tutti gli accessi passano da un unico lock perché OkHttp invoca il CookieJar da thread diversi
 * del proprio dispatcher, e la lista sottostante non è thread-safe.
 */
class SessionCookieJar(
    private val clock: () -> Long = System::currentTimeMillis,
) : CookieJar {

    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = clock()
        synchronized(lock) {
            for (incoming in cookies) {
                // Sostituisco un cookie con lo stesso nome, dominio e percorso perché è così che il
                // server lo aggiorna; un cookie già scaduto equivale a una cancellazione e quindi non
                // lo reinserisco.
                this.cookies.removeAll { it.sameIdentityAs(incoming) }
                if (incoming.expiresAt > now) this.cookies += incoming
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clock()
        synchronized(lock) {
            cookies.removeAll { it.expiresAt <= now }
            return cookies.filter { it.matches(url) }
        }
    }

    /** Svuoto l'archivio prima di ogni nuovo login, così nessun cookie di una sessione scaduta sopravvive. */
    fun clear() {
        synchronized(lock) { cookies.clear() }
    }

    private fun Cookie.sameIdentityAs(other: Cookie): Boolean =
        name == other.name && domain == other.domain && path == other.path
}
