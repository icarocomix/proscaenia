package com.icarocomix.proscaenia.data.auth

import com.icarocomix.proscaenia.core.logging.AppLog
import com.icarocomix.proscaenia.domain.error.MissingCredentialsException
import java.io.IOException

/**
 * Custode della sessione con la piattaforma, basata su token Bearer.
 *
 * All'avvio riprendo il token salvato in forma cifrata, quindi nella maggior parte dei casi non serve
 * alcun login. Quando il backend rifiuta il token, l'interceptor chiede un rinnovo, che consiste in un
 * nuovo login con le credenziali salvate.
 *
 * Il problema che risolvo qui è la concorrenza: alla scadenza più richieste parallele ricevono un 401
 * quasi insieme, e senza coordinamento ognuna rifarebbe il login, con il rischio di invalidarsi a
 * vicenda e di far scattare protezioni lato server. Per questo associo a ogni token una "generazione":
 * chi ha ricevuto un rifiuto chiede il rinnovo indicando la generazione con cui aveva fatto la
 * richiesta, e se nel frattempo un altro thread ha già rinnovato (generazione cambiata) riparte con il
 * token nuovo senza un secondo login. Tutto il lavoro di login avviene sotto un unico lock e in modo
 * sincrono, perché viene invocato dall'interceptor di OkHttp su thread di rete.
 */
class SessionManager(
    private val credentialStore: CredentialStore,
    private val authApi: AuthApi,
    private val deviceId: String,
) {

    private val lock = Any()

    @Volatile
    private var generation = 0L

    @Volatile
    private var token: String? = null

    @Volatile
    private var restored = false

    /** Token corrente, da inviare come `Authorization: Bearer`; null finché non esiste una sessione. */
    val bearerToken: String? get() = token

    /** Garantisco un token prima di una richiesta autenticata e restituisco la sua generazione. */
    @Throws(IOException::class)
    fun ensureSession(): Long {
        if (token != null) return generation
        synchronized(lock) {
            if (token == null) {
                restoreLocked()
                if (token == null) loginLocked(storedCredentials())
            }
            return generation
        }
    }

    /** Rinnovo il token solo se nessun altro l'ha già fatto dopo la richiesta rifiutata. */
    @Throws(IOException::class)
    fun renewSession(rejectedGeneration: Long) {
        synchronized(lock) {
            if (token != null && generation != rejectedGeneration) return
            loginLocked(storedCredentials())
        }
    }

    /** Login esplicito con credenziali non ancora salvate: è il percorso della schermata di primo avvio. */
    @Throws(IOException::class)
    fun signIn(credentials: Credentials) {
        synchronized(lock) { loginLocked(credentials) }
    }

    /**
     * Chiudo la sessione e libero il posto di questo dispositivo sull'account (limite di 4).
     *
     * Lavoro sotto lo stesso lock del login: se l'utente rientra e avvia uno spettacolo mentre la
     * chiusura è in corso, il nuovo login attende e parte solo dopo, invece di essere annullato da una
     * rimozione arrivata in ritardo. Cancello sempre il token locale, anche se la rete fallisce:
     * restare con un posto occupato è innocuo, perché il prossimo login usa lo stesso DeviceId e
     * riprende lo stesso posto invece di occuparne uno nuovo. Le credenziali restano salvate, quindi
     * all'utente non viene chiesto nulla: il login successivo avviene da solo, alla prima riproduzione.
     */
    fun releaseSession() {
        synchronized(lock) {
            restoreLocked()
            val current = token ?: run {
                AppLog.i(TAG, "Uscita: nessuna sessione aperta, niente da liberare")
                return
            }
            try {
                authApi.releaseDevice(current, deviceId)
            } finally {
                token = null
                generation++
                credentialStore.saveSessionToken(null)
            }
        }
    }

    /**
     * Riprendo il token salvato una sola volta per processo. Lo leggo pigramente, sotto il lock,
     * perché aprire l'archivio cifrato costa qualche decina di millisecondi e non voglio pagarlo
     * sul thread principale all'avvio.
     */
    private fun restoreLocked() {
        if (restored) return
        restored = true
        credentialStore.readSessionToken()?.let { saved ->
            token = saved
            generation++
            AppLog.i(TAG, "Sessione ripresa dal token salvato")
        }
    }

    private fun loginLocked(credentials: Credentials) {
        // Invalido il token prima della chiamata: se il login fallisce non deve sopravvivere un token
        // già rifiutato dal server.
        restored = true
        token = null
        val result = authApi.login(credentials)
        token = result.accessToken
        generation++
        credentialStore.saveSessionToken(result.accessToken)
        // Ogni login occupa (o riprende) il posto di questo dispositivo sull'account: lo registro
        // perché è l'evento da confrontare con i rilasci all'uscita quando si indaga il limite.
        AppLog.i(TAG, "Login riuscito, nuova sessione aperta")
    }

    private companion object {
        const val TAG = "SessionManager"
    }

    private fun storedCredentials(): Credentials =
        credentialStore.read() ?: throw MissingCredentialsException()
}
