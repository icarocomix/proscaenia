package com.icarocomix.proscaenia.core.network

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.UUID

/**
 * Identità del dispositivo inviata alla piattaforma negli header DeviceId e DeviceData.
 *
 * Il backend rifiuta il login (HTTP 401) se questi header mancano o sono vuoti, e registra i
 * dispositivi da cui l'account accede. Per questo genero l'identificativo una sola volta e lo
 * conservo: un valore nuovo a ogni avvio farebbe apparire l'app come un dispositivo sempre diverso,
 * con il rischio di raggiungere un eventuale limite di dispositivi dell'abbonamento.
 *
 * La struttura di DeviceData ricalca quella del client web, che la ricava da FingerprintJS; ho
 * mantenuto gli stessi valori descrittivi con cui il login è stato verificato contro il servizio
 * reale, perché non conosco le regole di validazione lato server e non voglio introdurre differenze
 * non necessarie rispetto al client ufficiale.
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().replace("-", "").also { generated ->
        prefs.edit { putString(KEY_DEVICE_ID, generated) }
    }

    val deviceData: String = JSONObject()
        .put("deviceId", deviceId)
        .put("deviceBrand", JSONObject.NULL)
        .put("deviceModel", "Android")
        .put("deviceSystemName", "Chrome")
        .put("deviceSystemVersion", UserAgent.CHROME_VERSION)
        .toString()

    private companion object {
        const val FILE_NAME = "device_identity"
        const val KEY_DEVICE_ID = "device_id"
    }
}
