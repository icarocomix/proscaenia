package com.icarocomix.proscaenia.domain.error

import java.io.IOException

/*
 * Ho fatto derivare tutte le eccezioni di dominio da IOException per una ragione pratica: alcune di
 * esse nascono dentro gli interceptor di OkHttp, e OkHttp propaga in modo pulito al chiamante solo
 * le IOException (un'eccezione non controllata lanciata da un interceptor in una chiamata asincrona
 * finirebbe invece sul thread del dispatcher, facendo terminare il processo). Così la gerarchia resta
 * unica e i livelli superiori possono distinguere i casi con un semplice `when` sul tipo.
 */

/** La piattaforma ha rifiutato username e password salvati o appena inseriti. */
class InvalidCredentialsException(
    message: String = "Credenziali rifiutate dalla piattaforma",
) : IOException(message)

/** Le credenziali sono valide ma l'abbonamento non è attivo (scaduto, sospeso o mai completato). */
class InactiveSubscriptionException : IOException("Abbonamento non attivo")

/**
 * Le credenziali sono corrette ma l'account ha già registrato il numero massimo di dispositivi
 * consentiti: il backend risponde 401 come per una password errata, distinguendo il caso solo nel
 * corpo ("MAX Device"). Va tenuto separato perché reinserire le credenziali non serve a nulla.
 */
class DeviceLimitException(
    message: String = "Numero massimo di dispositivi raggiunto",
) : IOException(message)

/** Una richiesta autenticata è partita prima che esistessero credenziali salvate sul dispositivo. */
class MissingCredentialsException : IOException("Nessuna credenziale salvata sul dispositivo")

/** Il backend ha risposto con uno stato HTTP non previsto per la richiesta effettuata. */
class ApiException(
    val statusCode: Int,
    message: String,
) : IOException(message)

/** La risposta è arrivata ma il suo contenuto non rispetta il contratto JSON atteso. */
class MalformedResponseException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
