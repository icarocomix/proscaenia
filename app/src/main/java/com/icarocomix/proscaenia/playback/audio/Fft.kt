package com.icarocomix.proscaenia.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT complessa radix-2 iterativa, sul posto, per una dimensione fissa potenza di due.
 *
 * Precalcolo una volta sola la permutazione a bit invertiti e le radici dell'unità, così la
 * trasformata nel thread audio non alloca nulla e non chiama funzioni trigonometriche. La dimensione
 * usata da [NoiseReducer] (512 punti) è piccola: una FFT complessa sul segnale reale, con parte
 * immaginaria nulla, costa poco più di una trasformata reale dedicata ed è molto più semplice da
 * verificare.
 */
class Fft(private val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "La dimensione della FFT deve essere una potenza di due: $size" }
    }

    private val bitReversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (index in 0 until size) table[index] = Integer.reverse(index) ushr (Int.SIZE_BITS - bits)
    }
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }

    /** Trasformata diretta (segno −) se [inverse] è falso, inversa normalizzata per 1/N altrimenti. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (index in 0 until size) {
            val swap = bitReversed[index]
            if (swap > index) {
                val tr = re[index]; re[index] = re[swap]; re[swap] = tr
                val ti = im[index]; im[index] = im[swap]; im[swap] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                for (offset in 0 until half) {
                    val wr = cosTable[offset * step]
                    val wi = sign * sinTable[offset * step]
                    val a = start + offset
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += length
            }
            length *= 2
        }
        if (inverse) {
            val scale = 1.0 / size
            for (index in 0 until size) {
                re[index] *= scale
                im[index] *= scale
            }
        }
    }
}
