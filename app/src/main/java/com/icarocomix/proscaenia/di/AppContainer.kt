package com.icarocomix.proscaenia.di

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.icarocomix.proscaenia.BuildConfig
import com.icarocomix.proscaenia.core.network.DeviceIdentity
import com.icarocomix.proscaenia.core.network.NetworkMonitor
import com.icarocomix.proscaenia.core.network.PlatformHeadersInterceptor
import com.icarocomix.proscaenia.core.network.UserAgent
import com.icarocomix.proscaenia.core.network.UserAgentInterceptor
import com.icarocomix.proscaenia.data.auth.AuthApi
import com.icarocomix.proscaenia.data.auth.CredentialStore
import com.icarocomix.proscaenia.data.auth.SessionInterceptor
import com.icarocomix.proscaenia.data.auth.SessionAutoRelease
import com.icarocomix.proscaenia.data.auth.SessionManager
import com.icarocomix.proscaenia.data.local.PlaybackPositionStore
import com.icarocomix.proscaenia.data.local.PlaybackPreferences
import com.icarocomix.proscaenia.data.remote.ProscaeniaApi
import com.icarocomix.proscaenia.data.remote.ProscaeniaEndpoints
import com.icarocomix.proscaenia.data.repository.AuthRepositoryImpl
import com.icarocomix.proscaenia.data.repository.CatalogRepositoryImpl
import com.icarocomix.proscaenia.data.repository.StreamRepositoryImpl
import com.icarocomix.proscaenia.domain.repository.AuthRepository
import com.icarocomix.proscaenia.domain.repository.CatalogRepository
import com.icarocomix.proscaenia.domain.repository.StreamRepository
import com.icarocomix.proscaenia.playback.PlayerFactory
import com.icarocomix.proscaenia.playback.cast.CastSupport
import com.icarocomix.proscaenia.ui.common.AutoRetryTriggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Contenitore delle dipendenze dell'applicazione, con iniezione manuale.
 *
 * Ho evitato un framework di DI (Hilt, Koin) perché il grafo è piccolo e statico: un contenitore
 * esplicito rende evidente chi possiede cosa, non aggiunge elaborazione delle annotazioni alla build
 * (rilevante su una macchina con poche risorse) e resta comunque sostituibile nei test, perché
 * ViewModel e servizio ricevono interfacce di dominio.
 *
 * Il punto più delicato è la topologia dei client OkHttp. Parto da un client base che possiede
 * l'User-Agent, gli header di piattaforma (Current-Build, DeviceId, DeviceData, Origin, Referer,
 * necessari perché il backend accetti anche solo il login) e i timeout; da questo derivo con
 * `newBuilder` il client delle API (con l'interceptor di sessione a token) e quello dei media
 * (senza). Derivarli dallo stesso client fa sì che condividano pool di connessioni e dispatcher,
 * quindi non apro connessioni TLS doppie verso lo stesso host. Il client dei media non ha
 * l'interceptor di sessione perché un 403 sul flusso significa URL firmato scaduto, non sessione
 * scaduta, e va gestito dal player rigenerando il link; porta però gli header di piattaforma, perché
 * il server dei video e delle licenze li richiede come farebbe un browser dalla pagina del sito.
 */
@OptIn(UnstableApi::class)
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val userAgent: String = UserAgent.value

    val endpoints = ProscaeniaEndpoints(BuildConfig.PROSCAENIA_BASE_URL.toHttpUrl())

    val deviceIdentity = DeviceIdentity(appContext)

    private val credentialStore = CredentialStore(appContext)

    private val baseHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(UserAgentInterceptor(userAgent))
        .addInterceptor(PlatformHeadersInterceptor(endpoints, deviceIdentity))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val sessionManager = SessionManager(credentialStore, AuthApi(baseHttpClient, endpoints), deviceIdentity.deviceId)

    /**
     * Scope applicativo per il lavoro che deve sopravvivere a activity e servizio, come il rilascio
     * del posto dispositivo all'uscita. SupervisorJob: un errore in un lavoro non cancella gli altri.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionAutoRelease = SessionAutoRelease(sessionManager, applicationScope)

    val networkMonitor = NetworkMonitor(appContext)

    private val uiVisible = MutableStateFlow(false)

    /** Eventi su cui le schermate in errore riprovano da sole (rete tornata, app in primo piano, timer). */
    val autoRetryTriggers = AutoRetryTriggers(networkMonitor, uiVisible.asStateFlow())

    /**
     * Unico punto in cui l'activity comunica la visibilità dell'interfaccia: la uso sia per liberare il
     * posto dispositivo all'uscita sia per sospendere i ritentativi automatici in background.
     */
    @MainThread
    fun onUiVisibilityChanged(visible: Boolean) {
        uiVisible.value = visible
        sessionAutoRelease.onUiVisibilityChanged(visible)
    }

    /** Il servizio di riproduzione segnala qui se c'è una riproduzione attiva. */
    @MainThread
    fun onPlaybackActiveChanged(active: Boolean) = sessionAutoRelease.onPlaybackActiveChanged(active)

    private val apiHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .addInterceptor(SessionInterceptor(endpoints, sessionManager))
        .build()

    /**
     * Client per flussi video, licenze DRM e immagini. Allungo il timeout di lettura perché un
     * segmento video su rete mobile lenta può impiegare più di 20 secondi senza che sia un errore.
     */
    val mediaHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api = ProscaeniaApi(apiHttpClient, endpoints, deviceIdentity)

    val authRepository: AuthRepository = AuthRepositoryImpl(credentialStore, sessionManager)

    val catalogRepository: CatalogRepository = CatalogRepositoryImpl(api)

    val streamRepository: StreamRepository = StreamRepositoryImpl(api)

    val playbackPreferences = PlaybackPreferences(appContext)

    val positionStore = PlaybackPositionStore(appContext)

    val castSupport = CastSupport(appContext)

    val playerFactory: PlayerFactory by lazy { PlayerFactory(appContext, mediaHttpClient, userAgent) }
}
