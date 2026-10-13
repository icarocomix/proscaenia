// La deprecazione di security-crypto è nota e accettata (vedi il commento della classe): la
// sopprimo a livello di file perché riguarda anche gli import, non solo il corpo della classe.
@file:Suppress("DEPRECATION")

package com.icarocomix.proscaenia.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.icarocomix.proscaenia.core.logging.AppLog
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Custodia cifrata delle credenziali sul dispositivo.
 *
 * Uso EncryptedSharedPreferences con una chiave AES-256-GCM generata e conservata nell'Android
 * Keystore: sia i nomi delle chiavi sia i valori finiscono su disco cifrati, e la chiave non è mai
 * estraibile dal dispositivo. La libreria è marcata come deprecata da Google ma resta funzionante e
 * supportata dalle versioni di Android coperte dall'app; l'ho scelta perché è esattamente il
 * meccanismo richiesto e non introduce dipendenze crittografiche scritte a mano.
 *
 * Apro le preferenze in modo pigro perché l'inizializzazione del Keystore costa qualche decina di
 * millisecondi e voglio pagarla solo quando qualcuno ha davvero bisogno delle credenziali.
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openEncryptedPrefs() }

    fun hasCredentials(): Boolean = read() != null

    fun read(): Credentials? {
        val username = prefs.getString(KEY_USERNAME, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return Credentials(username, password)
    }

    /**
     * Salvo in modo sincrono con `commit` e verifico l'esito: il salvataggio avviene una sola volta,
     * al primo avvio, e se fallisse silenziosamente l'utente si troverebbe di nuovo il login al
     * riavvio senza capire il perché. Il chiamante esegue questo metodo fuori dal thread principale.
     */
    @Throws(IOException::class)
    fun save(credentials: Credentials) {
        val written = prefs.edit()
            .putString(KEY_USERNAME, credentials.username)
            .putString(KEY_PASSWORD, credentials.password)
            .commit()
        if (!written) throw IOException("Impossibile salvare le credenziali sul dispositivo")
    }

    /**
     * Il token di accesso della piattaforma dura circa un anno: lo conservo cifrato accanto alle
     * credenziali così l'app non rifà il login a ogni avvio. Oltre a risparmiare una richiesta, evita
     * che il backend registri ripetutamente lo stesso dispositivo. Se il token viene rifiutato,
     * il SessionManager lo sostituisce con un nuovo login.
     */
    fun readSessionToken(): String? = prefs.getString(KEY_SESSION_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveSessionToken(token: String?) {
        prefs.edit().apply {
            if (token == null) remove(KEY_SESSION_TOKEN) else putString(KEY_SESSION_TOKEN, token)
        }.apply()
    }

    private fun openEncryptedPrefs(): SharedPreferences =
        try {
            createEncryptedPrefs()
        } catch (e: GeneralSecurityException) {
            recoverFromCorruptedStore(e)
        } catch (e: IOException) {
            recoverFromCorruptedStore(e)
        }

    /**
     * Gestisco il caso, documentato e non raro, in cui il file cifrato non sia più decifrabile: succede
     * quando la chiave del Keystore viene invalidata (ripristino del dispositivo, cambio del blocco
     * schermo su alcuni produttori). Il contenuto è irrecuperabile per definizione, quindi elimino il
     * solo file delle credenziali e lo ricreo vuoto: l'app mostrerà di nuovo il login invece di
     * andare in crash a ogni avvio.
     */
    private fun recoverFromCorruptedStore(cause: Exception): SharedPreferences {
        AppLog.w(TAG, "Archivio credenziali illeggibile, lo ricreo vuoto", cause)
        appContext.deleteSharedPreferences(FILE_NAME)
        return createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val FILE_NAME = "proscaenia_secure_store"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_SESSION_TOKEN = "session_token"
    }
}
