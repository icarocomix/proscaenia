package com.icarocomix.proscaenia.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * AudioProcessor di Media3 che applica [VoiceBoostDsp] al PCM decodificato.
 *
 * Lo inserisco nella catena dei processori del DefaultAudioSink, che Media3 esegue prima del
 * processore Sonic responsabile del cambio di velocità: così l'equalizzazione e la compressione
 * lavorano sull'audio a tempo originale e il risultato non cambia accelerando la riproduzione.
 *
 * Il processore resta sempre attivo nella catena per i formati che gestisco (PCM a 16 bit e float),
 * perché in Media3 lo stato attivo di un processore si decide solo in fase di configurazione e
 * cambiarlo richiederebbe di ricreare il sink, con un'interruzione udibile. L'accensione e lo
 * spegnimento passano invece da [enabled], un campo volatile scritto dal thread principale e letto
 * dal thread di riproduzione a ogni buffer; la dissolvenza del DSP evita i click. A effetto spento
 * e dissolvenza conclusa copio i byte così come arrivano, senza nemmeno riconvertirli, e l'uscita è
 * quindi bit per bit identica all'originale.
 */
@UnstableApi
class VoiceBoostAudioProcessor(initiallyEnabled: Boolean) : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = initiallyEnabled

    private var dsp: VoiceBoostDsp? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            // Per un formato che non gestisco mi dichiaro inattivo invece di lanciare
            // UnhandledAudioFormatException: preferisco che l'audio passi intatto piuttosto che
            // interrompere la riproduzione per una funzione accessoria.
            else -> AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Media3 rende effettivo il nuovo formato al flush: è quindi qui che ricreo il DSP, i cui
        // coefficienti dipendono dalla frequenza di campionamento, ed è sempre qui che lo stato dei
        // filtri viene azzerato dopo un seek.
        val format = inputAudioFormat
        dsp = if (format == AudioFormat.NOT_SET) null else VoiceBoostDsp(format.sampleRate, format.channelCount)
    }

    override fun onReset() {
        dsp = null
        scratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val active = enabled
        val processor = dsp
        if (processor == null || processor.isBypassed(active)) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val format = inputAudioFormat
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val frameCount = size / format.bytesPerFrame
        val sampleCount = frameCount * format.channelCount
        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
        val start = inputBuffer.position()

        // Leggo con accessi assoluti nell'ordine di byte del buffer, che in Media3 è quello nativo,
        // così non sposto la posizione finché non ho finito.
        for (index in 0 until sampleCount) {
            scratch[index] = if (isFloat) {
                inputBuffer.getFloat(start + index * Float.SIZE_BYTES)
            } else {
                inputBuffer.getShort(start + index * Short.SIZE_BYTES) / PCM16_SCALE
            }
        }
        processor.process(scratch, frameCount, active)
        for (index in 0 until sampleCount) {
            if (isFloat) {
                output.putFloat(scratch[index])
            } else {
                // La conversione andata e ritorno è esatta per i campioni non elaborati (divisione e
                // moltiplicazione per una potenza di due), quindi anche la coda di un buffer in cui la
                // dissolvenza termina resta identica all'originale.
                output.putShort((scratch[index] * PCM16_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        // Eventuali byte di un frame incompleto, che Media3 non dovrebbe mai consegnare, passano invariati.
        for (position in start + frameCount * format.bytesPerFrame until inputBuffer.limit()) {
            output.put(inputBuffer.get(position))
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private companion object {
        const val PCM16_SCALE = 32768f
    }
}
