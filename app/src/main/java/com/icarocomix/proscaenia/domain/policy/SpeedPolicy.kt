package com.icarocomix.proscaenia.domain.policy

import kotlin.math.roundToInt

/**
 * Regole sulla velocità di riproduzione.
 *
 * Le velocità ammesse vanno da 0,5x a 3,0x a passi di 0,25x. Normalizzo ogni valore in ingresso
 * (dalla UI, dalle preferenze o da un controller esterno come Android Auto o un'app di terze parti)
 * agganciandolo al passo più vicino, così il valore salvato è sempre uno di quelli mostrati in
 * interfaccia. Il Default Media Receiver del Chromecast non gestisce bene velocità superiori a 2x,
 * per cui durante il Cast applico un tetto senza però modificare la scelta salvata dall'utente, che
 * torna in vigore al rientro sul telefono.
 */
object SpeedPolicy {

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f
    const val STEP = 0.25f
    const val DEFAULT_SPEED = 1.0f
    const val MAX_CAST_SPEED = 2.0f

    val availableSpeeds: List<Float> =
        List(((MAX_SPEED - MIN_SPEED) / STEP).roundToInt() + 1) { index -> MIN_SPEED + index * STEP }

    fun normalize(speed: Float): Float {
        if (speed.isNaN()) return DEFAULT_SPEED
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val steps = ((clamped - MIN_SPEED) / STEP).roundToInt()
        return MIN_SPEED + steps * STEP
    }

    fun capForCast(speed: Float): Float = normalize(speed).coerceAtMost(MAX_CAST_SPEED)
}
