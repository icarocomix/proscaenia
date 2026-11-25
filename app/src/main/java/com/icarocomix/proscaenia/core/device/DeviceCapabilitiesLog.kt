package com.icarocomix.proscaenia.core.device

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.MediaDrm
import android.os.Build
import androidx.media3.common.C
import com.icarocomix.proscaenia.core.logging.AppLog

/**
 * Registra una volta per avvio le capacità del dispositivo rilevanti per la riproduzione protetta:
 * modello, versione di Android, tipo di dispositivo (TV o no) e livello Widevine con HDCP massimo.
 *
 * Scrivo su file (livello avviso) perché su una TV senza ADB il file di log è l'unico modo di sapere
 * se il CDM è L1 o L3: da quel livello dipende se il server licenze concede l'alta definizione.
 */
object DeviceCapabilitiesLog {

    private const val TAG = "DeviceCapabilities"

    /** Eseguo la lettura fuori dal thread principale: aprire il CDM inizializza OEMCrypto (decine di ms). */
    fun logAsync(context: Context) {
        val appContext = context.applicationContext
        Thread({ AppLog.w(TAG, describe(appContext)) }, "DeviceCapabilities").apply { isDaemon = true }.start()
    }

    private fun describe(context: Context): String {
        val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
        val isTv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION
        val widevine = try {
            val drm = MediaDrm(C.WIDEVINE_UUID)
            try {
                "Widevine ${drm.getPropertyString("securityLevel")}, HDCP max ${hdcpLabel(drm)}, " +
                    "versione CDM ${drm.getPropertyString(MediaDrm.PROPERTY_VERSION)}"
            } finally {
                drm.close()
            }
        } catch (e: Exception) {
            "Widevine non disponibile (${e.javaClass.simpleName}: ${e.message})"
        }
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${if (isTv) "TV" else "non TV"}, $widevine"
    }

    private fun hdcpLabel(drm: MediaDrm): String = when (drm.maxHdcpLevel) {
        MediaDrm.HDCP_NONE -> "nessuno"
        MediaDrm.HDCP_V1 -> "1.x"
        MediaDrm.HDCP_V2 -> "2.0"
        MediaDrm.HDCP_V2_1 -> "2.1"
        MediaDrm.HDCP_V2_2 -> "2.2"
        MediaDrm.HDCP_V2_3 -> "2.3"
        MediaDrm.HDCP_NO_DIGITAL_OUTPUT -> "nessuna uscita digitale"
        else -> "sconosciuto"
    }
}
