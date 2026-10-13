package com.icarocomix.proscaenia.playback

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.icarocomix.proscaenia.domain.model.ShowDetail

/**
 * Richieste di riproduzione inviate dalla UI al servizio.
 *
 * La UI non conosce mai l'URL del flusso: invia un MediaItem che contiene soltanto l'identificativo
 * dello spettacolo e i metadati per notifica e schermata di blocco. È il servizio, nel callback della
 * MediaSession, a risolvere la sorgente reale. Ho scelto questa divisione perché l'URL va rigenerato
 * anche quando l'app non è in primo piano (scadenza a schermo spento) e solo il servizio è sempre
 * presente durante la riproduzione; inoltre qualunque controller esterno può avviare uno spettacolo
 * con il solo ID.
 */
object PlaybackRequests {

    @OptIn(UnstableApi::class)
    fun forShow(show: ShowDetail): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(show.title)
            .setDisplayTitle(show.title)
            .setArtist(show.categoryName)
            .setDescription(show.synopsis.takeIf { it.isNotBlank() })
            .setArtworkUri(show.thumbnailUrl?.toUri())
            .setDurationMs(show.durationSeconds?.times(1_000))
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId(show.id)
            .setMediaMetadata(metadata)
            .build()
    }
}
