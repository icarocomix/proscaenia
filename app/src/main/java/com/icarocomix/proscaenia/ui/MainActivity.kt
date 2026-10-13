package com.icarocomix.proscaenia.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.icarocomix.proscaenia.ProscaeniaApp
import com.icarocomix.proscaenia.ui.navigation.AppNavHost
import com.icarocomix.proscaenia.ui.navigation.Routes
import com.icarocomix.proscaenia.ui.theme.ProscaeniaTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Unica activity dell'app, ospite della navigazione Compose.
 *
 * Estendo AppCompatActivity, e non semplicemente ComponentActivity, perché il pulsante Cast
 * (MediaRouteButton) apre la finestra di scelta del dispositivo come DialogFragment e richiede quindi
 * una FragmentActivity con tema AppCompat.
 */
class MainActivity : AppCompatActivity() {

    /** Richieste di apertura del player arrivate dalla notifica multimediale, consumate dalla navigazione. */
    private val playerRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Decido la destinazione iniziale una sola volta: il login compare esclusivamente se non ci
        // sono credenziali salvate, cioè al primo avvio. La lettura apre l'archivio cifrato e costa
        // qualche decina di millisecondi, accettabili perché avvengono prima del primo frame.
        val container = (application as ProscaeniaApp).container
        val startDestination = if (container.authRepository.hasStoredCredentials()) Routes.CATALOG else Routes.LOGIN

        // Dopo una ricreazione dell'activity l'intent è già stato gestito e la navigazione è stata
        // ripristinata dallo stato salvato: rielaborarlo riaprirebbe il player una seconda volta.
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            ProscaeniaTheme {
                AppNavHost(
                    startDestination = startDestination,
                    playerRequests = playerRequests,
                    onPlayerRequestHandled = { playerRequests.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as ProscaeniaApp).container.onUiVisibilityChanged(true)
    }

    override fun onStop() {
        super.onStop()
        // Una rotazione ferma e ricrea l'activity senza che l'utente lasci l'app: in quel caso non
        // devo liberare il posto dispositivo, altrimenti ogni rotazione costerebbe un nuovo login.
        if (!isChangingConfigurations) {
            (application as ProscaeniaApp).container.onUiVisibilityChanged(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_PLAYER) return
        intent.getStringExtra(EXTRA_SHOW_ID)?.takeIf { it.isNotBlank() }?.let { playerRequests.value = it }
    }

    companion object {
        private const val ACTION_OPEN_PLAYER = "com.icarocomix.proscaenia.action.OPEN_PLAYER"
        private const val EXTRA_SHOW_ID = "show_id"

        /** Intent usato dalla notifica del servizio per riportare l'utente sul player dello spettacolo. */
        fun openPlayerIntent(context: Context, showId: String?): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (showId != null) putExtra(EXTRA_SHOW_ID, showId) }
    }
}
