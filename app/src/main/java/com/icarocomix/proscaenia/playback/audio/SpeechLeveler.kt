package com.icarocomix.proscaenia.playback.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Livellatore lento (controllo automatico del guadagno) che porta il parlato verso un livello RMS
 * costante.
 *
 * È lo stadio che rende davvero udibili le battute pronunciate lontano dal microfono: un
 * compressore veloce agisce solo sui picchi e, con la sua compensazione fissa, finisce per alzare
 * anche riverbero e rumore delle pause. Qui invece misuro l'energia media su una finestra di circa
 * 0,4 s, cioè più lunga di una sillaba, e quindi il guadagno segue la frase e non il singolo
 * fonema: il contrasto tra sillabe e pause, che conta per l'intelligibilità, resta intatto.
 *
 * Il guadagno sale lentamente (costante di 1,5 s) e scende più in fretta (0,3 s), così una battuta
 * urlata dopo un sussurro non esplode; resta inoltre compreso tra [minGainDb] e [maxGainDb]. Quando
 * il livello misurato è sotto [gateDb] (silenzio, cambio scena) o quando il chiamante segnala
 * l'assenza di parlato congelo il guadagno: altrimenti il livellatore inseguirebbe il rumore di
 * fondo portandolo al livello della voce.
 *
 * Il rivelatore riceve l'energia media del frame su tutti i canali, così il guadagno è unico e
 * l'immagine stereo non si sposta.
 */
class SpeechLeveler(
    sampleRate: Int,
    private val targetDb: Double = -17.0,
    private val maxGainDb: Double = 12.0,
    private val minGainDb: Double = -6.0,
    private val gateDb: Double = -50.0,
    detectorSeconds: Double = 0.4,
    gainUpSeconds: Double = 1.5,
    gainDownSeconds: Double = 0.3,
) {

    private val detectorCoefficient = exp(-1.0 / (detectorSeconds * sampleRate))
    private val upCoefficient = exp(-1.0 / (gainUpSeconds * sampleRate))
    private val downCoefficient = exp(-1.0 / (gainDownSeconds * sampleRate))
    private var meanSquare = 0.0
    private var gainDb = 0.0

    /**
     * Restituisco il guadagno lineare per un frame di energia media [frameMeanSquare]. Con
     * [speechPresent] falso (pausa rilevata da [NoiseReducer]) misuro ma non aggiorno il guadagno:
     * altrimenti, nelle pause lunghe, il rivelatore scenderebbe verso il fondo e il livellatore lo
     * alzerebbe fino a +12 dB.
     */
    fun gainFor(frameMeanSquare: Double, speechPresent: Boolean = true): Double {
        meanSquare = frameMeanSquare + detectorCoefficient * (meanSquare - frameMeanSquare)
        val levelDb = 10.0 * log10(meanSquare + SILENCE_POWER)
        if (speechPresent && levelDb > gateDb) {
            val targetGainDb = (targetDb - levelDb).coerceIn(minGainDb, maxGainDb)
            val coefficient = if (targetGainDb > gainDb) upCoefficient else downCoefficient
            gainDb = targetGainDb + coefficient * (gainDb - targetGainDb)
        }
        return 10.0.pow(gainDb / 20.0)
    }

    fun reset() {
        // Riparto da guadagno unitario e da un rivelatore a zero: così, se dopo un seek arriva un
        // silenzio, il livello misurato resta sotto la soglia e il guadagno non sale. Partire da un
        // livello "già a bersaglio" faceva invece attraversare al rivelatore, nella sua discesa,
        // la zona sopra la soglia, alzando il rumore di quasi 9 dB.
        meanSquare = 0.0
        gainDb = 0.0
    }

    private companion object {
        const val SILENCE_POWER = 1e-20
    }
}
