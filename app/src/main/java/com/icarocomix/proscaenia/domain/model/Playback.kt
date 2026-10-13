package com.icarocomix.proscaenia.domain.model

/**
 * Configurazione DRM di una parte video.
 *
 * Gli spettacoli di Proscaenia sono DASH protetti con Widevine: il player ottiene la chiave di
 * decifratura richiedendola al server delle licenze della piattaforma, esattamente come fa il sito.
 * La richiesta di licenza deve portare gli stessi header di sessione delle altre chiamate (DeviceId
 * in primo luogo), perché il server lega la licenza al dispositivo; per questo li trasporto qui.
 */
data class DrmConfig(
    val licenseUrl: String,
    val requestHeaders: Map<String, String>,
)

/**
 * Una parte riproducibile di uno spettacolo. La piattaforma consegna una sequenza di parti: una
 * breve sigla iniziale ([isIntro]) e poi lo spettacolo vero e proprio. Ogni parte ha una sorgente
 * DASH (e una HLS di riserva) e, se protetta, la propria configurazione DRM.
 */
data class VideoPart(
    val dashUrl: String,
    val hlsUrl: String?,
    val drm: DrmConfig?,
    val durationMs: Long,
    val isIntro: Boolean,
)

/**
 * Sorgente completa di uno spettacolo. Gli URL sono firmati e a scadenza: non li metto mai in cache
 * e il player li rigenera quando il server risponde 403/410. [serverProgressMs] è la posizione di
 * visione che la piattaforma conserva lato server, usata come ripresa quando sul dispositivo non c'è
 * una posizione locale più recente.
 */
data class StreamSource(
    val parts: List<VideoPart>,
    val subtitles: List<SubtitleTrack>,
    val serverProgressMs: Long,
    val featureDurationMs: Long,
) {
    /** La parte principale: lo spettacolo, non la sigla. È quella la cui posizione va salvata. */
    val feature: VideoPart? get() = parts.firstOrNull { !it.isIntro } ?: parts.firstOrNull()
}

/** Traccia di sottotitoli WebVTT fornita dalla piattaforma. */
data class SubtitleTrack(
    val url: String,
    val language: String?,
    val label: String?,
)

/** Ultima posizione di riproduzione salvata per uno spettacolo, con la durata nota in quel momento. */
data class SavedPosition(
    val positionMs: Long,
    val durationMs: Long,
)
