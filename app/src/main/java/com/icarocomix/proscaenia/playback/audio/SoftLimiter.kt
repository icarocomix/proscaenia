package com.icarocomix.proscaenia.playback.audio

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Limitatore morbido senza memoria.
 *
 * Fino a 0,8 il segnale passa invariato; oltre, comprimo l'eccedenza con una tangente iperbolica
 * scalata in modo che la curva sia continua e con pendenza 1 nel punto di raccordo (nessuno spigolo
 * che genererebbe armoniche udibili) e che tenda asintoticamente a 1 senza mai raggiungerlo. È
 * l'ultima difesa contro il clipping digitale: l'esaltazione della voce e la compensazione del
 * compressore aggiungono guadagno e, senza questo stadio, un picco improvviso supererebbe il fondo
 * scala.
 */
object SoftLimiter {

    const val KNEE = 0.8
    private const val HEADROOM = 1.0 - KNEE

    fun apply(sample: Double): Double {
        val magnitude = abs(sample)
        if (magnitude <= KNEE) return sample
        val limited = KNEE + HEADROOM * tanh((magnitude - KNEE) / HEADROOM)
        return if (sample < 0) -limited else limited
    }
}
