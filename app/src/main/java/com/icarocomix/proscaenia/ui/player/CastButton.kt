package com.icarocomix.proscaenia.ui.player

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * Pulsante Cast standard di Google, integrato in Compose come View.
 *
 * Uso il MediaRouteButton originale invece di disegnarne uno mio perché gestisce da solo la ricerca
 * dei dispositivi, l'animazione di connessione e la finestra di scelta, ed è il comportamento che
 * l'utente conosce dalle altre app. Lo chiamo solo quando il Cast è disponibile: senza Google Play
 * Services `CastButtonFactory` fallirebbe, e la schermata semplicemente non lo mostra.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            MediaRouteButton(context).also { button ->
                CastButtonFactory.setUpMediaRouteButton(context.applicationContext, button)
            }
        },
        modifier = modifier.size(48.dp),
    )
}
