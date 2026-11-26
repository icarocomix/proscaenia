package com.icarocomix.proscaenia.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.domain.model.ShowDetail
import com.icarocomix.proscaenia.ui.common.ErrorState
import com.icarocomix.proscaenia.ui.common.LoadingState
import com.icarocomix.proscaenia.ui.common.NowPlayingBar
import com.icarocomix.proscaenia.ui.common.formatDuration
import com.icarocomix.proscaenia.ui.common.formatTimestamp
import com.icarocomix.proscaenia.ui.tv.LocalTvMode
import com.icarocomix.proscaenia.ui.tv.TvSafePadding
import com.icarocomix.proscaenia.ui.tv.tvFocusHighlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
    viewModel: DetailViewModel = viewModel(factory = DetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offlineProbeResult by viewModel.offlineProbeResult.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshResumePoint() }

    if (LocalTvMode.current) {
        TvDetailScreen(
            state = state,
            onRetry = viewModel::load,
            onPlay = onPlay,
            onOpenPlayer = onOpenPlayer,
            offlineProbe = if (viewModel.offlineProbeAvailable) {
                OfflineProbeUi(offlineProbeResult, viewModel::runOfflineLicenseProbe)
            } else {
                null
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            DetailUiState.Loading -> LoadingState(Modifier.padding(innerPadding))
            is DetailUiState.Failure -> ErrorState(
                message = current.message,
                onRetry = viewModel::load,
                modifier = Modifier.padding(innerPadding),
            )
            is DetailUiState.Content -> DetailContent(
                show = current.show,
                resumePositionMs = current.resumePositionMs,
                onPlay = { onPlay(current.show.id) },
                onOpenPlayer = onOpenPlayer,
                offlineProbe = if (viewModel.offlineProbeAvailable) {
                    OfflineProbeUi(offlineProbeResult, viewModel::runOfflineLicenseProbe)
                } else {
                    null
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun DetailContent(
    show: ShowDetail,
    resumePositionMs: Long,
    onPlay: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    offlineProbe: OfflineProbeUi?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AsyncImage(
            model = show.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NowPlayingBar(onOpenPlayer = onOpenPlayer)
            Text(text = show.title, style = MaterialTheme.typography.headlineSmall)
            val details = listOfNotNull(
                show.categoryName,
                show.durationSeconds?.let { formatDuration(it) },
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (resumePositionMs > 0) {
                        stringResource(R.string.detail_resume, formatTimestamp(resumePositionMs))
                    } else {
                        stringResource(R.string.detail_play)
                    },
                )
            }
            if (offlineProbe != null) {
                OutlinedButton(onClick = offlineProbe.onRun, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.detail_offline_probe))
                }
                offlineProbe.result?.let { result ->
                    Text(text = result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (show.synopsis.isNotBlank()) {
                Text(
                    text = show.synopsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Scheda per TV. Sul telefono la locandina a tutta larghezza occupa l'intera altezza di uno schermo
 * orizzontale (16:9 su 960 dp = 540 dp) e spinge "Riproduci" sotto il bordo: è il motivo per cui sulla
 * TV bisognava scorrere a lungo. Qui la locandina sta in una colonna a sinistra e titolo, dati e
 * pulsante principale nella colonna di destra, tutti visibili senza scorrere; il focus parte già su
 * "Riproduci", così basta premere OK. Il ritorno al catalogo è il tasto Indietro del telecomando.
 */
@Composable
private fun TvDetailScreen(
    state: DetailUiState,
    onRetry: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
    offlineProbe: OfflineProbeUi?,
) {
    when (state) {
        DetailUiState.Loading -> LoadingState()
        is DetailUiState.Failure -> ErrorState(message = state.message, onRetry = onRetry)
        is DetailUiState.Content -> {
            val show = state.show
            val playFocus = remember { FocusRequester() }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(TvSafePadding),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                AsyncImage(
                    model = show.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(0.42f)
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NowPlayingBar(onOpenPlayer = onOpenPlayer)
                    Text(
                        text = show.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(show.categoryName, show.durationSeconds?.let { formatDuration(it) })
                    if (details.isNotEmpty()) {
                        Text(
                            text = details.joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onPlay(show.id) },
                            modifier = Modifier
                                .focusRequester(playFocus)
                                .tvFocusHighlight(ButtonDefaults.shape),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                if (state.resumePositionMs > 0) {
                                    stringResource(R.string.detail_resume, formatTimestamp(state.resumePositionMs))
                                } else {
                                    stringResource(R.string.detail_play)
                                },
                            )
                        }
                        if (offlineProbe != null) {
                            OutlinedButton(
                                onClick = offlineProbe.onRun,
                                modifier = Modifier.tvFocusHighlight(ButtonDefaults.outlinedShape),
                            ) {
                                Text(stringResource(R.string.detail_offline_probe))
                            }
                        }
                    }
                    offlineProbe?.result?.let { result ->
                        Text(text = result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (show.synopsis.isNotBlank()) {
                        // Il testo non è focalizzabile e quindi non scorrerebbe con il D-pad: lo limito
                        // allo spazio disponibile con i puntini finali invece di tagliarlo a metà riga.
                        Text(
                            text = show.synopsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            LaunchedEffect(show.id) { runCatching { playFocus.requestFocus() } }
        }
    }
}

/** Stato e azione della sonda diagnostica della licenza offline (solo build debug). */
private data class OfflineProbeUi(val result: String?, val onRun: () -> Unit)
