package com.icarocomix.proscaenia.ui.tv

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Vero quando l'interfaccia gira su una TV: le schermate scelgono il layout 10-foot. */
val LocalTvMode = staticCompositionLocalOf { false }

/**
 * Larghezza logica del "canvas" TV, in dp. È il riferimento delle linee guida 10-foot di Android TV
 * (1920 px a densità xhdpi): con questa larghezza tutte le misure in dp hanno sullo schermo la stessa
 * dimensione fisica relativa, a 1080p come a 4K.
 */
private const val TV_CANVAS_WIDTH_DP = 960f

/**
 * Margini di sicurezza per l'overscan dei televisori (circa 5% orizzontale e verticale sul canvas di
 * 960×540 dp): nessun contenuto interattivo deve finire sotto il bordo del pannello.
 */
val TvSafePadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp)

/**
 * Applico il modo TV a tutto il contenuto.
 *
 * Normalizzo la densità perché i televisori dichiarano valori molto diversi a parità di risoluzione:
 * un pannello 1080p a 480 dpi espone solo 640 dp di larghezza e ogni elemento appare ingrandito di
 * metà (è il sintomo "home troppo grande, il pulsante Riproduci è in fondo"). Ricavo la densità dalla
 * larghezza reale in pixel, così il canvas è sempre di 960 dp; mantengo la scala dei caratteri scelta
 * dall'utente nelle impostazioni di accessibilità. Sul telefono non tocco nulla.
 */
@Composable
fun TvAwareContent(isTv: Boolean, content: @Composable () -> Unit) {
    if (!isTv) {
        CompositionLocalProvider(LocalTvMode provides false, content = content)
        return
    }
    val metrics = LocalContext.current.resources.displayMetrics
    val configuration = LocalConfiguration.current
    val current = LocalDensity.current
    // Uso il lato lungo: la TV è sempre orizzontale, ma un box collegato in modo anomalo potrebbe
    // riportare le dimensioni scambiate.
    val longSidePx = maxOf(metrics.widthPixels, metrics.heightPixels)
    val density = remember(longSidePx, configuration.fontScale) {
        if (longSidePx <= 0) current else Density(longSidePx / TV_CANVAS_WIDTH_DP, configuration.fontScale)
    }
    CompositionLocalProvider(
        LocalTvMode provides true,
        LocalDensity provides density,
        content = content,
    )
}
