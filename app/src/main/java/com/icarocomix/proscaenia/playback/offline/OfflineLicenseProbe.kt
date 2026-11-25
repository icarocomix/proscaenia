package com.icarocomix.proscaenia.playback.offline

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import com.icarocomix.proscaenia.core.logging.AppLog
import com.icarocomix.proscaenia.domain.repository.StreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sonda diagnostica: verifica se il server licenze della piattaforma concede a questo client una
 * licenza Widevine persistente (offline), prerequisito per il download degli spettacoli dentro l'app.
 *
 * Non scarico nessun segmento video e non tocco mai chiavi in chiaro: chiedo al CDM del dispositivo
 * una licenza di tipo offline per lo stesso contenuto che il player riproduce in streaming e registro
 * come risponde il server. Se la politica della licenza non consente la persistenza, il CDM rifiuta la
 * risposta e l'helper solleva un'eccezione; se la consente, ottengo un keySetId e le durate residue.
 * Al termine rilascio la licenza, così la prova non lascia licenze attive associate all'account.
 */
@UnstableApi
class OfflineLicenseProbe(
    private val streamRepository: StreamRepository,
    private val dataSourceFactory: DataSource.Factory,
    private val drmDataSourceFactory: DataSource.Factory,
) {

    suspend fun run(showId: String): String = withContext(Dispatchers.IO) {
        val result = try {
            probe(showId)
        } catch (e: DrmSession.DrmSessionException) {
            // Raccolgo l'intera catena delle cause: il motivo utile (licenza non persistibile, rifiuto
            // del server, errore HTTP) è quasi sempre annidato sotto l'eccezione di sessione.
            "LICENZA OFFLINE RIFIUTATA: ${causeChain(e)}"
        } catch (e: Exception) {
            "SONDA NON COMPLETATA: ${causeChain(e)}"
        }
        AppLog.w(TAG, "show=$showId → $result")
        result
    }

    private suspend fun probe(showId: String): String {
        val source = streamRepository.resolve(showId)
        val part = source.feature ?: return "nessuna parte riproducibile nella scheda"
        val drm = part.drm ?: return "parte non protetta da DRM: il download non richiederebbe licenze"

        // Leggo il manifest e, se il PSSH non è dichiarato lì, il segmento di inizializzazione: mi serve
        // solo il formato con i dati di inizializzazione DRM, non il contenuto.
        val dataSource = dataSourceFactory.createDataSource()
        val manifest = DashUtil.loadManifest(dataSource, part.dashUrl.toUri())
        val format = DashUtil.loadFormatWithDrmInitData(dataSource, manifest.getPeriod(0))
            ?: return "nessun dato di inizializzazione DRM nel manifest"

        // Stessi header di dispositivo e stesso client HTTP del player: la licenza deve essere chiesta
        // esattamente come durante lo streaming, altrimenti un rifiuto non sarebbe significativo.
        val httpCallback = HttpMediaDrmCallback(drm.licenseUrl, true, drmDataSourceFactory).apply {
            drm.requestHeaders.forEach { (name, value) -> setKeyRequestProperty(name, value) }
        }
        val callback = ResponseLoggingCallback(httpCallback)
        val sessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(callback)
        val helper = OfflineLicenseHelper(sessionManager, DrmSessionEventListener.EventDispatcher())
        try {
            val keySetId = helper.downloadLicense(format)
            val durations = helper.getLicenseDurationRemainingSec(keySetId)
            val report = "LICENZA OFFLINE CONCESSA: keySetId ${keySetId.size} byte, " +
                "validità licenza ${describeSeconds(durations.first)}, " +
                "finestra di riproduzione ${describeSeconds(durations.second)}"
            // Il rilascio contatta di nuovo il server: se fallisce lo segnalo senza invalidare l'esito.
            val release = runCatching { helper.releaseLicense(keySetId) }
                .fold({ "licenza rilasciata" }, { "rilascio non riuscito: ${causeChain(it)}" })
            return "$report; $release"
        } finally {
            helper.release()
        }
    }

    /**
     * Registro la forma della risposta del server licenze: se il CDM non riesce a interpretarla devo
     * sapere se è un messaggio d'errore in chiaro (testo/JSON con HTTP 200) o un binario inatteso.
     * Mostro un'anteprima solo quando il contenuto è testo leggibile: una licenza vera è un binario
     * cifrato per il CDM e di essa registro soltanto la dimensione.
     */
    private class ResponseLoggingCallback(private val delegate: MediaDrmCallback) : MediaDrmCallback by delegate {
        override fun executeKeyRequest(uuid: java.util.UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
            AppLog.w(TAG, "richiesta licenza: tipo ${request.requestType}, ${request.data.size} byte")
            val result = delegate.executeKeyRequest(uuid, request)
            val response = result.data
            val printable = response.count { it in 0x20..0x7e || it == 0x0a.toByte() || it == 0x0d.toByte() || it == 0x09.toByte() }
            val preview = if (response.isNotEmpty() && printable * 10 >= response.size * 9) {
                "testo: " + String(response, Charsets.UTF_8).take(600)
            } else {
                "binario (primi byte ${response.take(8).joinToString(" ") { "%02x".format(it) }})"
            }
            AppLog.w(TAG, "risposta licenza: ${response.size} byte, $preview")
            return result
        }
    }

    private fun describeSeconds(seconds: Long): String = when (seconds) {
        Long.MAX_VALUE -> "illimitata"
        C.TIME_UNSET -> "non dichiarata"
        else -> "${seconds}s (~${seconds / 3_600}h)"
    }

    private fun causeChain(error: Throwable): String =
        generateSequence(error) { it.cause }.take(6).joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }

    private companion object {
        const val TAG = "OfflineLicenseProbe"
    }
}
