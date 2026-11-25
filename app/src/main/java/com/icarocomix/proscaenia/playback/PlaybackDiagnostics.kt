package com.icarocomix.proscaenia.playback

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.icarocomix.proscaenia.core.logging.AppLog

/**
 * Diagnostica della fluidità di riproduzione: rende misurabile il "lag" percepito distinguendone le
 * cause possibili, che richiedono rimedi diversi.
 *
 * - Stallo (rebuffer): il buffer si svuota durante la visione → rete/CDN o buffer troppo corto.
 * - Fotogrammi persi: il decoder o il rendering non tengono il passo → carico CPU/GPU.
 * - Underrun audio: l'audio non arriva in tempo al sink → elaborazione audio (DSP) troppo pesante.
 * - Cambi di qualità frequenti: l'adattamento del bitrate oscilla → banda instabile.
 *
 * Gli eventi rilevanti (stalli, fotogrammi persi, underrun) vanno sul file di log, così si possono
 * leggere anche senza ADB; quelli frequenti (stima di banda) restano solo su logcat.
 */
@UnstableApi
class PlaybackDiagnostics : AnalyticsListener {

    private var lastState = Player.STATE_IDLE
    private var lastSeekAtMs = 0L
    private var stallStartedAtMs = 0L
    private var bitrateEstimate = 0L
    private var videoBitrate = Format.NO_VALUE
    private var videoResolution = "?"

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Un buffering subito dopo un salto è atteso e non va contato come stallo.
        if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAtMs = SystemClock.elapsedRealtime()
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastState == Player.STATE_READY && state == Player.STATE_BUFFERING && now - lastSeekAtMs > SEEK_GRACE_MS) {
            stallStartedAtMs = now
            AppLog.w(TAG, "STALLO a ${eventTime.eventPlaybackPositionMs / 1_000}s: buffer ${eventTime.totalBufferedDurationMs} ms, " + qualitySummary())
        } else if (state == Player.STATE_READY && stallStartedAtMs != 0L) {
            AppLog.w(TAG, "ripresa dopo stallo di ${now - stallStartedAtMs} ms")
            stallStartedAtMs = 0L
        }
        lastState = state
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        AppLog.w(TAG, "fotogrammi persi: $droppedFrames in $elapsedMs ms, " + qualitySummary())
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        AppLog.w(TAG, "underrun audio: buffer $bufferSizeMs ms, ultimo invio $elapsedSinceLastFeedMs ms fa")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        videoBitrate = format.bitrate
        videoResolution = "${format.width}x${format.height}"
        AppLog.i(TAG, "qualità video: " + qualitySummary())
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        this.bitrateEstimate = bitrateEstimate
        AppLog.i(TAG, "banda stimata ${bitrateEstimate / 1_000} kbit/s, buffer ${eventTime.totalBufferedDurationMs} ms")
    }

    private fun qualitySummary(): String {
        val bitrate = if (videoBitrate == Format.NO_VALUE) "?" else "${videoBitrate / 1_000}"
        return "video $videoResolution @ $bitrate kbit/s, banda stimata ${bitrateEstimate / 1_000} kbit/s"
    }

    private companion object {
        const val TAG = "PlaybackDiagnostics"
        const val SEEK_GRACE_MS = 1_500L
    }
}
