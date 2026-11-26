package com.icarocomix.proscaenia.playback

import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Spettacolo caricato nel servizio di riproduzione, come lo vede la UI fuori dal player. */
data class NowPlaying(
    val showId: String,
    val title: String,
    /** Vero se l'audio sta effettivamente uscendo (o sta per riprendere dopo il buffering). */
    val isPlaying: Boolean,
)

/**
 * Canale leggero, interno al processo, tra il servizio di riproduzione e le schermate di navigazione.
 *
 * Catalogo e scheda devono sapere se c'è uno spettacolo in sottofondo (per mostrare la barra "In
 * riproduzione" con Stop) senza collegare un MediaController: un controller legherebbe e quindi
 * creerebbe il servizio con il suo player anche quando nessuno sta guardando nulla. Il servizio
 * pubblica qui lo stato e raccoglie i comandi; se il servizio non esiste lo stato è nullo e un
 * comando non ha nulla da fermare, quindi può andare perso senza conseguenze.
 */
class PlaybackStatus {

    enum class Command { STOP }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    // Buffer minimo: i comandi arrivano da tocchi o tasti dell'utente, mai a raffica.
    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    @MainThread
    fun publish(value: NowPlaying?) {
        _nowPlaying.value = value
    }

    fun requestStop() {
        _commands.tryEmit(Command.STOP)
    }
}
