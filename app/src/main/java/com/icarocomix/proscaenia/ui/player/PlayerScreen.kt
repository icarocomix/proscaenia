package com.icarocomix.proscaenia.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.domain.policy.SpeedPolicy
import com.icarocomix.proscaenia.ui.common.LoadingState
import com.icarocomix.proscaenia.ui.common.UiMessage
import com.icarocomix.proscaenia.ui.common.asString
import com.icarocomix.proscaenia.ui.common.formatSpeed

/**
 * Schermata del player.
 *
 * Uso la PlayerView di Media3 per video, barra di avanzamento e Play/Pausa con salti di 10 secondi,
 * perché è collaudata con qualunque Player (anche il controller remoto durante il Cast); sopra di
 * essa disegno in Compose la barra superiore con le funzioni specifiche dell'app, mostrata e nascosta
 * insieme ai controlli della PlayerView per un aspetto unitario.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }

    RequestNotificationPermission()
    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (player == null && state.error == null) LoadingState()

        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowShuffleButton(false)
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    // I sottotitoli li gestisco dalla barra superiore, con la logica di selezione
                    // esplicita del ViewModel: nascondo il pulsante della PlayerView per non averne due.
                    setShowSubtitleButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE },
                    )
                }
            },
            update = { view ->
                view.player = player
                // Durante il Cast non c'è video da guardare: tengo i controlli sempre visibili, così
                // l'utente può comandare il Chromecast senza dover toccare lo schermo nero.
                view.controllerShowTimeoutMs = if (state.isCasting) 0 else CONTROLLER_TIMEOUT_MS
                if (state.isCasting) view.showController()
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isCasting) {
            CastingNotice(
                deviceName = state.castDeviceName ?: stringResource(R.string.player_casting_default_device),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible || state.isCasting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopBar(
                state = state,
                onBack = onBack,
                onToggleSubtitles = viewModel::toggleSubtitles,
                onToggleVoiceBoost = viewModel::toggleVoiceBoost,
                onOpenSpeed = { showSpeedSheet = true },
                onResetSpeed = viewModel::resetSpeed,
            )
        }

        state.error?.let { error ->
            PlaybackErrorCard(
                message = error,
                onRetry = viewModel::retry,
                onDismiss = viewModel::dismissError,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    if (showSpeedSheet) {
        SpeedSheet(
            requestedSpeed = state.requestedSpeed,
            isCasting = state.isCasting,
            onSpeedSelected = { speed ->
                viewModel.selectSpeed(speed)
                showSpeedSheet = false
            },
            onDismiss = { showSpeedSheet = false },
        )
    }
}

@Composable
private fun PlayerTopBar(
    state: PlayerUiState,
    onBack: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onToggleVoiceBoost: () -> Unit,
    onOpenSpeed: () -> Unit,
    onResetSpeed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
            .safeDrawingPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
        }
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.castAvailable) CastButton()
        if (state.hasSubtitles) {
            IconButton(onClick = onToggleSubtitles) {
                Icon(
                    imageVector = if (state.subtitlesEnabled) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                    contentDescription = stringResource(
                        if (state.subtitlesEnabled) R.string.player_subtitles_disable else R.string.player_subtitles_enable,
                    ),
                    tint = Color.White,
                )
            }
        }
        IconButton(onClick = onToggleVoiceBoost) {
            Icon(
                imageVector = if (state.voiceBoostEnabled) Icons.Filled.RecordVoiceOver else Icons.Filled.VoiceOverOff,
                contentDescription = stringResource(
                    if (state.voiceBoostEnabled) R.string.player_voice_boost_disable else R.string.player_voice_boost_enable,
                ),
                tint = if (state.voiceBoostEnabled) MaterialTheme.colorScheme.secondary else Color.White,
            )
        }
        TextButton(onClick = onOpenSpeed) {
            Text(text = formatSpeed(state.speed), color = Color.White)
        }
        // Pulsante rapido "1x": compare solo quando serve, cioè quando la velocità scelta è diversa.
        if (state.requestedSpeed != SpeedPolicy.DEFAULT_SPEED) {
            TextButton(onClick = onResetSpeed) {
                Text(text = formatSpeed(SpeedPolicy.DEFAULT_SPEED), color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun CastingNotice(deviceName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CastConnected,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.player_casting_title, deviceName),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.player_casting_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlaybackErrorCard(
    message: UiMessage,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.widthIn(max = 420.dp).padding(24.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = message.asString(), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

/**
 * Da Android 13 la notifica multimediale, e con essa i controlli sulla schermata di blocco, richiede il
 * permesso POST_NOTIFICATIONS. Lo chiedo all'apertura del player, il momento in cui il bisogno è
 * evidente all'utente; se viene negato la riproduzione funziona comunque, senza notifica.
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val CONTROLLER_TIMEOUT_MS = 3_000
