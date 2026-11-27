package com.icarocomix.proscaenia.playback.audio

import kotlin.math.abs
import kotlin.math.max

/**
 * Catena di elaborazione "Amplifica voce", indipendente da Android e da Media3 così da poterla
 * verificare con semplici test JVM.
 *
 * La taratura nasce dall'analisi di registrazioni reali di spettacoli dal vivo (vedi
 * `docs/audio-amplifica-voce.md`), che hanno mostrato tre difetti ricorrenti: un fondo grave
 * costante sotto i 400 Hz (sala, ventilazione) quasi allo stesso livello durante il parlato e nelle
 * pause; una risonanza "scatolata" intorno a 400–500 Hz; un crollo degli acuti sopra i 2–3 kHz,
 * proprio dove risiedono le consonanti. Lavoro su campioni float interlacciati in [-1, 1] e per ogni
 * frame applico, in quest'ordine:
 *
 * 1. solo per lo stereo, attenuazione di 6 dB della componente laterale (L−R)/2: la voce è quasi
 *    sempre al centro, mentre riverbero e pubblico sono decorrelati tra i canali e finiscono nei
 *    lati;
 * 2. riduzione adattativa del rumore di fondo ([NoiseReducer]), che stima da sola il fondo di sala
 *    e lo abbassa fino a 12 dB, e segnala al livellatore quando c'è parlato;
 * 3. passa-alto Butterworth del 4° ordine a 120 Hz, scaffale basso −8 dB a 250 Hz e campana −4 dB a
 *    450 Hz, che tolgono il fondo di sala e il rimbombo senza toccare le formanti della voce;
 * 4. campana +3 dB a 3 kHz e scaffale alto +6 dB da 2 kHz, che riportano le consonanti;
 * 5. [SpeechLeveler], che porta le battute deboli e quelle forti a un livello simile;
 * 6. compressore collegato morbido (−12 dBFS, 2,5:1) per le sole battute urlate;
 * 7. [PeakLimiter] a −2 dBFS e infine [SoftLimiter], ultima difesa contro il clipping.
 *
 * La riduzione del rumore ritarda il segnale elaborato di [NoiseReducer.FRAME_SIZE] campioni (circa
 * 11 ms): durante i 30 ms della dissolvenza originale ed elaborato sono quindi leggermente sfasati,
 * un breve effetto di pettine accettato in cambio di non ritardare l'audio quando l'effetto è
 * spento.
 *
 * Il passaggio tra segnale originale ed elaborato avviene con una dissolvenza incrociata lineare di
 * 30 ms, così accendere o spegnere l'effetto durante la riproduzione non produce click. Quando
 * l'effetto è spento e la dissolvenza è terminata il campione viene lasciato esattamente com'era:
 * il chiamante può riconoscere questo stato con [isBypassed] ed evitare del tutto la conversione.
 */
class VoiceBoostDsp(
    sampleRate: Int,
    private val channelCount: Int,
    noiseReduction: Boolean = true,
) {

    private val filters: Array<BiquadFilter> = arrayOf(
        BiquadFilter.highPass(sampleRate, HIGH_PASS_HZ, BiquadFilter.BUTTERWORTH_4TH_ORDER_Q[0], channelCount),
        BiquadFilter.highPass(sampleRate, HIGH_PASS_HZ, BiquadFilter.BUTTERWORTH_4TH_ORDER_Q[1], channelCount),
        BiquadFilter.lowShelf(sampleRate, LOW_SHELF_HZ, BiquadFilter.SHELF_Q, LOW_SHELF_GAIN_DB, channelCount),
        BiquadFilter.peaking(sampleRate, BOX_HZ, BOX_Q, BOX_GAIN_DB, channelCount),
        BiquadFilter.peaking(sampleRate, PRESENCE_HZ, PRESENCE_Q, PRESENCE_GAIN_DB, channelCount),
        BiquadFilter.highShelf(sampleRate, HIGH_SHELF_HZ, BiquadFilter.SHELF_Q, HIGH_SHELF_GAIN_DB, channelCount),
    )

    // Escludibile solo nei test, per verificare gli altri stadi con toni stazionari che il riduttore
    // tratterebbe giustamente come rumore.
    private val noiseReducer: NoiseReducer? = if (noiseReduction) NoiseReducer(sampleRate, channelCount) else null
    private val leveler = SpeechLeveler(sampleRate)
    private val compressor = LinkedCompressor(
        sampleRate,
        thresholdDb = COMPRESSOR_THRESHOLD_DB,
        ratio = COMPRESSOR_RATIO,
        attackSeconds = COMPRESSOR_ATTACK_SECONDS,
        releaseSeconds = COMPRESSOR_RELEASE_SECONDS,
        makeupGainDb = 0.0,
    )
    private val limiter = PeakLimiter(sampleRate)
    private val crossfadeStep = 1.0 / max(1.0, CROSSFADE_SECONDS * sampleRate)
    private val inputFrame = DoubleArray(channelCount)
    private val wetFrame = DoubleArray(channelCount)
    private val isStereo = channelCount == 2

    /** Peso del segnale elaborato nell'uscita: 0 è l'originale puro, 1 l'elaborato puro. */
    private var wetMix = 0.0

    fun isBypassed(enabled: Boolean): Boolean = !enabled && wetMix == 0.0

    /** Elaboro sul posto [frameCount] frame interlacciati di [samples]. */
    fun process(samples: FloatArray, frameCount: Int, enabled: Boolean) {
        // Se riaccendo l'effetto dopo un bypass completo azzero lo stato: filtri e dinamica si
        // riferirebbero a un audio ormai lontano e il loro "ricordo" produrrebbe un transitorio.
        if (enabled && wetMix == 0.0) reset()
        val target = if (enabled) 1.0 else 0.0
        var offset = 0
        for (frame in 0 until frameCount) {
            if (wetMix != target) {
                wetMix = if (target > wetMix) {
                    (wetMix + crossfadeStep).coerceAtMost(target)
                } else {
                    (wetMix - crossfadeStep).coerceAtLeast(target)
                }
            }
            if (wetMix == 0.0) {
                // Bypass completo raggiunto a metà buffer: i campioni restanti restano originali.
                offset += channelCount
                continue
            }
            loadFrame(samples, offset)
            if (noiseReducer != null) {
                noiseReducer.process(inputFrame, wetFrame)
            } else {
                System.arraycopy(inputFrame, 0, wetFrame, 0, channelCount)
            }

            // Equalizzazione e misura dell'energia media del frame, che alimenta il livellatore.
            var meanSquare = 0.0
            for (channel in 0 until channelCount) {
                var value = wetFrame[channel]
                for (filter in filters) value = filter.process(value, channel)
                wetFrame[channel] = value
                meanSquare += value * value
            }
            val levelGain = leveler.gainFor(meanSquare / channelCount, noiseReducer?.speechPresent ?: true)

            // Compressore e limitatore lavorano sul picco del frame già livellato, con un unico
            // guadagno per tutti i canali.
            var linkedPeak = 0.0
            for (channel in 0 until channelCount) linkedPeak = max(linkedPeak, abs(wetFrame[channel] * levelGain))
            val compressorGain = compressor.gainFor(linkedPeak)
            val limitGain = limiter.gainFor(linkedPeak * compressorGain)
            val totalGain = levelGain * compressorGain * limitGain

            for (channel in 0 until channelCount) {
                val dry = samples[offset + channel].toDouble()
                val wet = SoftLimiter.apply(wetFrame[channel] * totalGain)
                samples[offset + channel] = (dry + wetMix * (wet - dry)).toFloat()
            }
            offset += channelCount
        }
    }

    /** Copio il frame in [inputFrame], attenuando i lati se il flusso è stereo. */
    private fun loadFrame(samples: FloatArray, offset: Int) {
        if (isStereo) {
            val left = samples[offset].toDouble()
            val right = samples[offset + 1].toDouble()
            val mid = (left + right) * 0.5
            val side = (left - right) * 0.5 * SIDE_GAIN
            inputFrame[0] = mid + side
            inputFrame[1] = mid - side
        } else {
            for (channel in 0 until channelCount) inputFrame[channel] = samples[offset + channel].toDouble()
        }
    }

    /** Chiamato anche dopo un seek, perché lo stato dei filtri non deve "sbavare" tra due punti lontani. */
    fun reset() {
        noiseReducer?.reset()
        filters.forEach(BiquadFilter::reset)
        leveler.reset()
        compressor.reset()
        limiter.reset()
    }

    companion object {
        const val SIDE_GAIN = 0.5
        const val HIGH_PASS_HZ = 120.0
        const val LOW_SHELF_HZ = 250.0
        const val LOW_SHELF_GAIN_DB = -8.0
        const val BOX_HZ = 450.0
        const val BOX_Q = 1.0
        const val BOX_GAIN_DB = -4.0
        const val PRESENCE_HZ = 3_000.0
        const val PRESENCE_Q = 1.0
        const val PRESENCE_GAIN_DB = 3.0
        const val HIGH_SHELF_HZ = 2_000.0
        const val HIGH_SHELF_GAIN_DB = 6.0
        const val COMPRESSOR_THRESHOLD_DB = -12.0
        const val COMPRESSOR_RATIO = 2.5
        const val COMPRESSOR_ATTACK_SECONDS = 0.010
        const val COMPRESSOR_RELEASE_SECONDS = 0.200
        const val CROSSFADE_SECONDS = 0.030
    }
}
