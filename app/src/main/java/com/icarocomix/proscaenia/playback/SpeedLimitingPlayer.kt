package com.icarocomix.proscaenia.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.icarocomix.proscaenia.domain.policy.SpeedPolicy

/**
 * Involucro del player esposto dalla MediaSession che intercetta i cambi di velocità.
 *
 * Ho scelto di centralizzare qui la gestione della velocità perché qualunque controller (la UI
 * dell'app, un'auto, un orologio) passa dalla sessione e quindi da questo oggetto: normalizzo il
 * valore richiesto al passo di 0,25x, lo notifico a [onSpeedRequested] perché venga salvato come
 * scelta dell'utente e applico al player un valore che, durante il Cast, non supera 2x. In questo
 * modo la preferenza salvata resta quella chiesta dall'utente (per esempio 2,5x) e torna in vigore
 * al rientro sul telefono, mentre il Chromecast riceve solo velocità che sa gestire.
 */
@UnstableApi
class SpeedLimitingPlayer(
    player: Player,
    private val onSpeedRequested: (Float) -> Unit,
) : ForwardingPlayer(player) {

    override fun setPlaybackSpeed(speed: Float) {
        val requested = SpeedPolicy.normalize(speed)
        onSpeedRequested(requested)
        super.setPlaybackSpeed(effectiveSpeed(requested))
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        val requested = SpeedPolicy.normalize(playbackParameters.speed)
        onSpeedRequested(requested)
        super.setPlaybackParameters(playbackParameters.withSpeed(effectiveSpeed(requested)))
    }

    private fun effectiveSpeed(requested: Float): Float =
        if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) SpeedPolicy.capForCast(requested) else requested
}
