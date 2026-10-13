package com.icarocomix.proscaenia.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ponte tra una [Call] di OkHttp e le coroutine.
 *
 * Accodo la chiamata invece di eseguirla in modo bloccante perché così la cancellazione della
 * coroutine (per esempio quando l'utente lascia una schermata) si traduce davvero in `Call.cancel()`
 * e la connessione viene chiusa subito, invece di lasciare un thread bloccato fino al timeout.
 * La risposta restituita va chiusa dal chiamante, tipicamente con `use`.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // Se la coroutine è stata cancellata mentre la risposta arrivava, chiudo io il corpo
                // per non lasciare la connessione occupata nel pool.
                continuation.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
}
