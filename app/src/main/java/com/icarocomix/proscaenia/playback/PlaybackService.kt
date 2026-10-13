package com.icarocomix.proscaenia.playback

import android.app.PendingIntent
import android.content.Intent
import com.icarocomix.proscaenia.core.logging.AppLog
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.icarocomix.proscaenia.ProscaeniaApp
import com.icarocomix.proscaenia.di.AppContainer
import com.icarocomix.proscaenia.domain.model.SavedPosition
import com.icarocomix.proscaenia.domain.policy.ResumePolicy
import com.icarocomix.proscaenia.domain.policy.SpeedPolicy
import com.icarocomix.proscaenia.playback.audio.VoiceBoostAudioProcessor
import com.icarocomix.proscaenia.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano che possiede il player e la MediaSession.
 *
 * Tenere il player qui, e non nell'activity, è ciò che permette alla riproduzione di proseguire a
 * schermo spento e con l'app in background: Media3 promuove il servizio in primo piano durante la
 * riproduzione e pubblica la notifica multimediale, visibile anche sulla schermata di blocco, con
 * Play/Pausa, salti di 10 secondi, titolo e miniatura dello spettacolo.
 *
 * La UI comunica con il servizio esclusivamente tramite un MediaController, come qualunque altro
 * controller del sistema. Le uniche eccezioni sono le preferenze persistenti ("Amplifica voce" e
 * velocità), che il servizio legge e osserva direttamente: così una modifica fatta in UI arriva al
 * processore audio senza un protocollo di comandi personalizzati.
 *
 * Tutti i metodi di questa classe girano sul thread principale, che è anche il thread applicativo
 * del player: per questo posso accedere al player dalle coroutine di `lifecycleScope` senza
 * sincronizzazioni aggiuntive.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val container: AppContainer by lazy { (application as ProscaeniaApp).container }

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var sessionPlayer: Player
    private lateinit var voiceBoostProcessor: VoiceBoostAudioProcessor
    private var castPlayer: CastPlayer? = null
    private var mediaSession: MediaSession? = null

    /** Spettacolo attualmente caricato, con la sorgente risolta più recente. */
    private var currentShow: ResolvedShow? = null

    private var periodicSaveJob: Job? = null
    private var urlRecoveryJob: Job? = null
    private var urlRecoveryAttempts = 0

    /** Vero se il player è fermo su un errore di rete in attesa che la connessione torni. */
    private var waitingForNetwork = false

    private val isRemote: Boolean
        get() = sessionPlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    override fun onCreate() {
        super.onCreate()
        val preferences = container.playbackPreferences

        voiceBoostProcessor = VoiceBoostAudioProcessor(initiallyEnabled = preferences.voiceBoostEnabled)
        exoPlayer = container.playerFactory.createExoPlayer(voiceBoostProcessor)
        // Applico la velocità salvata direttamente al player locale, prima che la sessione esista:
        // è il valore di partenza e non una nuova scelta dell'utente da salvare.
        exoPlayer.setPlaybackSpeed(preferences.playbackSpeed)

        castPlayer = createCastPlayerOrNull(exoPlayer)
        sessionPlayer = SpeedLimitingPlayer(castPlayer ?: exoPlayer) { requested ->
            preferences.playbackSpeed = requested
        }
        sessionPlayer.addListener(playerListener)

        // Carico la miniatura della notifica con lo stesso stack di rete del player, così la
        // copertina riceve cookie e User-Agent coerenti; CacheBitmapLoader evita di riscaricarla a
        // ogni aggiornamento della notifica.
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader.Builder(this)
                .setDataSourceFactory(container.playerFactory.dataSourceFactory)
                .build(),
        )
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId(SESSION_ID)
            .setCallback(SessionCallback())
            .setBitmapLoader(bitmapLoader)
            .setMediaButtonPreferences(seekButtons())
            .setSessionActivity(sessionActivity(showId = null))
            .build()

        observeVoiceBoostPreference()
        observeReconnections()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Media3 restituirebbe START_STICKY, ma dopo la morte del processo il sistema ricreerebbe un
        // servizio senza spettacolo né URL valido: preferisco che non venga riavviato da solo.
        return START_NOT_STICKY
    }

    /**
     * Quando l'utente chiude l'app dalle recenti salvo subito la posizione. Se lo spettacolo era in
     * pausa o terminato fermo anche il servizio, altrimenti lascio proseguire la riproduzione in
     * background, che è il comportamento atteso da un lettore multimediale.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentPosition(synchronous = true)
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Salvataggio sincrono: dopo onDestroy il processo può essere terminato in qualunque momento.
        saveCurrentPosition(synchronous = true)
        periodicSaveJob?.cancel()
        urlRecoveryJob?.cancel()
        sessionPlayer.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        castPlayer?.release()
        exoPlayer.release()
        container.onPlaybackActiveChanged(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ costruzione

    /**
     * Costruisco il CastPlayer di Media3 solo se il framework Cast è utilizzabile. Il CastPlayer
     * incapsula il player locale e quello remoto e commuta da solo tra i due quando si apre o si
     * chiude una sessione Cast; a me resta da definire, nella callback di trasferimento, cosa passare
     * da un lato all'altro. Se qualcosa fallisce torno al solo player locale senza propagare errori.
     */
    private fun createCastPlayerOrNull(localPlayer: ExoPlayer): CastPlayer? {
        if (container.castSupport.castContextOrNull() == null) return null
        return try {
            val remotePlayer = RemoteCastPlayer.Builder(this)
                .setSeekBackIncrementMs(PlayerFactory.SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(PlayerFactory.SEEK_INCREMENT_MS)
                .build()
            CastPlayer.Builder(this)
                .setLocalPlayer(localPlayer)
                .setRemotePlayer(remotePlayer)
                .setTransferCallback(::transferState)
                .build()
        } catch (e: Exception) {
            AppLog.w(TAG, "CastPlayer non disponibile, uso solo il player locale", e)
            null
        }
    }

    /** Pulsanti di notifica e schermata di blocco: indietro di 10 s a sinistra, avanti di 10 s a destra. */
    private fun seekButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Indietro di 10 secondi")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName("Avanti di 10 secondi")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    /**
     * Il tocco sulla notifica riapre l'app direttamente sul player dello spettacolo in corso. Aggiorno
     * il PendingIntent a ogni cambio di spettacolo; FLAG_UPDATE_CURRENT sostituisce gli extra di
     * quello esistente invece di accumularne di nuovi.
     */
    private fun sessionActivity(showId: String?): PendingIntent =
        PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            MainActivity.openPlayerIntent(this, showId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun observeVoiceBoostPreference() {
        lifecycleScope.launch {
            // Scrivo un campo volatile letto dal thread audio: l'effetto cambia al buffer successivo,
            // con la dissolvenza del DSP, senza ricreare né mettere in pausa il player.
            container.playbackPreferences.voiceBoostChanges.collect { enabled ->
                voiceBoostProcessor.enabled = enabled
            }
        }
    }

    // ------------------------------------------------------------------ passaggio telefono ⇄ TV

    /**
     * Trasferisco tra telefono e Chromecast posizione, stato play/pausa e velocità. Ricostruisco il
     * MediaItem dalla sorgente risolta invece di copiare quello del player di origine, perché un item
     * proveniente dal Chromecast passa da una conversione che non preserva tutti i campi locali.
     * Verso il Chromecast limito la velocità a 2x; al ritorno ripristino quella scelta dall'utente,
     * che potrebbe essere superiore.
     */
    private fun transferState(source: Player, target: Player) {
        val item = currentShow?.toMediaItem() ?: source.currentMediaItem ?: return
        saveCurrentPosition(synchronous = false)
        val position = source.currentPosition.coerceAtLeast(0)
        val userSpeed = container.playbackPreferences.playbackSpeed
        val toRemote = target is RemoteCastPlayer
        target.setMediaItem(item, position)
        target.setPlaybackSpeed(if (toRemote) SpeedPolicy.capForCast(userSpeed) else userSpeed)
        target.playWhenReady = source.playWhenReady
        target.prepare()
    }

    // ------------------------------------------------------------------ risoluzione degli spettacoli

    private inner class SessionCallback : MediaSession.Callback {

        /**
         * Ricevo dalla UI (o da un controller esterno) un MediaItem con il solo ID dello spettacolo e
         * lo trasformo in un item riproducibile. La posizione di partenza segue questa logica: se il
         * controller ne indica una esplicita la rispetto (per esempio un "riprova" dalla posizione
         * corrente), altrimenti applico la regola di ripresa automatica sulla posizione salvata.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = lifecycleScope.future {
            val requested = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
                ?: throw IllegalArgumentException("Nessuno spettacolo richiesto")
            val resolved = resolve(requested)

            // Salvo la posizione dello spettacolo precedente prima di sostituirlo.
            saveCurrentPosition(synchronous = false)
            prepareForNewShow(resolved)

            val startPosition = if (startPositionMs != C.TIME_UNSET && startPositionMs >= 0) {
                startPositionMs
            } else {
                resumePositionFor(resolved)
            }
            MediaSession.MediaItemsWithStartPosition(listOf(mediaItemOf(resolved)), 0, startPosition)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = lifecycleScope.future {
            mediaItems.map { mediaItemOf(resolve(it)) }.toMutableList()
        }
    }

    /**
     * Risolvo la sorgente e, in caso di errore, avviso i controller collegati prima di far fallire il
     * future: Media3 si limiterebbe a registrare il fallimento nei log e la UI resterebbe in attesa
     * senza sapere perché la riproduzione non parte.
     */
    private suspend fun resolve(requested: MediaItem): ResolvedShow {
        val showId = requested.mediaId.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("MediaItem privo dell'identificativo dello spettacolo")
        return try {
            ResolvedShow(showId, requested.mediaMetadata, container.streamRepository.resolve(showId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Risoluzione della sorgente di $showId non riuscita", e)
            mediaSession?.sendError(PlaybackErrors.toSessionError(e))
            throw e
        }
    }

    /** Costruisco il MediaItem riproducibile, segnalando come errore una scheda senza parti valide. */
    private fun mediaItemOf(resolved: ResolvedShow): MediaItem =
        resolved.toMediaItem() ?: run {
            val error = PlaybackErrors.unplayable("Lo spettacolo non ha un formato riproducibile")
            mediaSession?.sendError(error)
            throw IllegalStateException(error.message)
        }

    /**
     * Punto di ripresa: uso la posizione locale, più aggiornata perché salvata ogni 10 secondi, e in
     * sua assenza quella conservata dal server, così su un dispositivo nuovo si riprende comunque da
     * dove si era arrivati sul sito o su un altro telefono.
     */
    private fun resumePositionFor(resolved: ResolvedShow): Long {
        val local = container.positionStore.read(resolved.showId)
        if (local != null) return ResumePolicy.resumePositionMs(local, resolved.catalogDurationMs)
        val serverPosition = SavedPosition(resolved.serverProgressMs, resolved.catalogDurationMs ?: 0L)
        return ResumePolicy.resumePositionMs(serverPosition, resolved.catalogDurationMs)
    }

    private fun prepareForNewShow(show: ResolvedShow) {
        urlRecoveryJob?.cancel()
        urlRecoveryAttempts = 0
        currentShow = show
        mediaSession?.setSessionActivity(sessionActivity(show.showId))
        // Ogni nuovo spettacolo riparte con i sottotitoli spenti, come da comportamento predefinito.
        if (sessionPlayer.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
            sessionPlayer.trackSelectionParameters = sessionPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
    }

    // ------------------------------------------------------------------ eventi del player

    private val playerListener = object : Player.Listener {

        /**
         * Comunico al rilascio automatico del posto dispositivo se c'è una riproduzione attiva:
         * spettacolo caricato e in riproduzione, buffering o pausa. Fermo, terminato o in errore
         * (stato IDLE o ENDED) non lo è, e con l'interfaccia chiusa il posto può essere liberato.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            val state = player.playbackState
            val active = player.mediaItemCount > 0 && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            container.onPlaybackActiveChanged(active)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Durante la riproduzione salvo ogni 10 secondi; quando si ferma (pausa, attesa del buffer,
            // fine) salvo subito, così la posizione registrata è sempre quella dell'ultimo arresto.
            if (isPlaying) {
                startPeriodicSave()
            } else {
                periodicSaveJob?.cancel()
                saveCurrentPosition(synchronous = false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Una riproduzione arrivata a READY dimostra che il link è valido: azzero il contatore dei
            // tentativi, così il limite vale per ogni singolo episodio di errore.
            if (playbackState == Player.STATE_READY) {
                urlRecoveryAttempts = 0
                waitingForNetwork = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Registro ogni errore del player con codice, spettacolo e dispositivo di uscita prima di
            // tentare il recupero: anche un errore poi risolto da solo è un indizio utile nel file di log.
            val target = if (isRemote) "Chromecast" else "telefono"
            AppLog.e(TAG, "Errore di riproduzione ${error.errorCodeName} su ${currentShow?.showId} ($target)", error)
            recoverAutomatically(error)
        }
    }

    /**
     * Recupero da solo gli errori che non dipendono dal contenuto, così la visione riprende senza
     * alcun intervento anche a schermo spento:
     *
     * - URL firmato scaduto (403/410 dalla CDN, succede dopo un certo tempo o se cambia la rete):
     *   chiedo subito alla piattaforma un link nuovo;
     * - errore di rete: se il dispositivo è in linea ricarico dopo una breve attesa, altrimenti
     *   aspetto il ritorno della rete (vedi [observeReconnections]).
     *
     * In entrambi i casi riparto dalla stessa posizione e con lo stesso stato play/pausa. Mi fermo
     * dopo [MAX_URL_RECOVERY_ATTEMPTS] tentativi consecutivi senza successo, per non ciclare su un
     * problema persistente: l'errore resta visibile al controller, che offre un nuovo tentativo, e un
     * ritorno della rete concede comunque un nuovo giro. Durante il Cast non intervengo, perché è il
     * Chromecast a leggere il flusso e a gestirne gli errori.
     */
    private fun recoverAutomatically(error: PlaybackException) {
        if (isRemote) return
        val show = currentShow ?: return
        val expiredStatus = PlaybackErrors.expiredUrlStatus(error)
        when {
            expiredStatus != null -> reloadWithFreshSource(show, "URL scaduto (HTTP $expiredStatus)", delayMs = 0)
            PlaybackErrors.isNetworkError(error) -> {
                if (container.networkMonitor.isOnline.value) {
                    reloadWithFreshSource(show, "errore di rete ${error.errorCodeName}", delayMs = NETWORK_RETRY_DELAY_MS)
                } else {
                    waitingForNetwork = true
                    AppLog.i(TAG, "Riproduzione di ${show.showId} interrotta senza rete: riprendo al ritorno della connessione")
                }
            }
        }
    }

    /**
     * Al ritorno della rete riprendo la riproduzione rimasta ferma su un errore di connessione. Lo
     * considero un nuovo episodio, quindi azzero il contatore dei tentativi.
     */
    private fun observeReconnections() {
        lifecycleScope.launch {
            container.networkMonitor.reconnections.collect {
                val show = currentShow ?: return@collect
                if (!waitingForNetwork || sessionPlayer.playerError == null || isRemote) return@collect
                waitingForNetwork = false
                urlRecoveryAttempts = 0
                reloadWithFreshSource(show, "rete tornata disponibile", delayMs = 0)
            }
        }
    }

    /**
     * Rigenero la sorgente e la ricarico dalla posizione corrente. Il link lo richiedo sempre nuovo,
     * anche dopo un errore di rete: dopo un'interruzione lunga quello vecchio potrebbe essere scaduto.
     * La risoluzione passa dai ritentativi automatici delle API e, se serve, dal login automatico con
     * le credenziali salvate; se fallisce per mancanza di rete resto in attesa della riconnessione.
     */
    private fun reloadWithFreshSource(show: ResolvedShow, reason: String, delayMs: Long) {
        if (urlRecoveryAttempts >= MAX_URL_RECOVERY_ATTEMPTS) {
            AppLog.w(TAG, "Recupero di ${show.showId} sospeso ($reason) dopo $urlRecoveryAttempts tentativi")
            return
        }
        urlRecoveryAttempts++
        val resumeAt = sessionPlayer.currentPosition.coerceAtLeast(0)
        val playWhenReady = sessionPlayer.playWhenReady
        AppLog.w(TAG, "Recupero automatico di ${show.showId} ($reason), tentativo $urlRecoveryAttempts")

        urlRecoveryJob?.cancel()
        urlRecoveryJob = lifecycleScope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val freshSource = container.streamRepository.resolve(show.showId)
                // Nel frattempo l'utente potrebbe aver aperto un altro spettacolo: in quel caso il
                // link rigenerato non serve più e non devo sostituire ciò che sta suonando.
                if (currentShow?.showId != show.showId) return@launch
                val refreshed = show.copy(source = freshSource)
                val item = refreshed.toMediaItem() ?: return@launch
                currentShow = refreshed
                sessionPlayer.setMediaItem(item, resumeAt)
                sessionPlayer.playWhenReady = playWhenReady
                sessionPlayer.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Recupero automatico di ${show.showId} non riuscito", e)
                if (!container.networkMonitor.isOnline.value) waitingForNetwork = true
                mediaSession?.sendError(PlaybackErrors.toSessionError(e))
            }
        }
    }

    // ------------------------------------------------------------------ salvataggio della posizione

    private fun startPeriodicSave() {
        periodicSaveJob?.cancel()
        periodicSaveJob = lifecycleScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                saveCurrentPosition(synchronous = false)
            }
        }
    }

    /**
     * Salvo anche durante il Cast, perché il player remoto riporta comunque la posizione corrente.
     * Come durata preferisco quella misurata dal player, più affidabile di quella del catalogo; se
     * non è ancora nota (prima del primo READY) ripiego su quella dichiarata dalla piattaforma.
     */
    private fun saveCurrentPosition(synchronous: Boolean) {
        if (!::sessionPlayer.isInitialized) return
        val showId = sessionPlayer.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val position = sessionPlayer.currentPosition
        if (position < 0) return
        val duration = sessionPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: currentShow?.takeIf { it.showId == showId }?.catalogDurationMs
            ?: C.TIME_UNSET
        container.positionStore.save(showId, position, duration, synchronous)
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val SESSION_ID = "proscaenia"
        const val SESSION_ACTIVITY_REQUEST_CODE = 1
        const val MAX_URL_RECOVERY_ATTEMPTS = 3
        const val NETWORK_RETRY_DELAY_MS = 3_000L
        const val POSITION_SAVE_INTERVAL_MS = 10_000L
    }
}
