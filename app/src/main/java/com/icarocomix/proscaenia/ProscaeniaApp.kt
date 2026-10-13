package com.icarocomix.proscaenia

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.icarocomix.proscaenia.core.logging.AppLog
import com.icarocomix.proscaenia.di.AppContainer

/**
 * Application che possiede il contenitore delle dipendenze e configura il caricamento immagini.
 *
 * Fornisco a Coil lo stesso client OkHttp dei media perché le miniature del catalogo possono essere
 * servite dalla stessa CDN dei video: condividere cookie, User-Agent e pool di connessioni evita
 * risposte 403 sulle copertine e connessioni TLS duplicate.
 */
class ProscaeniaApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Inizializzo il registro degli errori per primo, così anche un problema nella costruzione
        // del contenitore delle dipendenze finisce nel file di log consultabile dall'utente.
        AppLog.init(this)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.mediaHttpClient }))
            }
            .crossfade(true)
            .build()
}
