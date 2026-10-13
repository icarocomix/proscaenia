package com.icarocomix.proscaenia.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.icarocomix.proscaenia.R
import java.util.Locale

/** Durata di uno spettacolo in forma leggibile, per esempio "1 h 45 min" o "50 min". */
@Composable
fun formatDuration(durationSeconds: Long): String {
    val totalMinutes = (durationSeconds + 30) / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        stringResource(R.string.duration_hours_minutes, hours.toInt(), minutes.toInt())
    } else {
        stringResource(R.string.duration_minutes, minutes.toInt())
    }
}

/** Posizione nel formato dei lettori multimediali: "m:ss" sotto l'ora, "h:mm:ss" oltre. */
fun formatTimestamp(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * Etichetta della velocità con la virgola decimale italiana e senza zeri superflui: "1x", "1,5x",
 * "1,25x". Uso il numero minimo di decimali necessari perché i passi da 0,25 ne richiedono due ma
 * mostrarli sempre ("1,00x") renderebbe i pulsanti più larghi e meno leggibili.
 */
fun formatSpeed(speed: Float): String {
    val hundredths = Math.round(speed * 100)
    val pattern = when {
        hundredths % 100 == 0 -> "%.0f"
        hundredths % 10 == 0 -> "%.1f"
        else -> "%.2f"
    }
    return String.format(Locale.ITALIAN, pattern, speed) + "x"
}
