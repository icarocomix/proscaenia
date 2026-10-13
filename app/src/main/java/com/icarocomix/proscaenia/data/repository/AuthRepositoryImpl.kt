package com.icarocomix.proscaenia.data.repository

import com.icarocomix.proscaenia.core.network.AutomaticRetry
import com.icarocomix.proscaenia.data.auth.CredentialStore
import com.icarocomix.proscaenia.data.auth.Credentials
import com.icarocomix.proscaenia.data.auth.SessionManager
import com.icarocomix.proscaenia.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepositoryImpl(
    private val credentialStore: CredentialStore,
    private val sessionManager: SessionManager,
) : AuthRepository {

    override fun hasStoredCredentials(): Boolean = credentialStore.hasCredentials()

    /**
     * Eseguo prima un login reale e salvo le credenziali solo se la piattaforma le accetta: salvarle
     * in anticipo significherebbe non chiedere mai più all'utente una password magari digitata male,
     * dato che la schermata di login compare soltanto al primo avvio. Tolgo gli spazi accidentali
     * dallo username, mai dalla password, dove potrebbero essere intenzionali.
     *
     * Anche qui ripeto in automatico gli errori di rete e del server, ma non il rifiuto delle
     * credenziali: al primo avvio un rifiuto significa password errata e va mostrato subito.
     */
    override suspend fun signIn(username: String, password: String) {
        val credentials = Credentials(username.trim(), password)
        AutomaticRetry.run("Primo accesso", retryRejectedSignIn = false) {
            withContext(Dispatchers.IO) { sessionManager.signIn(credentials) }
        }
        withContext(Dispatchers.IO) { credentialStore.save(credentials) }
    }
}
