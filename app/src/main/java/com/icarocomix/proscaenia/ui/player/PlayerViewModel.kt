package com.icarocomix.proscaenia.ui.player

import android.app.Application
import android.content.ComponentName
import com.icarocomix.proscaenia.core.logging.AppLog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.data.local.PlaybackPreferences
import com.icarocomix.proscaenia.domain.model.ShowDetail
import com.icarocomix.proscaenia.domain.policy.SpeedPolicy
import com.icarocomix.proscaenia.domain.repository.CatalogRepository
import com.icarocomix.proscaenia.playback.PlaybackRequests
import com.icarocomix.proscaenia.playback.PlaybackService
import com.icarocomix.proscaenia.playback.cast.CastSupport
import com.icarocomix.proscaenia.ui.common.AutoRetryTriggers
import com.icarocomix.proscaenia.ui.common.ErrorMessages
import com.icarocomix.proscaenia.ui.common.UiMessage
import com.icarocomix.proscaenia.ui.common.appContainer
import com.icarocomix.proscaenia.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

data class PlayerUiState(
    val title: String = "",
    val isConnected: Boolean = false,
    val castAvailable: Boolean = false,
    val isCasting: Boolean = false,
    val castDeviceName: String? = null,
    /** Velocità effettivamente applicata dal player, già limitata durante il Cast. */
    val speed: Float = SpeedPolicy.DEFAULT_SPEED,
    /** Velocità scelta dall'utente e salvata nelle preferenze. */
    val requestedSpeed: Float = SpeedPolicy.DEFAULT_SPEED,
    val voiceBoostEnabled: Boolean = true,
    val hasSubtitles: Boolean = false,
    val subtitlesEnabled: Boolean = false,
    val error: UiMessage? = null,
    /** Vero se l'errore mostrato può risolversi con un nuovo tentativo automatico. */
    val errorRetryable: Boolean = false,
)

/**
 * ViewModel del player: possiede il MediaController collegato al [PlaybackService].
 *
 * Il controller vive nel ViewModel, e non nella composable, perché deve sopravvivere alle
 * ricomposizioni e ai cambi di orientamento senza riconnettersi alla sessione. Lo rilascio in
 * [onCleared]: questo scollega soltanto la UI, mentre la riproduzione prosegue nel servizio, che
 * è il comportamento atteso uscendo dal player con la riproduzione in corso.
 */
class PlayerViewModel(
    private val application: Application,
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val preferences: PlaybackPreferences,
    private val castSupport: CastSupport,
    private val autoRetryTriggers: AutoRetryTriggers,
) : ViewModel() {

    private val showId: String = checkNotNull(savedStateHandle[Routes.ARG_SHOW_ID]) {
        "Rotta del player senza identificativo dello spettacolo"
    }

    private val _state = MutableStateFlow(
        PlayerUiState(
            castAvailable = castSupport.isAvailable,
            requestedSpeed = preferences.playbackSpeed,
            voiceBoostEnabled = preferences.voiceBoostEnabled,
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)

    /** Player da agganciare alla PlayerView; null finché il controller non è connesso. */
    val player: StateFlow<Player?> = _player.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var connectJob: Job? = null
    private var show: ShowDetail? = null

    /*
     * I listener devono essere dichiarati prima del blocco init. viewModelScope usa
     * Dispatchers.Main.immediate, quindi la coroutine lanciata nell'init gira subito e in modo
     * sincrono finché non sospende; con il catalogo già in memoria arriva a usare questi campi prima
     * che l'inizializzazione in ordine testuale li abbia valorizzati, e Media3 riceveva un listener
     * null (NullPointerException in setListener al tocco su "Riproduci").
     */
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onError(controller: MediaController, sessionError: SessionError) {
            _state.update { it.copy(error = ErrorMessages.fromSession(sessionError), errorRetryable = ErrorMessages.isRetryable(sessionError)) }
        }
    }

    init {
        viewModelScope.launch {
            preferences.voiceBoostChanges.collect { enabled -> _state.update { it.copy(voiceBoostEnabled = enabled) } }
        }
        connectJob = viewModelScope.launch { connectAndStart() }
        viewModelScope.launch {
            autoRetryTriggers.events().collect { if (needsAutomaticRetry()) retry() }
        }
    }

    /**
     * Riprovo da solo gli errori nati fuori dal player: scheda non caricata, sessione non collegata,
     * sorgente non risolta (errore arrivato via sessione). Gli errori del player li recupera già il
     * servizio, anche a schermo spento; intervenire anche qui causerebbe due ricaricamenti in parallelo.
     */
    private fun needsAutomaticRetry(): Boolean {
        if (_state.value.error == null || !_state.value.errorRetryable) return false
        val controller = currentController() ?: return connectJob?.isActive != true
        return controller.playerError == null
    }

    /**
     * Mi collego al servizio e avvio lo spettacolo solo se non è già quello in riproduzione: se l'utente
     * rientra dal catalogo o dalla notifica mentre lo spettacolo suona, devo agganciarmi alla
     * riproduzione esistente e non ricominciarla. Il MediaItem inviato contiene solo ID e metadati;
     * la sorgente reale la risolve il servizio.
     */
    private suspend fun connectAndStart() {
        val detail = try {
            catalogRepository.show(showId)
        } catch (e: IOException) {
            _state.update { it.copy(error = ErrorMessages.from(e, "Scheda $showId non disponibile per il player"), errorRetryable = ErrorMessages.isRetryable(e)) }
            return
        }
        show = detail
        _state.update { it.copy(title = detail.title) }

        // Un nuovo tentativo dopo una connessione fallita non deve lasciare appeso il future precedente.
        controllerFuture?.let(MediaController::releaseFuture)
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        val controller = try {
            future.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // La connessione può fallire solo per problemi del sistema (servizio non avviabile): lo
            // registro e mostro un errore generico invece di lasciare la schermata nera.
            AppLog.e(TAG, "Connessione alla sessione di riproduzione non riuscita", e)
            _state.update { it.copy(error = UiMessage(R.string.error_unknown), errorRetryable = true) }
            return
        }

        controller.addListener(playerListener)
        when {
            controller.currentMediaItem?.mediaId != showId -> {
                controller.setMediaItem(PlaybackRequests.forShow(detail))
                controller.prepare()
                controller.play()
            }
            // Stesso spettacolo ma fermo su un errore: riprovo dalla posizione in cui si era fermato.
            controller.playerError != null -> restartFromCurrentPosition(controller, detail)
        }
        _player.value = controller
        _state.update { it.copy(isConnected = true) }
        syncFrom(controller)
    }

    fun selectSpeed(speed: Float) {
        val normalized = SpeedPolicy.normalize(speed)
        _state.update { it.copy(requestedSpeed = normalized) }
        // Il salvataggio della preferenza e il tetto di 2x durante il Cast avvengono nel servizio,
        // che riceve il comando attraverso la sessione: qui mi limito a inoltrarlo.
        currentController()?.setPlaybackSpeed(normalized)
    }

    fun resetSpeed() = selectSpeed(SpeedPolicy.DEFAULT_SPEED)

    /** Scrivo solo la preferenza: il servizio la osserva e accende o spegne il DSP al volo. */
    fun toggleVoiceBoost() {
        preferences.voiceBoostEnabled = !_state.value.voiceBoostEnabled
    }

    /**
     * Le tracce WebVTT caricate a parte non hanno flag "predefinita" e il selettore di Media3 non le
     * sceglierebbe da solo neppure con il tipo testo riabilitato. All'attivazione quindi indico in modo
     * esplicito quale gruppo usare, preferendo quello nella lingua del dispositivo; alla
     * disattivazione rimuovo l'override e disabilito di nuovo l'intero tipo di traccia.
     */
    fun toggleSubtitles() {
        val controller = currentController() ?: return
        val enable = !_state.value.subtitlesEnabled
        val builder = controller.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enable)
        if (enable) {
            preferredTextGroup(controller.currentTracks)?.let { group ->
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }
        controller.trackSelectionParameters = builder.build()
    }

    /**
     * Senza controller rifaccio l'intero collegamento, ma solo se non ce n'è già uno in corso: il
     * tocco su "Riprova" e un ritentativo automatico possono arrivare insieme, e due collegamenti
     * paralleli creerebbero due controller che inviano lo stesso spettacolo al servizio.
     */
    fun retry() {
        val controller = currentController() ?: run {
            if (connectJob?.isActive == true) return
            dismissError()
            connectJob = viewModelScope.launch { connectAndStart() }
            return
        }
        val detail = show ?: return
        restartFromCurrentPosition(controller, detail)
    }

    fun dismissError() = _state.update { it.copy(error = null, errorRetryable = false) }

    override fun onCleared() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        _player.value = null
    }

    /**
     * Rimando lo spettacolo al servizio con la posizione corrente esplicita: in questo modo il
     * servizio risolve un URL nuovo (utile dopo che i tentativi automatici sono falliti) ma non
     * applica la regola di ripresa, che partirebbe dall'ultima posizione salvata.
     */
    private fun restartFromCurrentPosition(controller: MediaController, detail: ShowDetail) {
        dismissError()
        val position = controller.currentPosition.coerceAtLeast(0)
        controller.setMediaItem(PlaybackRequests.forShow(detail), position)
        controller.prepare()
        controller.play()
    }

    private fun currentController(): MediaController? =
        controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let { future ->
            try {
                future.get()
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Riallineo lo stato della UI al player dopo ogni gruppo di eventi. Un errore resta visibile
     * finché lo spettacolo di questa schermata non torna davvero pronto (READY, senza errori): così
     * un errore di risoluzione arrivato via sessione non sparisce al primo evento qualunque, ma
     * scompare da solo quando il servizio o un ritentativo automatico hanno ripristinato la visione.
     */
    private fun syncFrom(player: Player) {
        val casting = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        val tracks = player.currentTracks
        val textDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        val recovered = player.playerError == null &&
            player.playbackState == Player.STATE_READY &&
            player.currentMediaItem?.mediaId == showId
        _state.update { current ->
            current.copy(
                isCasting = casting,
                castDeviceName = if (casting) castSupport.connectedDeviceName() else null,
                speed = player.playbackParameters.speed,
                hasSubtitles = tracks.containsType(C.TRACK_TYPE_TEXT),
                subtitlesEnabled = !textDisabled && tracks.isTypeSelected(C.TRACK_TYPE_TEXT),
                error = player.playerError?.let(ErrorMessages::fromPlayback) ?: current.error.takeUnless { recovered },
            )
        }
    }

    private fun preferredTextGroup(tracks: Tracks): Tracks.Group? {
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        val deviceLanguage = Locale.getDefault().language
        return textGroups.firstOrNull { group ->
            group.length > 0 && group.getTrackFormat(0).language?.startsWith(deviceLanguage) == true
        } ?: textGroups.firstOrNull()
    }

    companion object {
        private const val TAG = "PlayerViewModel"

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                PlayerViewModel(
                    application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    savedStateHandle = createSavedStateHandle(),
                    catalogRepository = container.catalogRepository,
                    preferences = container.playbackPreferences,
                    castSupport = container.castSupport,
                    autoRetryTriggers = container.autoRetryTriggers,
                )
            }
        }
    }
}
