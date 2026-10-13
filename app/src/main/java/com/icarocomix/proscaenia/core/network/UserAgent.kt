package com.icarocomix.proscaenia.core.network

import android.os.Build

/**
 * User-Agent unico dell'intero processo.
 *
 * Ho scelto di costruirlo una sola volta e di usarlo identico sia per le chiamate alle API sia per
 * il download dei flussi video: molte CDN legano gli URL firmati alla "firma" del client che li ha
 * richiesti, e un User-Agent diverso tra la richiesta del link e la lettura dei segmenti è una
 * causa tipica di risposte HTTP 403. Imito un Chrome mobile perché è il profilo che la piattaforma
 * web si aspetta, e inserisco il modello reale del dispositivo per restare coerente con ciò che il
 * sistema dichiarerebbe da sé.
 */
object UserAgent {

    /** Versione di Chrome dichiarata: la riuso anche in DeviceData per restare coerente con l'User-Agent. */
    const val CHROME_VERSION = "141.0.0.0"

    val value: String by lazy {
        val raw = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME_VERSION Mobile Safari/537.36"
        sanitize(raw)
    }

    /**
     * Filtro i caratteri non ASCII stampabili perché OkHttp rifiuta con IllegalArgumentException
     * un header che li contenga, e alcuni produttori usano nomi di modello con caratteri speciali.
     */
    internal fun sanitize(raw: String): String =
        raw.filter { it in ' '..'~' }.trim()
}
