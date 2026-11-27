package com.icarocomix.proscaenia.playback.audio

import kotlin.math.exp
import kotlin.math.pow

/**
 * Limitatore di picco ad attacco istantaneo e rilascio esponenziale, con tetto a [ceilingDb].
 *
 * Il livellatore può aggiungere fino a 12 dB: affidare tutti i picchi al solo [SoftLimiter]
 * vorrebbe dire saturarli spesso con la tangente iperbolica, che a lungo andare genera distorsione
 * udibile. Questo stadio riduce invece il guadagno dell'intero frame quanto basta perché il picco
 * resti sotto il tetto, e lo rilascia in 60 ms; [SoftLimiter] rimane solo come ultima protezione.
 *
 * Non uso un ritardo di anticipo (look-ahead) per non introdurre latenza nella catena di Media3:
 * con l'attacco istantaneo il primo campione che supera il tetto viene già ridotto, il prezzo è una
 * lieve distorsione sui soli transitori più violenti. Il tetto a −2 dBFS lascia margine ai picchi
 * tra un campione e l'altro e alla ricodifica del sistema (per esempio verso un'uscita Bluetooth).
 */
class PeakLimiter(sampleRate: Int, ceilingDb: Double = -2.0, releaseSeconds: Double = 0.060) {

    private val ceiling = 10.0.pow(ceilingDb / 20.0)
    private val releaseCoefficient = exp(-1.0 / (releaseSeconds * sampleRate))
    private var envelope = 0.0

    /** Restituisco il guadagno lineare (≤ 1) per un frame il cui picco tra i canali è [linkedPeak]. */
    fun gainFor(linkedPeak: Double): Double {
        envelope = if (linkedPeak > envelope) linkedPeak else linkedPeak + releaseCoefficient * (envelope - linkedPeak)
        return if (envelope > ceiling) ceiling / envelope else 1.0
    }

    fun reset() {
        envelope = 0.0
    }
}
