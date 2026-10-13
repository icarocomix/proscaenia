package com.icarocomix.proscaenia.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.icarocomix.proscaenia.domain.model.StreamSource

/**
 * Spettacolo in riproduzione con la sorgente già risolta.
 *
 * Il servizio conserva questo oggetto per ricostruire il MediaItem quando gli URL firmati scadono,
 * per trasferirlo al Chromecast e per conoscere la durata reale ai fini della ripresa. Riproduco la
 * sola parte principale (lo spettacolo) e salto la sigla iniziale di pochi secondi (parte di tipo
 * "ads"): metterla in coda come primo elemento di una playlist renderebbe ambigua la posizione da
 * salvare e riprendere, che deve riferirsi sempre allo spettacolo, e chi riprende a metà non deve
 * rivederla.
 */
@UnstableApi
data class ResolvedShow(
    val showId: String,
    val metadata: MediaMetadata,
    val source: StreamSource,
) {

    /** Durata reale dello spettacolo dichiarata dalla scheda, usata dalla regola di ripresa. */
    val catalogDurationMs: Long? get() = source.featureDurationMs.takeIf { it > 0 }

    /** Posizione di visione conservata lato server, ripiego quando sul dispositivo non c'è una locale. */
    val serverProgressMs: Long get() = source.serverProgressMs

    fun toMediaItem(): MediaItem? {
        val part = source.feature ?: return null
        val builder = MediaItem.Builder()
            .setMediaId(showId)
            .setUri(part.dashUrl)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(metadata)
        part.drm?.let { drm ->
            // Widevine: il player ottiene la chiave dal server licenze della piattaforma, inoltrando
            // gli header di sessione (DeviceId su tutti) richiesti per legare la licenza al dispositivo.
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drm.licenseUrl)
                    .setLicenseRequestHeaders(drm.requestHeaders)
                    .setMultiSession(true)
                    .build(),
            )
        }
        return builder.build()
    }
}
