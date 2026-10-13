package com.icarocomix.proscaenia.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * Costruzione di ExoPlayer con la configurazione richiesta per lo streaming teatrale.
 *
 * Tengo la configurazione in una classe separata dal servizio perché riguarda solo il "come"
 * costruire il motore (rete, buffer, audio) e non il ciclo di vita della riproduzione.
 */
@UnstableApi
class PlayerFactory(
    private val context: Context,
    mediaHttpClient: OkHttpClient,
    userAgent: String,
) {

    /**
     * Tutte le letture di rete del player passano dal client OkHttp dei media, che condivide CookieJar
     * e User-Agent con quello delle API: è la condizione perché la CDN riconosca come "stesso client"
     * chi ha chiesto il link e chi scarica i segmenti, ed eviti i 403. Non avvolgo la sorgente in un
     * CacheDataSource: nessun byte del flusso viene scritto su disco. DefaultDataSource aggiunge il
     * supporto agli schemi locali (data:, file:, asset:) senza modificare il percorso HTTP.
     */
    private val httpDataSourceFactory = OkHttpDataSource.Factory(mediaHttpClient).setUserAgent(userAgent)

    val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpDataSourceFactory)


    fun createExoPlayer(voiceBoostProcessor: AudioProcessor): ExoPlayer {
        // Buffer esclusivamente in RAM (DefaultLoadControl usa un allocatore in memoria) e
        // volutamente corto: almeno 15 s e al massimo 40 s di contenuto, avvio appena ci sono 1,5 s
        // e ripartenza dopo un'attesa con 3 s. Un buffer corto limita la memoria occupata su
        // dispositivi modesti e riduce lo spreco di banda quando l'utente salta avanti o chiude.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Inserisco "Amplifica voce" nella catena dei processori del sink audio. Media3 applica i
        // processori personalizzati prima di quelli interni di salto dei silenzi e di cambio
        // velocità, quindi il DSP elabora l'audio decodificato a tempo originale. Lascio il cambio
        // di velocità al processore Sonic (comportamento predefinito) invece di delegarlo
        // all'AudioTrack: così l'ordine "prima la voce, poi la velocità" è garantito dalla catena
        // stessa e non dipende dall'implementazione audio del produttore.
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setAudioProcessors(arrayOf(voiceBoostProcessor))
                    .build()
        }

        // Senza questa configurazione Media3 invierebbe le richieste di licenza Widevine con il proprio
        // DefaultHttpDataSource, cioè con un User-Agent diverso e senza gli header di piattaforma
        // (Origin, Referer) aggiunti dagli interceptor: le faccio passare dallo stesso client OkHttp
        // dei segmenti. Creo il provider per ogni player e non una volta sola: memorizza un gestore DRM
        // legato al thread di riproduzione del primo player che lo usa, e un servizio ricreato nello
        // stesso processo (uscita e rientro nell'app) falliva con IllegalStateException in
        // DefaultDrmSessionManager.initPlaybackLooper.
        val drmSessionManagerProvider = DefaultDrmSessionManagerProvider().apply {
            setDrmHttpDataSourceFactory(httpDataSourceFactory)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManagerProvider)
            .setLoadErrorHandlingPolicy(ExpiredUrlLoadErrorPolicy())

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            // Metto in pausa se si scollegano le cuffie, per non far partire l'audio dall'altoparlante.
            .setHandleAudioBecomingNoisy(true)
            // A schermo spento CPU e Wi-Fi devono restare attivi per continuare a scaricare il flusso.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()

        // Sottotitoli spenti di default: il tipo di traccia testo resta disabilitato finché l'utente
        // non lo attiva esplicitamente dal player.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        return player
    }

    companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 40_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        const val SEEK_INCREMENT_MS = 10_000L
    }
}
