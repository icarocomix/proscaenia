package com.icarocomix.proscaenia.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.ui.common.formatSpeed
import com.icarocomix.proscaenia.ui.common.formatTimestamp
import kotlinx.coroutines.delay

/** Istantanea dello stato del player letta dall'OSD. */
@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    /** Intenzione dell'utente (play o pausa), indipendente dal buffering. */
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
)

/**
 * Leggo lo stato del player: il listener aggiorna subito play/pausa e buffering, il polling ogni
 * mezzo secondo fa avanzare la barra (Media3 non notifica la posizione, che cambia di continuo).
 * Tutto gira sul thread principale, che è il thread applicativo del MediaController.
 */
@Composable
private fun rememberPlaybackProgress(player: Player?): State<PlaybackProgress> =
    produceState(PlaybackProgress(), player) {
        val current = player ?: return@produceState
        fun snapshot() = PlaybackProgress(
            positionMs = current.currentPosition.coerceAtLeast(0),
            durationMs = current.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            bufferedMs = current.bufferedPosition.coerceAtLeast(0),
            playWhenReady = current.playWhenReady,
            buffering = current.playbackState == Player.STATE_BUFFERING,
        )
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                value = snapshot()
            }
        }
        current.addListener(listener)
        try {
            while (true) {
                value = snapshot()
                delay(PROGRESS_POLL_MS)
            }
        } finally {
            current.removeListener(listener)
        }
    }

/**
 * Barra di controllo (OSD) del player per TV, disegnata in Compose al posto del controller della
 * PlayerView.
 *
 * Il controller della PlayerView è pensato per il touch: con il telecomando riceve i tasti solo se la
 * View ha il focus, cosa che dentro una UI Compose non è garantita, e quindi i controlli risultavano
 * nascosti o irraggiungibili. Qui gestisco io i tasti in un unico punto, con queste regole (le stesse
 * dell'app Tizen):
 *
 * - OSD nascosto: OK/Su/Giù lo mostrano, Sinistra/Destra saltano di 10 secondi e lo mostrano;
 * - OSD visibile: il D-pad si muove tra i pulsanti, in ordine Indietro 10 s → Play/Pausa → Stop →
 *   Avanti 10 s → funzioni accessorie; a ogni apertura il focus parte da Play/Pausa;
 * - tasti multimediali del telecomando (Play/Pausa, Stop, Avanti/Indietro veloce) sempre attivi;
 * - Indietro con l'OSD visibile durante la riproduzione lo nasconde; altrimenti esce dal player;
 * - l'OSD si nasconde dopo [OSD_TIMEOUT_MS] senza tasti, ma solo mentre lo spettacolo scorre: in
 *   pausa, in buffering o con un errore resta visibile.
 *
 * Tutti i pulsanti stanno dentro i margini di overscan, così nessuno finisce tagliato fuori schermo.
 */
@Composable
fun TvPlayerOverlay(
    player: Player?,
    state: PlayerUiState,
    refocusKey: Any,
    onTogglePlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onStop: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onToggleVoiceBoost: () -> Unit,
    onOpenSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by rememberPlaybackProgress(player)
    var osdRequested by remember { mutableStateOf(true) }
    // Contatore delle interazioni: ogni tasto lo incrementa e fa ripartire il timer di chiusura.
    var interactions by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }

    // Con un errore a schermo la scheda d'errore ha la precedenza (e il focus) sull'OSD.
    val osdVisible = osdRequested && state.error == null
    val isRunning = progress.playWhenReady && !progress.buffering

    fun showOsd() {
        osdRequested = true
        interactions++
    }

    // Registrato dopo il BackHandler della schermata, quindi ha la precedenza finché è abilitato.
    BackHandler(enabled = osdVisible && isRunning) { osdRequested = false }

    LaunchedEffect(osdVisible, interactions, isRunning, state.isCasting) {
        if (osdVisible && isRunning && !state.isCasting) {
            delay(OSD_TIMEOUT_MS)
            osdRequested = false
        }
    }

    // A ogni apertura il focus va su Play/Pausa; a OSD chiuso torna al contenitore, che deve
    // continuare a ricevere i tasti per poter riaprire l'OSD.
    LaunchedEffect(osdVisible, refocusKey) {
        runCatching { if (osdVisible) playPauseFocus.requestFocus() else if (state.error == null) rootFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        onTogglePlayPause(); showOsd(); return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                        if (!progress.playWhenReady) onTogglePlayPause()
                        showOsd(); return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (progress.playWhenReady) onTogglePlayPause()
                        showOsd(); return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> {
                        onStop(); return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    AndroidKeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                    AndroidKeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                        onSeekForward(); showOsd(); return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
                    AndroidKeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                    AndroidKeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                        onSeekBack(); showOsd(); return@onPreviewKeyEvent true
                    }
                }
                // Con la scheda d'errore aperta il D-pad deve poter raggiungere i suoi pulsanti.
                if (state.error != null) return@onPreviewKeyEvent false
                if (osdVisible) {
                    interactions++
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onSeekBack(); showOsd(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onSeekForward(); showOsd(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP,
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> { showOsd(); true }
                    // Indietro e gli altri tasti di sistema proseguono il loro percorso normale.
                    else -> false
                }
            }
            .focusable(),
    ) {
        AnimatedVisibility(
            visible = osdVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            OsdPanel(
                state = state,
                progress = progress,
                playPauseFocus = playPauseFocus,
                onTogglePlayPause = onTogglePlayPause,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
                onStop = onStop,
                onToggleSubtitles = onToggleSubtitles,
                onToggleVoiceBoost = onToggleVoiceBoost,
                onOpenSpeed = onOpenSpeed,
            )
        }
    }
}

@Composable
private fun OsdPanel(
    state: PlayerUiState,
    progress: PlaybackProgress,
    playPauseFocus: FocusRequester,
    onTogglePlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onStop: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onToggleVoiceBoost: () -> Unit,
    onOpenSpeed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
            // Margini di overscan: 48 dp ai lati e 27 dp in basso sul canvas da 960×540 dp.
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProgressRow(progress)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OsdButton(Icons.Filled.Replay10, stringResource(R.string.player_rewind), onSeekBack)
                OsdButton(
                    icon = if (progress.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = stringResource(if (progress.playWhenReady) R.string.player_pause else R.string.player_play),
                    onClick = onTogglePlayPause,
                    modifier = Modifier.focusRequester(playPauseFocus),
                )
                OsdButton(Icons.Filled.Stop, stringResource(R.string.player_stop), onStop)
                OsdButton(Icons.Filled.Forward10, stringResource(R.string.player_forward), onSeekForward)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.hasSubtitles) {
                    OsdButton(
                        icon = if (state.subtitlesEnabled) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                        label = stringResource(
                            if (state.subtitlesEnabled) R.string.player_subtitles_disable else R.string.player_subtitles_enable,
                        ),
                        onClick = onToggleSubtitles,
                        showLabel = false,
                    )
                }
                OsdButton(
                    icon = if (state.voiceBoostEnabled) Icons.Filled.RecordVoiceOver else Icons.Filled.VoiceOverOff,
                    label = stringResource(
                        if (state.voiceBoostEnabled) R.string.player_voice_boost_disable else R.string.player_voice_boost_enable,
                    ),
                    onClick = onToggleVoiceBoost,
                    showLabel = false,
                    active = state.voiceBoostEnabled,
                )
                OsdButton(Icons.Filled.Speed, formatSpeed(state.speed), onOpenSpeed)
            }
        }
    }
}

@Composable
private fun ProgressRow(progress: PlaybackProgress) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(formatTimestamp(progress.positionMs), style = MaterialTheme.typography.labelLarge, color = Color.White)
        val duration = progress.durationMs.coerceAtLeast(1L)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((progress.bufferedMs.toFloat() / duration).coerceIn(0f, 1f))
                    .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((progress.positionMs.toFloat() / duration).coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(3.dp)),
            )
        }
        Text(
            text = if (progress.durationMs > 0) formatTimestamp(progress.durationMs) else "--:--",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/**
 * Pulsante dell'OSD. Con il focus si inverte (fondo chiaro, contenuto scuro) e si ingrandisce:
 * è lo stile più leggibile a distanza su un video di qualunque colore. Le funzioni accessorie
 * mostrano solo l'icona, ma conservano l'etichetta come descrizione per l'accessibilità.
 */
@Composable
private fun OsdButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    active: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val contentColor = when {
        focused -> Color.Black
        active -> MaterialTheme.colorScheme.secondary
        else -> Color.White
    }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = if (showLabel) RoundedCornerShape(24.dp) else CircleShape,
        color = if (focused) Color.White else Color.White.copy(alpha = 0.14f),
        contentColor = contentColor,
        modifier = modifier.graphicsLayer {
            val scale = if (focused) 1.1f else 1f
            scaleX = scale
            scaleY = scale
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 14.dp else 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = if (showLabel) null else label, modifier = Modifier.size(22.dp))
            if (showLabel) {
                Spacer(Modifier.size(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

private const val OSD_TIMEOUT_MS = 5_000L
private const val PROGRESS_POLL_MS = 500L
