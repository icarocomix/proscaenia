package com.icarocomix.proscaenia.data.remote

import org.json.JSONArray
import org.json.JSONObject

/*
 * Ho scelto org.json (incluso nella piattaforma Android) invece di una libreria di serializzazione
 * perché i payload sono piccoli e il contratto è ancora da allineare al backend reale: una mappatura
 * esplicita campo per campo rende evidente cosa leggo e mi permette di tollerare campi mancanti
 * senza annotazioni o plugin del compilatore. Queste estensioni eliminano le ambiguità di org.json,
 * che per esempio restituisce la stringa "null" o una stringa vuota dove io voglio un valore assente.
 */

internal fun JSONObject.stringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

internal fun JSONObject.longOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = optLong(name, Long.MIN_VALUE)
    return value.takeIf { it != Long.MIN_VALUE && it >= 0 }
}

internal fun JSONObject.arrayOrEmpty(name: String): JSONArray = optJSONArray(name) ?: JSONArray()

internal inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T?): List<T> {
    val result = ArrayList<T>(length())
    for (index in 0 until length()) {
        val element = optJSONObject(index) ?: continue
        transform(element)?.let(result::add)
    }
    return result
}
