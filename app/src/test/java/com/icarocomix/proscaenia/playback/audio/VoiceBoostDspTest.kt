package com.icarocomix.proscaenia.playback.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceBoostDspTest {

    private val sampleRate = 48_000
    private val channels = 2

    @Test
    fun `da spento il segnale esce identico all'ingresso`() {
        val dsp = VoiceBoostDsp(sampleRate, channels)
        val input = stereoSine(frequencyHz = 440.0, amplitude = 0.5, frames = 4_800)
        val buffer = input.copyOf()

        assertTrue(dsp.isBypassed(enabled = false))
        dsp.process(buffer, frames(buffer), enabled = false)

        assertArrayEquals(input, buffer, 0f)
    }

    @Test
    fun `dopo lo spegnimento e la dissolvenza torna bit per bit identico`() {
        val dsp = VoiceBoostDsp(sampleRate, channels)
        val warmUp = stereoSine(1_000.0, 0.5, 9_600)
        dsp.process(warmUp, frames(warmUp), enabled = true)

        // Spengo: la dissolvenza dura 30 ms (1.440 frame a 48 kHz), quindi questo buffer la esaurisce.
        val fadeOut = stereoSine(1_000.0, 0.5, 4_800)
        dsp.process(fadeOut, frames(fadeOut), enabled = false)
        assertTrue(dsp.isBypassed(enabled = false))

        val input = stereoSine(1_000.0, 0.5, 4_800)
        val buffer = input.copyOf()
        dsp.process(buffer, frames(buffer), enabled = false)
        assertArrayEquals(input, buffer, 0f)
    }

    @Test
    fun `il limitatore impedisce di superare il fondo scala anche con un ingresso saturo`() {
        val dsp = VoiceBoostDsp(sampleRate, channels)
        val buffer = stereoSine(frequencyHz = 2_000.0, amplitude = 1.0, frames = 48_000)

        dsp.process(buffer, frames(buffer), enabled = true)

        // Ammetto il valore 1 esatto: durante la dissolvenza iniziale passa il campione originale a
        // fondo scala, e la curva tanh tende a 1 entro la precisione di un float. Ciò che conta è che
        // nessun campione superi il fondo scala.
        assertTrue("picco ${buffer.maxOf { abs(it) }}", buffer.all { abs(it) <= 1f })
    }

    @Test
    fun `il passa alto attenua drasticamente un ronzio a 30 Hz`() {
        val dsp = VoiceBoostDsp(sampleRate, channels)
        val frames = 96_000
        val buffer = stereoSine(frequencyHz = 30.0, amplitude = 0.3, frames = frames)
        val inputRms = rms(buffer, fromFrame = frames / 2)

        dsp.process(buffer, frames, enabled = true)

        // Misuro solo la seconda metà, a transitorio esaurito: a 30 Hz un Butterworth del 4° ordine
        // a 100 Hz attenua di circa 42 dB, ben oltre il guadagno di compensazione di 3 dB.
        val outputRms = rms(buffer, fromFrame = frames / 2)
        assertTrue("rapporto ${outputRms / inputRms}", outputRms / inputRms < 0.05)
    }

    @Test
    fun `la banda della voce viene esaltata rispetto all'originale`() {
        val dsp = VoiceBoostDsp(sampleRate, channels)
        val frames = 48_000
        // Livello basso, sotto la soglia del compressore, per osservare l'equalizzazione pura.
        val buffer = stereoSine(frequencyHz = 2_000.0, amplitude = 0.02, frames = frames)
        val inputRms = rms(buffer, fromFrame = frames / 2)

        dsp.process(buffer, frames, enabled = true)

        val gainDb = 20 * kotlin.math.log10(rms(buffer, fromFrame = frames / 2) / inputRms)
        assertTrue("guadagno $gainDb dB", gainDb in 5.0..10.0)
    }

    @Test
    fun `il limitatore morbido è trasparente sotto la soglia e continuo al raccordo`() {
        assertEquals(0.5, SoftLimiter.apply(0.5), 0.0)
        assertEquals(-0.8, SoftLimiter.apply(-0.8), 0.0)
        assertEquals(0.8, SoftLimiter.apply(0.800001), 1e-5)
        // Per eccedenze realistiche resto strettamente sotto il fondo scala; per ingressi enormi tanh
        // vale 1 entro la precisione del double, quindi verifico solo di non superarlo mai.
        assertTrue(SoftLimiter.apply(1.2) < 1.0)
        assertTrue(SoftLimiter.apply(5.0) <= 1.0)
        assertTrue(SoftLimiter.apply(-5.0) >= -1.0)
        assertTrue(SoftLimiter.apply(0.95) > SoftLimiter.apply(0.9))
    }

    private fun stereoSine(frequencyHz: Double, amplitude: Double, frames: Int): FloatArray {
        val samples = FloatArray(frames * channels)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2 * PI * frequencyHz * frame / sampleRate)).toFloat()
            for (channel in 0 until channels) samples[frame * channels + channel] = value
        }
        return samples
    }

    private fun frames(buffer: FloatArray) = buffer.size / channels

    private fun rms(buffer: FloatArray, fromFrame: Int): Double {
        var sum = 0.0
        var count = 0
        for (index in fromFrame * channels until buffer.size) {
            sum += buffer[index] * buffer[index]
            count++
        }
        return sqrt(sum / count)
    }
}
