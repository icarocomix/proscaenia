package com.icarocomix.proscaenia.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Riduzione adattativa del rumore di fondo nel dominio della frequenza, in streaming.
 *
 * Divido il segnale in blocchi da [FRAME_SIZE] campioni sovrapposti al 50% (finestra radice di
 * Hann in analisi e sintesi, che con questa sovrapposizione ricostruisce esattamente l'originale
 * quando il guadagno è unitario) e per ogni bin di frequenza:
 *
 * 1. stimo il rumore con un "minimum statistics" semplificato (R. Martin, 2001): il minimo del
 *    periodogramma smussato su una finestra scorrevole di circa 1,5 s, divisa in [SUBWINDOWS]
 *    sotto-finestre per aggiornarla senza conservare tutta la storia. In 1,5 s ogni banda attraversa
 *    almeno una micro-pausa del parlato, quindi il minimo rappresenta il fondo (sala, ventilazione,
 *    brusio) e si adatta da solo quando questo cambia. Il minimo sottostima la media del rumore:
 *    lo correggo con [NOISE_BIAS], calibrato su rumore bianco stazionario. Ho scartato
 *    l'inseguimento continuo di Doblinger, più semplice, perché con attori che parlano quasi senza
 *    pause la stima risaliva fino alla voce e finiva per toglierne 6–8 dB;
 * 2. calcolo il guadagno di Wiener con stima "decision-directed" del rapporto segnale/rumore a
 *    priori (Ephraim e Malah), che lo rende stabile da un blocco all'altro e limita il "rumore
 *    musicale" tipico della sottrazione spettrale;
 * 3. impongo un pavimento di [FLOOR_DB]: il fondo viene abbassato, non cancellato, perché una
 *    riduzione più profonda dà alla voce un timbro "subacqueo".
 *
 * Il guadagno è unico per tutti i canali (calcolato sulla potenza media), così l'immagine stereo
 * non si sposta. Finché la prima finestra di stima non è completa (circa 1,5 s dall'avvio o da un
 * seek) lascio passare il segnale invariato: una stima parziale potrebbe scambiare per rumore il
 * minimo di una frase e attenuare la voce.
 *
 * Il blocco introduce un ritardo fisso di [FRAME_SIZE] campioni (circa 11 ms a 48 kHz), ben sotto
 * la soglia di percezione del sincronismo labiale. Espongo infine [speechPresent], vero quando
 * l'energia nella banda del parlato supera il rumore stimato di [SPEECH_THRESHOLD_DB]: serve al
 * livellatore per non alzare il fondo durante le pause.
 */
class NoiseReducer(sampleRate: Int, private val channelCount: Int) {

    private val fft = Fft(FRAME_SIZE)
    private val window = DoubleArray(FRAME_SIZE) { sqrt(0.5 * (1 - cos(2 * PI * it / FRAME_SIZE))) }
    private val frames = Array(channelCount) { DoubleArray(FRAME_SIZE) }
    private val overlap = Array(channelCount) { DoubleArray(FRAME_SIZE) }
    private val pending = Array(channelCount) { DoubleArray(HOP_SIZE) }
    private val re = Array(channelCount) { DoubleArray(FRAME_SIZE) }
    private val im = Array(channelCount) { DoubleArray(FRAME_SIZE) }

    private val power = DoubleArray(BINS)
    private val smoothed = DoubleArray(BINS)
    private val currentMinimum = DoubleArray(BINS)
    private val subwindowMinima = Array(SUBWINDOWS) { DoubleArray(BINS) }
    private val noise = DoubleArray(BINS)
    private val previousGain = DoubleArray(BINS)
    private val previousPower = DoubleArray(BINS)
    private val gain = DoubleArray(BINS)

    private val hopsPerSubwindow = max(1, (WINDOW_SECONDS * sampleRate / HOP_SIZE / SUBWINDOWS).roundToInt())
    private val speechFromBin = (SPEECH_LOW_HZ * FRAME_SIZE / sampleRate).toInt().coerceIn(1, BINS - 1)
    private val speechToBin = (SPEECH_HIGH_HZ * FRAME_SIZE / sampleRate).toInt().coerceIn(speechFromBin, BINS - 1)
    private val floorGain = 10.0.pow(FLOOR_DB / 20.0)
    private val speechRatio = 10.0.pow(SPEECH_THRESHOLD_DB / 10.0)

    private var position = 0
    private var smoothedReady = false
    private var hopsInSubwindow = 0
    private var filledSubwindows = 0
    private var nextSubwindow = 0

    /** Vero se nell'ultimo blocco il parlato emergeva chiaramente dal rumore stimato. */
    var speechPresent: Boolean = true
        private set

    init {
        reset()
    }

    /**
     * Ricevo un frame ([channelCount] campioni) e scrivo in [output] il frame elaborato di
     * [FRAME_SIZE] campioni prima.
     */
    fun process(input: DoubleArray, output: DoubleArray) {
        for (channel in 0 until channelCount) {
            output[channel] = pending[channel][position]
            frames[channel][FRAME_SIZE - HOP_SIZE + position] = input[channel]
        }
        position++
        if (position == HOP_SIZE) {
            processBlock()
            position = 0
        }
    }

    private fun processBlock() {
        // Analisi: spettro di ogni canale e potenza media tra i canali, su cui stimo il rumore.
        power.fill(0.0)
        for (channel in 0 until channelCount) {
            val real = re[channel]
            val imaginary = im[channel]
            val frame = frames[channel]
            for (n in 0 until FRAME_SIZE) {
                real[n] = frame[n] * window[n]
                imaginary[n] = 0.0
            }
            fft.transform(real, imaginary, inverse = false)
            for (bin in 0 until BINS) power[bin] += (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) / channelCount
        }

        updateNoiseEstimate()
        computeGain()

        // Sintesi: applico lo stesso guadagno a ogni canale, simmetrico sui bin negativi perché il
        // segnale ricostruito resti reale, e sommo con sovrapposizione.
        for (channel in 0 until channelCount) {
            val real = re[channel]
            val imaginary = im[channel]
            for (bin in 0 until BINS) {
                real[bin] *= gain[bin]
                imaginary[bin] *= gain[bin]
                if (bin in 1 until BINS - 1) {
                    real[FRAME_SIZE - bin] *= gain[bin]
                    imaginary[FRAME_SIZE - bin] *= gain[bin]
                }
            }
            fft.transform(real, imaginary, inverse = true)
            val accumulator = overlap[channel]
            for (n in 0 until FRAME_SIZE) accumulator[n] += real[n] * window[n]
            System.arraycopy(accumulator, 0, pending[channel], 0, HOP_SIZE)
            System.arraycopy(accumulator, HOP_SIZE, accumulator, 0, FRAME_SIZE - HOP_SIZE)
            accumulator.fill(0.0, FRAME_SIZE - HOP_SIZE, FRAME_SIZE)
            val frame = frames[channel]
            System.arraycopy(frame, HOP_SIZE, frame, 0, FRAME_SIZE - HOP_SIZE)
        }
    }

    private fun updateNoiseEstimate() {
        for (bin in 0 until BINS) {
            smoothed[bin] = if (smoothedReady) SMOOTHING * smoothed[bin] + (1 - SMOOTHING) * power[bin] else power[bin]
            currentMinimum[bin] = min(currentMinimum[bin], smoothed[bin])
        }
        smoothedReady = true
        hopsInSubwindow++
        if (hopsInSubwindow == hopsPerSubwindow) {
            // Chiudo la sotto-finestra: il suo minimo sostituisce quello più vecchio della finestra.
            System.arraycopy(currentMinimum, 0, subwindowMinima[nextSubwindow], 0, BINS)
            nextSubwindow = (nextSubwindow + 1) % SUBWINDOWS
            filledSubwindows = min(filledSubwindows + 1, SUBWINDOWS)
            currentMinimum.fill(Double.POSITIVE_INFINITY)
            hopsInSubwindow = 0
        }
        for (bin in 0 until BINS) {
            var minimum = currentMinimum[bin]
            for (index in 0 until filledSubwindows) minimum = min(minimum, subwindowMinima[index][bin])
            noise[bin] = minimum * NOISE_BIAS + TINY_POWER
        }
    }

    private fun computeGain() {
        if (filledSubwindows < SUBWINDOWS) {
            // Stima non ancora affidabile: passo il segnale com'è e lascio lavorare il livellatore.
            gain.fill(1.0)
            previousGain.fill(1.0)
            System.arraycopy(power, 0, previousPower, 0, BINS)
            speechPresent = true
            return
        }
        for (bin in 0 until BINS) {
            val posteriorSnr = power[bin] / noise[bin]
            val prioriSnr = DECISION_DIRECTED * previousGain[bin] * previousGain[bin] * previousPower[bin] / noise[bin] +
                (1 - DECISION_DIRECTED) * max(posteriorSnr - 1, 0.0)
            gain[bin] = max(prioriSnr / (1 + prioriSnr), floorGain)
            previousGain[bin] = gain[bin]
            previousPower[bin] = power[bin]
        }
        var speechPower = 0.0
        var noisePower = 0.0
        for (bin in speechFromBin..speechToBin) {
            speechPower += smoothed[bin]
            noisePower += noise[bin]
        }
        speechPresent = speechPower > noisePower * speechRatio
    }

    /** Dopo un seek riparto da zero: la stima del rumore precedente non riguarda più questo punto. */
    fun reset() {
        frames.forEach { it.fill(0.0) }
        overlap.forEach { it.fill(0.0) }
        pending.forEach { it.fill(0.0) }
        smoothed.fill(0.0)
        currentMinimum.fill(Double.POSITIVE_INFINITY)
        previousGain.fill(1.0)
        previousPower.fill(0.0)
        gain.fill(1.0)
        position = 0
        smoothedReady = false
        hopsInSubwindow = 0
        filledSubwindows = 0
        nextSubwindow = 0
        speechPresent = true
    }

    companion object {
        const val FRAME_SIZE = 512
        const val HOP_SIZE = FRAME_SIZE / 2
        private const val BINS = FRAME_SIZE / 2 + 1
        private const val SUBWINDOWS = 8
        private const val WINDOW_SECONDS = 1.5
        private const val SMOOTHING = 0.85
        private const val NOISE_BIAS = 2.3
        private const val DECISION_DIRECTED = 0.98
        const val FLOOR_DB = -12.0
        private const val SPEECH_THRESHOLD_DB = 6.0
        private const val SPEECH_LOW_HZ = 300.0
        private const val SPEECH_HIGH_HZ = 4_000.0
        private const val TINY_POWER = 1e-20
    }
}
