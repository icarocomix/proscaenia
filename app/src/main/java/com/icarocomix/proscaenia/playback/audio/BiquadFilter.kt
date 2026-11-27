package com.icarocomix.proscaenia.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filtro biquad del secondo ordine con stato indipendente per ogni canale.
 *
 * Uso la Forma Diretta II trasposta in doppia precisione: rispetto alla Forma Diretta I richiede
 * solo due variabili di stato per canale ed è numericamente più robusta, cosa che conta per il
 * passa-alto a 100 Hz, i cui poli a 44,1/48 kHz sono molto vicini al cerchio unitario e
 * accumulerebbero errori di arrotondamento in singola precisione. I coefficienti sono già
 * normalizzati su a0 e seguono l'"Audio EQ Cookbook" di Robert Bristow-Johnson.
 */
class BiquadFilter private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
    channelCount: Int,
) {

    private val z1 = DoubleArray(channelCount)
    private val z2 = DoubleArray(channelCount)

    fun process(input: Double, channel: Int): Double {
        val output = b0 * input + z1[channel]
        z1[channel] = b1 * input - a1 * output + z2[channel]
        z2[channel] = b2 * input - a2 * output
        return output
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    companion object {

        /**
         * Q delle due sezioni che, in cascata, formano un Butterworth del 4° ordine: sono i valori
         * 1/(2·cos(π/8)) e 1/(2·cos(3π/8)), che danno una risposta massimamente piatta in banda
         * passante e una pendenza complessiva di 24 dB/ottava.
         */
        val BUTTERWORTH_4TH_ORDER_Q = doubleArrayOf(0.5411961001461970, 1.3065629648763766)

        fun highPass(sampleRate: Int, frequencyHz: Double, q: Double, channelCount: Int): BiquadFilter {
            val w0 = 2 * PI * safeFrequency(frequencyHz, sampleRate) / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cosW0 = cos(w0)
            val a0 = 1 + alpha
            return BiquadFilter(
                b0 = (1 + cosW0) / 2 / a0,
                b1 = -(1 + cosW0) / a0,
                b2 = (1 + cosW0) / 2 / a0,
                a1 = -2 * cosW0 / a0,
                a2 = (1 - alpha) / a0,
                channelCount = channelCount,
            )
        }

        fun peaking(sampleRate: Int, frequencyHz: Double, q: Double, gainDb: Double, channelCount: Int): BiquadFilter {
            val w0 = 2 * PI * safeFrequency(frequencyHz, sampleRate) / sampleRate
            val amplitude = sqrt(10.0.pow(gainDb / 20.0))
            val alpha = sin(w0) / (2 * q)
            val cosW0 = cos(w0)
            val a0 = 1 + alpha / amplitude
            return BiquadFilter(
                b0 = (1 + alpha * amplitude) / a0,
                b1 = -2 * cosW0 / a0,
                b2 = (1 - alpha * amplitude) / a0,
                a1 = -2 * cosW0 / a0,
                a2 = (1 - alpha / amplitude) / a0,
                channelCount = channelCount,
            )
        }

        /** Q che dà allo scaffale la transizione più dolce senza sovraelongazioni (pendenza S = 1). */
        const val SHELF_Q = 0.7071067811865476

        /** Scaffale basso: modifica di [gainDb] tutto ciò che sta sotto [frequencyHz]. */
        fun lowShelf(sampleRate: Int, frequencyHz: Double, q: Double, gainDb: Double, channelCount: Int): BiquadFilter =
            shelf(sampleRate, frequencyHz, q, gainDb, channelCount, high = false)

        /** Scaffale alto: modifica di [gainDb] tutto ciò che sta sopra [frequencyHz]. */
        fun highShelf(sampleRate: Int, frequencyHz: Double, q: Double, gainDb: Double, channelCount: Int): BiquadFilter =
            shelf(sampleRate, frequencyHz, q, gainDb, channelCount, high = true)

        private fun shelf(
            sampleRate: Int,
            frequencyHz: Double,
            q: Double,
            gainDb: Double,
            channelCount: Int,
            high: Boolean,
        ): BiquadFilter {
            val w0 = 2 * PI * safeFrequency(frequencyHz, sampleRate) / sampleRate
            val amplitude = sqrt(10.0.pow(gainDb / 20.0))
            val alpha = sin(w0) / (2 * q)
            val cosW0 = cos(w0)
            val twoSqrtAAlpha = 2 * sqrt(amplitude) * alpha
            // Le due varianti del Cookbook differiscono solo per il segno dei termini in (A-1)·cos(w0):
            // le scrivo per esteso per restare confrontabili riga per riga con le formule di riferimento.
            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            if (high) {
                b0 = amplitude * ((amplitude + 1) + (amplitude - 1) * cosW0 + twoSqrtAAlpha)
                b1 = -2 * amplitude * ((amplitude - 1) + (amplitude + 1) * cosW0)
                b2 = amplitude * ((amplitude + 1) + (amplitude - 1) * cosW0 - twoSqrtAAlpha)
                a0 = (amplitude + 1) - (amplitude - 1) * cosW0 + twoSqrtAAlpha
                a1 = 2 * ((amplitude - 1) - (amplitude + 1) * cosW0)
                a2 = (amplitude + 1) - (amplitude - 1) * cosW0 - twoSqrtAAlpha
            } else {
                b0 = amplitude * ((amplitude + 1) - (amplitude - 1) * cosW0 + twoSqrtAAlpha)
                b1 = 2 * amplitude * ((amplitude - 1) - (amplitude + 1) * cosW0)
                b2 = amplitude * ((amplitude + 1) - (amplitude - 1) * cosW0 - twoSqrtAAlpha)
                a0 = (amplitude + 1) + (amplitude - 1) * cosW0 + twoSqrtAAlpha
                a1 = -2 * ((amplitude - 1) + (amplitude + 1) * cosW0)
                a2 = (amplitude + 1) + (amplitude - 1) * cosW0 - twoSqrtAAlpha
            }
            return BiquadFilter(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0, channelCount)
        }

        /**
         * Limito la frequenza sotto Nyquist: con flussi a frequenza di campionamento molto bassa (per
         * esempio 8 kHz) i 3 kHz del secondo filtro sarebbero troppo vicini al limite e il filtro
         * diventerebbe instabile.
         */
        private fun safeFrequency(frequencyHz: Double, sampleRate: Int): Double =
            frequencyHz.coerceAtMost(sampleRate * 0.45)
    }
}
