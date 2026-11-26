package com.icarocomix.proscaenia.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Evidenziazione del focus da telecomando.
 *
 * Su TV l'unico indicatore della posizione dell'utente è il focus, e l'overlay standard di Material
 * (una velatura del 10%) a tre metri di distanza non si vede. Disegno quindi un bordo oro "luci di
 * ribalta" e ingrandisco leggermente l'elemento, come fanno i launcher di Android TV. Il modificatore
 * va messo prima del `clickable`/`onClick` del componente, perché osserva il focus dei nodi che
 * seguono nella catena. Sul telefono non applica nulla.
 */
fun Modifier.tvFocusHighlight(
    shape: Shape,
    focusedScale: Float = 1.06f,
): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) focusedScale else 1f, label = "tvFocusScale")
    val borderColor = if (focused) MaterialTheme.colorScheme.secondary else Color.Transparent
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(width = 3.dp, color = borderColor, shape = shape)
}
