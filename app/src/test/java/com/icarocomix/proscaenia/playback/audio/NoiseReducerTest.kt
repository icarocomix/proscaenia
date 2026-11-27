package com.icarocomix.proscaenia.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class NoiseReducerTest {

    private val sampleRate = 48_000
    private val delay = NoiseReducer.FRAME_SIZE

    @Test
    fun `la FFT andata e ritorno restituisce il segnale originale`() {
        val fft = Fft(64)
        val random = Random(3)
        val original = DoubleArray(64) { random.nextGaussian() }
        val re = original.copyOf()
        val im = DoubleArray(64)

        fft.transform(re, im, inverse = false)
        fft.transform(re, im, inverse = true)

        for (index in original.indices) {
            assertEquals(original[index], re[index], 1e-12)
            assertEquals(0.0, im[index], 1e-12)
        }
    }

    @Test
    fun `la FFT colloca un coseno nel bin atteso`() {
        val fft = Fft(64)
        val re = DoubleArray(64) { kotlin.math.cos(2 * PI * 5 * it / 64) }
        val im = DoubleArray(64)

        fft.transform(re, im, inverse = false)

        // Un coseno di ampiezza 1 sul bin 5 vale N/2 nei bin 5 e N−5 e zero altrove.
        assertEquals(32.0, re[5], 1e-9)
        assertEquals(32.0, re[59], 1e-9)
        assertEquals(0.0, re[6], 1e-9)
    }

    @Test
    fun `finché la stima non è pronta il segnale passa intatto con il solo ritardo del blocco`() {
        val reducer = NoiseReducer(sampleRate, 1)
        val random = Random(7)
        val input = DoubleArray(sampleRate) { 0.1 * random.nextGaussian() }
        val output = process(reducer, input)

        // In un secondo la finestra di stima da 1,5 s non è ancora completa: il guadagno è unitario
        // e la finestra radice di Hann al 50% ricostruisce esattamente l'ingresso ritardato.
        for (index in delay until input.size) assertEquals(input[index - delay], output[index], 1e-9)
    }

    @Test
    fun `abbassa il fondo stazionario nelle pause e conserva le battute`() {
        val reducer = NoiseReducer(sampleRate, 1)
        val random = Random(11)
        val seconds = 8
        val noise = DoubleArray(sampleRate * seconds) { 0.01 * random.nextGaussian() }
        // Parlato simulato: raffiche di 200 ms a 1 kHz alternate a 200 ms di pausa, sopra il fondo.
        val input = DoubleArray(noise.size) { index ->
            val burst = (index / (sampleRate / 5)) % 2 == 0
            noise[index] + if (burst) 0.2 * sin(2 * PI * 1_000.0 * index / sampleRate) else 0.0
        }
        val output = process(reducer, input)

        // Misuro negli ultimi 3 s, a stima assestata, la parte centrale di ogni pausa e di ogni
        // raffica (lontano dai bordi, dove il blocco da 512 campioni mescola le due situazioni).
        var pauseIn = 0.0
        var pauseOut = 0.0
        var burstIn = 0.0
        var burstOut = 0.0
        val segment = sampleRate / 5
        for (start in sampleRate * 5 until input.size - segment step segment) {
            val burst = (start / segment) % 2 == 0
            for (index in start + segment / 4 until start + 3 * segment / 4) {
                val source = input[index]
                val result = output[index + delay].let { it * it }
                if (burst) { burstIn += source * source; burstOut += result } else { pauseIn += source * source; pauseOut += result }
            }
        }
        val pauseDb = 10 * log10(pauseOut / pauseIn)
        val burstDb = 10 * log10(burstOut / burstIn)
        assertTrue("attenuazione nelle pause $pauseDb dB", pauseDb < -7.0)
        assertTrue("variazione sulle battute $burstDb dB", burstDb > -2.0)
    }

    @Test
    fun `si adatta da solo quando il fondo cresce`() {
        val reducer = NoiseReducer(sampleRate, 1)
        val random = Random(13)
        // Fondo debole per 4 s, poi dieci volte più forte (+20 dB) per 6 s: per esempio la sala
        // che si riempie o un impianto di ventilazione che parte.
        val input = DoubleArray(sampleRate * 10) { index ->
            (if (index < sampleRate * 4) 0.003 else 0.03) * random.nextGaussian()
        }
        val output = process(reducer, input)

        val last = sampleRate * 9
        val ratioDb = 10 * log10(energy(output, last + delay, input.size) / energy(input, last, input.size - delay))
        assertTrue("attenuazione a regime $ratioDb dB", ratioDb < -7.0)
        assertFalse("nessun parlato nel solo rumore", reducer.speechPresent)
    }

    @Test
    fun `in stereo il guadagno è comune ai due canali`() {
        val reducer = NoiseReducer(sampleRate, 2)
        val random = Random(17)
        val inFrame = DoubleArray(2)
        val outFrame = DoubleArray(2)
        // Stesso segnale su entrambi i canali: con guadagno comune le uscite devono coincidere.
        for (index in 0 until sampleRate * 3) {
            val value = 0.02 * random.nextGaussian()
            inFrame[0] = value
            inFrame[1] = value
            reducer.process(inFrame, outFrame)
            assertEquals(outFrame[0], outFrame[1], 1e-12)
        }
    }

    private fun process(reducer: NoiseReducer, input: DoubleArray): DoubleArray {
        val inFrame = DoubleArray(1)
        val outFrame = DoubleArray(1)
        return DoubleArray(input.size) { index ->
            inFrame[0] = input[index]
            reducer.process(inFrame, outFrame)
            outFrame[0]
        }
    }

    private fun energy(signal: DoubleArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (index in from until minOf(to, signal.size)) sum += signal[index] * signal[index]
        return sum
    }
}
