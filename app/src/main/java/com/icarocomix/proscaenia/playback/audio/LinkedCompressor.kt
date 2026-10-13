package com.icarocomix.proscaenia.playback.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Compressore a rilevazione di picco con canali collegati.
 *
 * Calcolo la riduzione di guadagno nel dominio logaritmico e la smusso con due costanti di tempo
 * distinte (attacco quando la riduzione deve crescere, rilascio quando deve diminuire), secondo lo
 * schema "smooth branching" descritto da Giannoulis, Massberg e Reiss: rispetto allo smussamento
 * dell'inviluppo lineare, così le costanti di attacco e rilascio corrispondono davvero ai tempi
 * dichiarati in millisecondi e il comportamento non dipende dal livello del segnale.
 *
 * Il rilevatore riceve un unico livello per frame, il massimo tra i canali, e restituisce un unico
 * guadagno da applicare a tutti: applicare la stessa riduzione su entrambi i canali è ciò che evita
 * lo "spostamento" dell'immagine stereo quando un attore parla da un lato del palco.
 */
class LinkedCompressor(
    sampleRate: Int,
    private val thresholdDb: Double = -20.0,
    ratio: Double = 3.0,
    attackSeconds: Double = 0.005,
    releaseSeconds: Double = 0.150,
    makeupGainDb: Double = 3.0,
) {

    private val slope = 1.0 - 1.0 / ratio
    private val attackCoefficient = exp(-1.0 / (attackSeconds * sampleRate))
    private val releaseCoefficient = exp(-1.0 / (releaseSeconds * sampleRate))
    private val makeupDb = makeupGainDb

    private var smoothedReductionDb = 0.0

    /** Restituisco il guadagno lineare da applicare al frame, compensazione inclusa. */
    fun gainFor(linkedPeak: Double): Double {
        val levelDb = if (linkedPeak > SILENCE_LINEAR) 20.0 * log10(linkedPeak) else SILENCE_DB
        val overshootDb = levelDb - thresholdDb
        val targetReductionDb = if (overshootDb > 0) overshootDb * slope else 0.0
        val coefficient = if (targetReductionDb > smoothedReductionDb) attackCoefficient else releaseCoefficient
        smoothedReductionDb = targetReductionDb + coefficient * (smoothedReductionDb - targetReductionDb)
        return 10.0.pow((makeupDb - smoothedReductionDb) / 20.0)
    }

    fun reset() {
        smoothedReductionDb = 0.0
    }

    private companion object {
        const val SILENCE_LINEAR = 1e-9
        const val SILENCE_DB = -180.0
    }
}
