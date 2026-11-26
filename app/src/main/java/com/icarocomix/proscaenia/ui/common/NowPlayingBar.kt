package com.icarocomix.proscaenia.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.playback.NowPlaying
import com.icarocomix.proscaenia.playback.PlaybackStatus
import com.icarocomix.proscaenia.ui.tv.tvFocusHighlight
import kotlinx.coroutines.flow.StateFlow

/** Espone alla UI lo spettacolo in sottofondo e il comando di Stop, senza collegarsi al servizio. */
class NowPlayingViewModel(private val status: PlaybackStatus) : ViewModel() {

    val nowPlaying: StateFlow<NowPlaying?> = status.nowPlaying

    fun stop() = status.requestStop()

    companion object {
        val Factory = viewModelFactory {
            initializer { NowPlayingViewModel(appContainer().playbackStatus) }
        }
    }
}

/**
 * Barra "In riproduzione" per catalogo e scheda.
 *
 * Uscendo dal player con lo spettacolo in corso l'audio continua mentre si sceglie il contenuto
 * successivo; questa barra rende evidente cosa sta suonando e offre le due azioni naturali: tornare
 * al player oppure fermare subito l'audio. Scompare da sola quando la riproduzione non è più attiva.
 */
@Composable
fun NowPlayingBar(
    onOpenPlayer: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = viewModel(factory = NowPlayingViewModel.Factory),
) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = nowPlaying != null, modifier = modifier) {
        // Durante l'animazione di uscita il valore è già nullo: in quel caso non disegno nulla.
        val current = nowPlaying ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (current.isPlaying) R.string.now_playing_label else R.string.now_playing_paused_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { onOpenPlayer(current.showId) },
                    modifier = Modifier.tvFocusHighlight(ButtonDefaults.shape),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.now_playing_open))
                }
                OutlinedButton(
                    onClick = viewModel::stop,
                    modifier = Modifier.tvFocusHighlight(ButtonDefaults.outlinedShape),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.player_stop))
                }
            }
        }
    }
}
