package com.icarocomix.proscaenia.playback.audio

import kotlin.math.abs
import kotlin.math.max

/**
 * Catena di elaborazione "Amplifica voce", indipendente da Android e da Media3 così da poterla
 * verificare con semplici test JVM.
 *
 * Lavoro su campioni float interlacciati nell'intervallo [-1, 1]. Per ogni frame applico, in
 * quest'ordine, il passa-alto Butterworth del 4° ordine a 100 Hz (due sezioni biquad in cascata),
 * che toglie rimbombi del palco e ronzii di rete senza toccare la fondamentale della voce; poi le
 * due campane di presenza a 1,3 kHz e 3 kHz (Q 1, +4,5 dB ciascuna), che insieme formano un
 * altopiano sulla banda dove risiede l'intelligibilità delle consonanti, mentre una campana unica
 * lascerebbe scoperti i bordi; quindi il compressore collegato tra i canali, che avvicina i
 * sussurri alle battute urlate; infine il limitatore morbido, che garantisce l'assenza di clipping.
 *
 * Il passaggio tra segnale originale ed elaborato avviene con una dissolvenza incrociata lineare di
 * 30 ms, così accendere o spegnere l'effetto durante la riproduzione non produce click. Quando
 * l'effetto è spento e la dissolvenza è terminata il campione viene lasciato esattamente com'era:
 * il chiamante può riconoscere questo stato con [isBypassed] ed evitare del tutto la conversione.
 */
class VoiceBoostDsp(sampleRate: Int, private val channelCount: Int) {

    private val filters: Array<BiquadFilter> = arrayOf(
        BiquadFilter.highPass(sampleRate, HIGH_PASS_HZ, BiquadFilter.BUTTERWORTH_4TH_ORDER_Q[0], channelCount),
        BiquadFilter.highPass(sampleRate, HIGH_PASS_HZ, BiquadFilter.BUTTERWORTH_4TH_ORDER_Q[1], channelCount),
        BiquadFilter.peaking(sampleRate, PRESENCE_LOW_HZ, PRESENCE_Q, PRESENCE_GAIN_DB, channelCount),
        BiquadFilter.peaking(sampleRate, PRESENCE_HIGH_HZ, PRESENCE_Q, PRESENCE_GAIN_DB, channelCount),
    )

    private val compressor = LinkedCompressor(sampleRate)
    private val crossfadeStep = 1.0 / max(1.0, CROSSFADE_SECONDS * sampleRate)
    private val wetFrame = DoubleArray(channelCount)

    /** Peso del segnale elaborato nell'uscita: 0 è l'originale puro, 1 l'elaborato puro. */
    private var wetMix = 0.0

    fun isBypassed(enabled: Boolean): Boolean = !enabled && wetMix == 0.0

    /** Elaboro sul posto [frameCount] frame interlacciati di [samples]. */
    fun process(samples: FloatArray, frameCount: Int, enabled: Boolean) {
        // Se riaccendo l'effetto dopo un bypass completo azzero lo stato: filtri e compressore si
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
            var linkedPeak = 0.0
            for (channel in 0 until channelCount) {
                var value = samples[offset + channel].toDouble()
                for (filter in filters) value = filter.process(value, channel)
                wetFrame[channel] = value
                linkedPeak = max(linkedPeak, abs(value))
            }
            val gain = compressor.gainFor(linkedPeak)
            for (channel in 0 until channelCount) {
                val dry = samples[offset + channel].toDouble()
                val wet = SoftLimiter.apply(wetFrame[channel] * gain)
                samples[offset + channel] = (dry + wetMix * (wet - dry)).toFloat()
            }
            offset += channelCount
        }
    }

    /** Chiamato anche dopo un seek, perché lo stato dei filtri non deve "sbavare" tra due punti lontani. */
    fun reset() {
        filters.forEach(BiquadFilter::reset)
        compressor.reset()
    }

    companion object {
        const val HIGH_PASS_HZ = 100.0
        const val PRESENCE_LOW_HZ = 1_300.0
        const val PRESENCE_HIGH_HZ = 3_000.0
        const val PRESENCE_Q = 1.0
        const val PRESENCE_GAIN_DB = 4.5
        const val CROSSFADE_SECONDS = 0.030
    }
}
