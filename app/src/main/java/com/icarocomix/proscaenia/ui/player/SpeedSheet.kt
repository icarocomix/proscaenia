package com.icarocomix.proscaenia.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.domain.policy.SpeedPolicy
import com.icarocomix.proscaenia.ui.common.formatSpeed

/**
 * Pannello di scelta della velocità: tutte le velocità da 0,5x a 3x a passi di 0,25x e un pulsante
 * dedicato per tornare a 1x. Evidenzio la velocità scelta dall'utente e non quella effettiva, perché
 * durante il Cast le due differiscono e l'utente deve ritrovare la propria scelta; in quel caso
 * mostro anche una nota che spiega il limite di 2x.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(
    requestedSpeed: Float,
    isCasting: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.player_speed), style = MaterialTheme.typography.titleLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeedPolicy.availableSpeeds.forEach { speed ->
                    FilterChip(
                        selected = speed == requestedSpeed,
                        onClick = { onSpeedSelected(speed) },
                        label = { Text(formatSpeed(speed)) },
                    )
                }
            }
            if (isCasting && requestedSpeed > SpeedPolicy.MAX_CAST_SPEED) {
                Text(
                    text = stringResource(R.string.player_speed_cast_cap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            OutlinedButton(
                onClick = { onSpeedSelected(SpeedPolicy.DEFAULT_SPEED) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.player_speed_reset))
            }
        }
    }
}
