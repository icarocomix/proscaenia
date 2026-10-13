package com.icarocomix.proscaenia.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.icarocomix.proscaenia.domain.policy.SpeedPolicy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Preferenze di riproduzione che sopravvivono tra le sessioni: "Amplifica voce" e velocità.
 *
 * Ho preferito SharedPreferences a DataStore perché il servizio di riproduzione deve leggere questi
 * valori in modo sincrono al momento di costruire il player (prima che arrivi qualunque comando) e
 * perché si tratta di due soli valori primitivi. Espongo comunque un Flow per "Amplifica voce": è il
 * canale con cui UI e servizio restano allineati senza parlarsi direttamente, perché la UI scrive la
 * preferenza e il servizio, che la osserva, applica il cambio al processore audio al volo.
 */
class PlaybackPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var voiceBoostEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_BOOST, true)
        set(value) = prefs.edit { putBoolean(KEY_VOICE_BOOST, value) }

    var playbackSpeed: Float
        get() = SpeedPolicy.normalize(prefs.getFloat(KEY_SPEED, SpeedPolicy.DEFAULT_SPEED))
        set(value) = prefs.edit { putFloat(KEY_SPEED, SpeedPolicy.normalize(value)) }

    /**
     * Il listener è tenuto in vita dal callbackFlow stesso: è importante perché SharedPreferences
     * conserva i listener solo con riferimenti deboli e uno creato "al volo" verrebbe raccolto dal GC.
     */
    val voiceBoostChanges: Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_VOICE_BOOST) trySend(voiceBoostEnabled)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(voiceBoostEnabled)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    private companion object {
        const val FILE_NAME = "playback_preferences"
        const val KEY_VOICE_BOOST = "voice_boost_enabled"
        const val KEY_SPEED = "playback_speed"
    }
}
