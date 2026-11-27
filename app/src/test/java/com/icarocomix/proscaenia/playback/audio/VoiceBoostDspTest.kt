package com.icarocomix.proscaenia.playback.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * I test dei singoli stadi escludono la riduzione del rumore: usano toni stazionari che, dopo la
 * prima finestra di stima, il riduttore tratterebbe giustamente come rumore di fondo. La riduzione
 * ha i propri test in [NoiseReducerTest].
 */
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
        val dsp = VoiceBoostDsp(sampleRate, channels, noiseReduction = false)
        val frames = 96_000
        val buffer = stereoSine(frequencyHz = 30.0, amplitude = 0.3, frames = frames)
        val inputRms = rms(buffer, fromFrame = frames / 2)

        dsp.process(buffer, frames, enabled = true)

        // Misuro solo la seconda metà, a transitorio esaurito: a 30 Hz passa-alto a 120 Hz e scaffale
        // basso insieme attenuano di circa 56 dB, e a quel livello il livellatore è fermo sotto la
        // soglia di silenzio, quindi non può recuperare il ronzio.
        val outputRms = rms(buffer, fromFrame = frames / 2)
        assertTrue("rapporto ${outputRms / inputRms}", outputRms / inputRms < 0.01)
    }

    @Test
    fun `le consonanti vengono esaltate rispetto al fondo grave`() {
        val dsp = VoiceBoostDsp(sampleRate, channels, noiseReduction = false)
        val frames = 96_000
        // Due toni di pari ampiezza: 300 Hz (zona del rimbombo) e 3 kHz (zona delle consonanti). Il
        // guadagno di livellatore e compressore è comune a entrambi, quindi il loro rapporto misura
        // la sola equalizzazione, attesa intorno a +13 dB.
        val buffer = FloatArray(frames * channels)
        for (frame in 0 until frames) {
            val t = frame.toDouble() / sampleRate
            val value = (0.02 * sin(2 * PI * 300.0 * t) + 0.02 * sin(2 * PI * 3_000.0 * t)).toFloat()
            for (channel in 0 until channels) buffer[frame * channels + channel] = value
        }

        dsp.process(buffer, frames, enabled = true)

        val tiltDb = 20 * log10(toneAmplitude(buffer, 3_000.0, frames / 2) / toneAmplitude(buffer, 300.0, frames / 2))
        assertTrue("inclinazione $tiltDb dB", tiltDb in 11.0..15.0)
    }

    @Test
    fun `il livellatore alza una battuta debole fino al massimo consentito`() {
        val dsp = VoiceBoostDsp(sampleRate, channels, noiseReduction = false)
        val frames = sampleRate * 8
        // −43 dBFS RMS: servirebbero 26 dB per arrivare al bersaglio, il livellatore si ferma a +12.
        val buffer = stereoSine(frequencyHz = 1_000.0, amplitude = 0.01, frames = frames)
        val inputRms = rms(buffer, fromFrame = frames - sampleRate)

        dsp.process(buffer, frames, enabled = true)

        val gainDb = 20 * log10(rms(buffer, fromFrame = frames - sampleRate) / inputRms)
        assertTrue("guadagno $gainDb dB", gainDb in 10.5..12.5)
    }

    @Test
    fun `il livellatore non alza il silenzio sotto la soglia`() {
        val dsp = VoiceBoostDsp(sampleRate, channels, noiseReduction = false)
        val frames = sampleRate * 4
        // −69 dBFS RMS: rumore di fondo di una pausa, che non deve essere portato al livello della voce.
        val buffer = stereoSine(frequencyHz = 1_000.0, amplitude = 0.0005, frames = frames)
        val inputRms = rms(buffer, fromFrame = frames / 2)

        dsp.process(buffer, frames, enabled = true)

        val gainDb = 20 * log10(rms(buffer, fromFrame = frames / 2) / inputRms)
        assertTrue("guadagno $gainDb dB", gainDb in -1.5..1.0)
    }

    @Test
    fun `i picchi restano sotto il tetto del limitatore`() {
        val dsp = VoiceBoostDsp(sampleRate, channels)
        val frames = sampleRate * 2
        val buffer = stereoSine(frequencyHz = 3_000.0, amplitude = 0.9, frames = frames)

        dsp.process(buffer, frames, enabled = true)

        // Tetto a −2 dBFS (0,794): a dissolvenza conclusa nessun campione deve superarlo.
        var peak = 0f
        for (index in (frames / 2) * channels until buffer.size) peak = maxOf(peak, abs(buffer[index]))
        assertTrue("picco $peak", peak <= 0.795f)
    }

    @Test
    fun `in stereo la componente laterale viene attenuata di 6 dB rispetto al centro`() {
        val frames = sampleRate * 2
        // Livello sotto la soglia di silenzio: il livellatore resta a guadagno unitario e i due casi
        // attraversano la stessa catena, così il rapporto misura solo il trattamento mid/side.
        val center = stereoSine(frequencyHz = 1_000.0, amplitude = 0.0005, frames = frames)
        val sides = center.copyOf()
        for (frame in 0 until frames) sides[frame * channels + 1] = -sides[frame * channels + 1]

        VoiceBoostDsp(sampleRate, channels, noiseReduction = false).process(center, frames, enabled = true)
        VoiceBoostDsp(sampleRate, channels, noiseReduction = false).process(sides, frames, enabled = true)

        val ratio = rms(sides, fromFrame = frames / 2) / rms(center, fromFrame = frames / 2)
        assertEquals(VoiceBoostDsp.SIDE_GAIN, ratio, 0.02)
    }

    @Test
    fun `un flusso mono attraversa la catena senza errori`() {
        val dsp = VoiceBoostDsp(sampleRate, 1)
        val buffer = FloatArray(sampleRate) { (0.1 * sin(2 * PI * 1_000.0 * it / sampleRate)).toFloat() }

        dsp.process(buffer, buffer.size, enabled = true)

        assertTrue(buffer.all { it.isFinite() && abs(it) <= 1f })
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

    /** Ampiezza di un tono nel canale sinistro dal frame [fromFrame], per proiezione su seno e coseno. */
    private fun toneAmplitude(buffer: FloatArray, frequencyHz: Double, fromFrame: Int): Double {
        var re = 0.0
        var im = 0.0
        val total = buffer.size / channels
        for (frame in fromFrame until total) {
            val phase = 2 * PI * frequencyHz * frame / sampleRate
            re += buffer[frame * channels] * cos(phase)
            im += buffer[frame * channels] * sin(phase)
        }
        return 2 * sqrt(re * re + im * im) / (total - fromFrame)
    }

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
