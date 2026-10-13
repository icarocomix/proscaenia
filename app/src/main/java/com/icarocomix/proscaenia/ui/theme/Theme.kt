package com.icarocomix.proscaenia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/*
 * Tema volutamente sempre scuro, ispirato a una sala teatrale: fondo quasi nero, rosso sipario come
 * colore primario e oro delle luci di ribalta per gli accenti. Ho escluso il colore dinamico di
 * Material You perché l'identità visiva deve restare la stessa su tutti i dispositivi, e il tema
 * scuro fisso evita anche il contrasto con il player video, che è nero per natura. I titoli usano un
 * carattere con grazie per richiamare la locandina, il testo corrente resta sans-serif per leggibilità.
 */

private val StageBlack = Color(0xFF0F0B0C)
private val StageSurface = Color(0xFF1B1416)
private val StageSurfaceHigh = Color(0xFF261C1F)
private val CurtainRed = Color(0xFFB8283C)
private val CurtainRedDark = Color(0xFF5C0F1B)
private val FootlightGold = Color(0xFFD9B26F)
private val Ivory = Color(0xFFF3EAE0)
private val Muted = Color(0xFFBFB2A8)

private val ProscaeniaColors = darkColorScheme(
    primary = CurtainRed,
    onPrimary = Ivory,
    primaryContainer = CurtainRedDark,
    onPrimaryContainer = Ivory,
    secondary = FootlightGold,
    onSecondary = StageBlack,
    tertiary = FootlightGold,
    background = StageBlack,
    onBackground = Ivory,
    surface = StageSurface,
    onSurface = Ivory,
    surfaceVariant = StageSurfaceHigh,
    onSurfaceVariant = Muted,
    surfaceContainer = StageSurface,
    surfaceContainerHigh = StageSurfaceHigh,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val ProscaeniaTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = FontFamily.Serif),
        headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = titleLarge.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun ProscaeniaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ProscaeniaColors,
        typography = ProscaeniaTypography,
        content = content,
    )
}
