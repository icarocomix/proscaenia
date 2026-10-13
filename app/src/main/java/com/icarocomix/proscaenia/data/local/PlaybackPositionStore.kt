package com.icarocomix.proscaenia.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.icarocomix.proscaenia.domain.model.SavedPosition

/**
 * Archivio delle posizioni di riproduzione per spettacolo.
 *
 * Il volume è minuscolo (due long per spettacolo visto) e la scrittura avviene ogni 10 secondi, a
 * ogni pausa e alla chiusura: SharedPreferences è adatto e non richiede uno schema. Normalmente
 * scrivo in modo asincrono con `apply`, che non blocca il thread principale; alla distruzione del
 * servizio invece passo a `commit`, perché il processo può essere terminato subito dopo e una
 * scrittura ancora in coda andrebbe persa proprio nel momento in cui conta di più.
 */
class PlaybackPositionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(showId: String): SavedPosition? {
        val position = prefs.getLong(positionKey(showId), -1L)
        if (position < 0) return null
        return SavedPosition(positionMs = position, durationMs = prefs.getLong(durationKey(showId), -1L))
    }

    fun save(showId: String, positionMs: Long, durationMs: Long, synchronous: Boolean) {
        prefs.edit(commit = synchronous) {
            putLong(positionKey(showId), positionMs.coerceAtLeast(0))
            putLong(durationKey(showId), durationMs)
        }
    }

    private fun positionKey(showId: String) = "position:$showId"

    private fun durationKey(showId: String) = "duration:$showId"

    private companion object {
        const val FILE_NAME = "playback_positions"
    }
}
