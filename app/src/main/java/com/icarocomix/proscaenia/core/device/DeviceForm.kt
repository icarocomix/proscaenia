package com.icarocomix.proscaenia.core.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Forma del dispositivo su cui gira l'app.
 *
 * Lo stesso APK serve telefono e Android TV: le differenze di comportamento (interfaccia 10-foot,
 * controlli da telecomando, audio che si ferma quando l'app esce dal primo piano) le attivo solo
 * quando il sistema dichiara la modalità televisione, così il telefono e il percorso Cast restano
 * esattamente come prima.
 */
object DeviceForm {

    @Volatile
    private var cached: Boolean? = null

    /**
     * Considero TV un dispositivo in modalità UI "televisione" oppure con la funzionalità leanback:
     * alcuni box economici dichiarano solo la seconda. Il risultato non cambia durante la vita del
     * processo, quindi lo calcolo una volta sola.
     */
    fun isTelevision(context: Context): Boolean {
        cached?.let { return it }
        val appContext = context.applicationContext
        val uiModeTv = appContext.getSystemService(UiModeManager::class.java)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val leanback = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return (uiModeTv || leanback).also { cached = it }
    }
}
